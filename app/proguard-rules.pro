# Vessel Protocol 39 JNI/native entry points are called by name from C/C++.
# Keep only the concrete bridges instead of disabling optimization globally.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keep class com.example.dreamlinux.VesselWaylandPresenter { *; }
-keep class com.example.dreamlinux.VesselVirtioInput { *; }

# Android instantiates these from the manifest / framework.
-keep class com.example.dreamlinux.VesselActivity { *; }
-keep class com.example.dreamlinux.VmSessionService { *; }
-keep class com.example.dreamlinux.VesselBootReceiver { *; }

# terminal-emulator resolves these methods by the fixed Termux JNI class name.
-keep class com.termux.terminal.JNI { *; }

# Vessel PTY identity claim is also resolved by a fixed JNI symbol.
-keep class com.example.dreamlinux.VesselPtyNative { *; }

# Phase 4 proroot JNI bridges have fixed native symbol names.
-keep class com.example.dreamlinux.VesselProrootDisplayBridge { *; }
-keep class com.example.dreamlinux.VesselProrootProcessNative { *; }
