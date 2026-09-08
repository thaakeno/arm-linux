#include <cstdio>
#include <unistd.h>

// Keep the Microdroid payload alive. Gate A readiness is proven by adbd/vsock from the host.
// Do not dlopen libvm_payload here: that library is not guaranteed to be available in this
// payload namespace on OEM builds, and exiting AVmPayload_main makes Microdroid shut down.
extern "C" __attribute__((visibility("default"))) int AVmPayload_main() {
    setvbuf(stdin, nullptr, _IONBF, 0);
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    std::puts("DEV 1 LINUX Gate A payload running");
    for (;;) pause();
    return 0;
}
