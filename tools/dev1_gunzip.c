#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <zlib.h>

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: dev1_gunzip INPUT.gz OUTPUT.tar\n");
        return 64;
    }

    gzFile in = gzopen(argv[1], "rb");
    if (!in) {
        fprintf(stderr, "gzopen(%s) failed\n", argv[1]);
        return 2;
    }
    FILE *out = fopen(argv[2], "wb");
    if (!out) {
        fprintf(stderr, "fopen(%s): %s\n", argv[2], strerror(errno));
        gzclose(in);
        return 3;
    }

    unsigned char buf[256 * 1024];
    unsigned long long total = 0;
    for (;;) {
        int n = gzread(in, buf, sizeof(buf));
        if (n < 0) {
            int zerr = 0;
            const char *msg = gzerror(in, &zerr);
            fprintf(stderr, "gzread failed zerr=%d: %s\n", zerr, msg ? msg : "unknown");
            fclose(out);
            gzclose(in);
            return 4;
        }
        if (n == 0) break;
        if (fwrite(buf, 1, (size_t)n, out) != (size_t)n) {
            fprintf(stderr, "fwrite failed after %llu bytes: %s\n", total, strerror(errno));
            fclose(out);
            gzclose(in);
            return 5;
        }
        total += (unsigned long long)n;
    }

    if (fclose(out) != 0) {
        fprintf(stderr, "fclose output failed: %s\n", strerror(errno));
        gzclose(in);
        return 6;
    }
    int rc = gzclose(in);
    if (rc != Z_OK) {
        fprintf(stderr, "gzclose failed: %d\n", rc);
        return 7;
    }

    fprintf(stderr, "DEV1_GUNZIP_OK bytes=%llu\n", total);
    return 0;
}
