cmake_minimum_required(VERSION 3.16)
project(vesseloutput LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(ECM REQUIRED NO_MODULE)
set(CMAKE_MODULE_PATH ${ECM_MODULE_PATH})
include(KDEInstallDirs)
include(KDECMakeSettings)
include(KDECompilerSettings NO_POLICY_SCOPE)
include(KDEClangFormat)
include(ECMSetupQtPluginMacroNames)

# KWin's effect factory macros carry their plugin JSON metadata in macro
# arguments. Standalone effect builds must teach CMake AUTOGEN about those
# macros exactly like KWin's own build does, otherwise Qt5 moc parses the
# expanded Q_PLUGIN_METADATA(... FILE ...) incorrectly and dies at "FILE".
ecm_setup_qtplugin_macro_names(
    JSON_ARG2
        "KWIN_EFFECT_FACTORY"
    JSON_ARG3
        "KWIN_EFFECT_FACTORY_ENABLED"
        "KWIN_EFFECT_FACTORY_SUPPORTED"
    JSON_ARG4
        "KWIN_EFFECT_FACTORY_SUPPORTED_ENABLED"
)

find_package(Qt5 5.15 REQUIRED COMPONENTS Core)
find_package(KF5 REQUIRED COMPONENTS CoreAddons)
# KWinEffects' imported targets expose XCB::XCB and epoxy::epoxy in their
# Debian/KWin 5.x link interfaces. Resolve those imported targets first.
find_package(XCB REQUIRED COMPONENTS XCB)
find_package(epoxy REQUIRED)
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
