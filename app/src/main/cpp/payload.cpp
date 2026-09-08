#include <cstdio>
#include <dlfcn.h>
#include <unistd.h>

// Minimal Microdroid payload used to keep the managed stock VM alive for Gate A.
// Resolve libvm_payload dynamically because this project is built with the public NDK, while
// libvm_payload is supplied inside Microdroid itself. Reporting payload-ready is important: AVF
// has a boot-hang watchdog and may stop a VM whose payload never reaches the ready state.
extern "C" __attribute__((visibility("default"))) int AVmPayload_main() {
    setvbuf(stdin, nullptr, _IONBF, 0);
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    std::puts("DEV 2 LINUX Gate A payload running");

    void* handle = dlopen("libvm_payload.so", RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        std::fprintf(stderr, "DEV2 payload: dlopen(libvm_payload.so) failed: %s\n", dlerror());
        return 70;
    }

    using NotifyReady = void (*)();
    dlerror();
    auto notifyReady = reinterpret_cast<NotifyReady>(dlsym(handle, "AVmPayload_notifyPayloadReady"));
    const char* symbolError = dlerror();
    if (symbolError != nullptr || notifyReady == nullptr) {
        std::fprintf(stderr, "DEV2 payload: dlsym(AVmPayload_notifyPayloadReady) failed: %s\n",
                     symbolError != nullptr ? symbolError : "symbol missing");
        dlclose(handle);
        return 71;
    }

    notifyReady();
    std::puts("DEV 2 LINUX Gate A payload ready");

    // Stay alive for terminal/vsock interaction until the host explicitly stops the VM.
    for (;;) pause();

    dlclose(handle);
    return 0;
}
