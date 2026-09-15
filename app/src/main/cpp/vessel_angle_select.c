#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdlib.h>
typedef void (*set_path_fn)(const char *);
__attribute__((constructor)) static void vessel_angle_select(void) {
    const char *path = getenv("VESSEL_ANGLE_PATH");
    if (!path || !*path) return;
    void *sym = dlsym(RTLD_DEFAULT, "epoxy_set_library_path");
    if (sym) ((set_path_fn)sym)(path);
}
