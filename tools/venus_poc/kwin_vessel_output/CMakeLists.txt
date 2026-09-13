cmake_minimum_required(VERSION 3.16)
project(vesseloutput LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(ECM REQUIRED NO_MODULE)
set(CMAKE_MODULE_PATH ${ECM_MODULE_PATH})
include(KDEInstallDirs)
include(KDECMakeSettings)
include(KDECompilerSettings NO_POLICY_SCOPE)

find_package(Qt5 5.15 REQUIRED COMPONENTS Core)
find_package(KF5 REQUIRED COMPONENTS CoreAddons)
find_package(XCB REQUIRED COMPONENTS XCB)
find_package(epoxy REQUIRED)

# KWinEffectsConfig.cmake exports KWin's own AUTOMOC setup for the
# KWIN_EFFECT_FACTORY* macros. Do not re-declare those macros here: their JSON
# argument positions are part of KWin's plugin build contract and differ across
# KWin generations. Importing KWinEffects makes this external effect use the
# exact metadata/IID rules of the KWin installation that will load it.
find_package(KWinEffects REQUIRED)
find_package(OpenGL REQUIRED COMPONENTS EGL)

kcoreaddons_add_plugin(vesseloutput
    SOURCES vesseloutput.cpp
    INSTALL_NAMESPACE "kwin/effects/plugins"
)

target_compile_definitions(vesseloutput PRIVATE EGL_NO_X11)
target_link_libraries(vesseloutput PRIVATE
    KWinEffects::kwineffects
    KWinEffects::kwinglutils
    XCB::XCB
    epoxy::epoxy
    Qt5::Core
    OpenGL::EGL
)

# Build a tiny ABI/discovery verifier from the same KWin headers and KDE
# libraries as the real effect. It instantiates only the plugin factory, never
# the Effect itself, so it is safe to run outside KWin. This catches the exact
# class of failure that previously produced a present vesseloutput.so that KWin
# silently refused to discover/load.
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/vesseloutput_probe.cpp" [=[
#include <QCoreApplication>
#include <QPluginLoader>
#include <QStringList>
#include <KPluginFactory>
#include <KPluginMetaData>
#include <kwineffects.h>
#include <iostream>

int main(int argc, char **argv)
{
    QCoreApplication app(argc, argv);
    const auto plugins = KPluginMetaData::findPlugins(
        QStringLiteral("kwin/effects/plugins"),
        [](const KPluginMetaData &data) {
            return data.pluginId() == QStringLiteral("vesseloutput");
        });

    if (plugins.isEmpty()) {
        std::cerr << "VESSEL_PLUGIN_DISCOVERY_FAILED libraryPaths="
                  << QCoreApplication::libraryPaths().join(QLatin1Char(':')).toStdString() << '\n';
        return 20;
    }

    const KPluginMetaData info = plugins.constFirst();
    QPluginLoader loader(info.fileName());
    const QString iid = loader.metaData().value(QStringLiteral("IID")).toString();
    if (iid != QString::fromLatin1(EffectPluginFactory_iid)) {
        std::cerr << "VESSEL_PLUGIN_IID_MISMATCH expected=" << EffectPluginFactory_iid
                  << " got=" << iid.toStdString() << " file=" << info.fileName().toStdString() << '\n';
        return 21;
    }

    QObject *instance = loader.instance();
    if (!instance) {
        std::cerr << "VESSEL_PLUGIN_FACTORY_LOAD_FAILED " << loader.errorString().toStdString() << '\n';
        return 22;
    }
    auto *factory = qobject_cast<KPluginFactory *>(instance);
    if (!factory || !dynamic_cast<KWin::EffectPluginFactory *>(factory)) {
        std::cerr << "VESSEL_PLUGIN_FACTORY_TYPE_FAILED file=" << info.fileName().toStdString() << '\n';
        return 23;
    }

    std::cout << "VESSEL_KWIN_PLUGIN_OK id=" << info.pluginId().toStdString()
              << " iid=" << iid.toStdString()
              << " file=" << info.fileName().toStdString() << '\n';
    return 0;
}
]=])

add_executable(vesseloutput_probe "${CMAKE_CURRENT_BINARY_DIR}/vesseloutput_probe.cpp")
target_link_libraries(vesseloutput_probe PRIVATE
    Qt5::Core
    KF5::CoreAddons
    KWinEffects::kwineffects
)
