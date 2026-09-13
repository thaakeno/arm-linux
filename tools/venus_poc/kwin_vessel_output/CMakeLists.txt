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

find_package(Qt5 5.15 REQUIRED COMPONENTS Core)
find_package(KF5 REQUIRED COMPONENTS CoreAddons)
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
    Qt5::Core
    OpenGL::EGL
)
