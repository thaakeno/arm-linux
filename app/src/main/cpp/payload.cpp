#include <cstdio>
#include <unistd.h>

// Minimal Microdroid payload used only to keep the managed stock VM alive for Gate A.
// It deliberately has no dependency on libvm_payload so the APK can be built with the NDK
// alone. Guest readiness is verified by the host through adbd/vsock, not by this function.
extern "C" __attribute__((visibility("default"))) int AVmPayload_main() {
    setvbuf(stdin, nullptr, _IONBF, 0);
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    std::puts("DEV 2 LINUX Gate A payload running");
    for (;;) pause();
    return 0;
}
