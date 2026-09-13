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
include(KDEClangFormat)
include(ECMSetupQtPluginMacroNames)

# Debian 12 ships KWin 5.27. In that series every KWIN_EFFECT_FACTORY*
# macro takes the embedded JSON metadata filename as argument 2. AutoMoc must
# be taught that exact signature or the .so can compile/install while carrying
# unusable Qt plugin metadata, which makes KWin silently skip the effect.
ecm_setup_qtplugin_macro_names(
    JSON_ARG2
        "KWIN_EFFECT_FACTORY"
        "KWIN_EFFECT_FACTORY_ENABLED"
        "KWIN_EFFECT_FACTORY_SUPPORTED"
        "KWIN_EFFECT_FACTORY_SUPPORTED_ENABLED"
)

find_package(Qt5 5.15 REQUIRED COMPONENTS Core)
find_package(KF5 REQUIRED COMPONENTS CoreAddons)
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
