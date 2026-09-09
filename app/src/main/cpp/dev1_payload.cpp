#include <cstdio>
#include <unistd.h>

extern "C" __attribute__((visibility("default"))) int AVmPayload_main() {
    setvbuf(stdin, nullptr, _IONBF, 0);
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    std::puts("DEV 1 LINUX Gate A payload running");
    for (;;) pause();
    return 0;
}
