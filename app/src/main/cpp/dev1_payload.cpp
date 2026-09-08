#include <cstdio>
#include <dlfcn.h>
#include <unistd.h>

// Minimal Microdroid payload used to keep the managed stock VM alive for Gate A.
// AVF requires the payload to explicitly report readiness; simply sleeping forever can leave
// the VM in a crosvm-running but payload-not-ready state and the framework may tear it down.
extern "C" __attribute__((visibility("default"))) int AVmPayload_main() {
    setvbuf(stdin, nullptr, _IONBF, 0);
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    std::puts("DEV 1 LINUX Gate A payload running");

    void* handle = dlopen("libvm_payload.so", RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        std::fprintf(stderr, "DEV1 payload: dlopen(libvm_payload.so) failed: %s\n", dlerror());
        return 70;
    }

    using NotifyReady = void (*)();
    dlerror();
    auto notifyReady = reinterpret_cast<NotifyReady>(dlsym(handle, "AVmPayload_notifyPayloadReady"));
    const char* symbolError = dlerror();
    if (symbolError != nullptr || notifyReady == nullptr) {
        std::fprintf(stderr, "DEV1 payload: dlsym(AVmPayload_notifyPayloadReady) failed: %s\n",
                     symbolError != nullptr ? symbolError : "symbol missing");
        dlclose(handle);
        return 71;
    }

    notifyReady();
    std::puts("DEV 1 LINUX Gate A payload ready");

    // Stay alive until the host explicitly stops the VM.
    for (;;) pause();

    dlclose(handle);
    return 0;
}
