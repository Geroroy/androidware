// aw-gl-renderer: standalone (headless) emugl render server for Linux.
//
//   aw-gl-renderer --socket PATH --width W --height H
//                  [--dump-dir DIR] [--dump-every N]
//
// Listens on the unix socket PATH for guest GL command streams, renders with
// the system EGL/GLES (Mesa, EGL_PLATFORM=surfaceless) and optionally dumps
// posted frames as PPM files.
#include "awgl.h"

#include <atomic>
#include <string>
#include <vector>

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {

std::atomic<bool> g_quit(false);
std::atomic<unsigned long> g_frames(0);
std::string g_dumpDir;
unsigned long g_dumpEvery = 1;

void onSignal(int) { g_quit = true; }

bool writePpm(const std::string& path, int w, int h, int ydir,
              const unsigned char* rgba) {
    // Write to a temp name and rename, so readers never see a partial file.
    std::string tmp = path + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (!f) return false;
    fprintf(f, "P6\n%d %d\n255\n", w, h);
    std::vector<unsigned char> row(3 * (size_t)w);
    for (int y = 0; y < h; y++) {
        // ydir < 0: first row in |rgba| is the bottom row; PPM is top-down.
        const unsigned char* src =
                rgba + 4 * (size_t)w * (size_t)(ydir < 0 ? h - 1 - y : y);
        for (int x = 0; x < w; x++) {
            row[3 * x + 0] = src[4 * x + 0];
            row[3 * x + 1] = src[4 * x + 1];
            row[3 * x + 2] = src[4 * x + 2];
        }
        fwrite(row.data(), 1, row.size(), f);
    }
    bool ok = fclose(f) == 0;
    if (ok) ok = rename(tmp.c_str(), path.c_str()) == 0;
    return ok;
}

void onPost(void*, int width, int height, int ydir, int format, int type,
            unsigned char* pixels) {
    unsigned long n = g_frames.fetch_add(1);
    if (g_dumpDir.empty()) return;
    if (format != 0x1908 /*GL_RGBA*/ || type != 0x1401 /*GL_UNSIGNED_BYTE*/) {
        return;
    }
    static bool warned = false;
    bool ok = writePpm(g_dumpDir + "/latest.ppm", width, height, ydir, pixels);
    if (n % g_dumpEvery == 0) {
        char name[64];
        snprintf(name, sizeof(name), "/frame-%06lu.ppm", n);
        ok = writePpm(g_dumpDir + name, width, height, ydir, pixels) && ok;
    }
    if (!ok && !warned) {
        warned = true;
        fprintf(stderr, "aw-gl-renderer: cannot write frames to %s: %s\n",
                g_dumpDir.c_str(), strerror(errno));
    }
}

void usage() {
    fprintf(stderr,
            "usage: aw-gl-renderer --socket PATH --width W --height H\n"
            "                      [--dump-dir DIR] [--dump-every N]\n");
}

}  // namespace

int main(int argc, char** argv) {
    const char* socketPath = NULL;
    int width = 0, height = 0;
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        const char* v = (i + 1 < argc) ? argv[i + 1] : NULL;
        if (a == "--socket" && v) { socketPath = v; i++; }
        else if (a == "--width" && v) { width = atoi(v); i++; }
        else if (a == "--height" && v) { height = atoi(v); i++; }
        else if (a == "--dump-dir" && v) { g_dumpDir = v; i++; }
        else if (a == "--dump-every" && v) { g_dumpEvery = strtoul(v, NULL, 10); i++; }
        else { usage(); return 2; }
    }
    if (!socketPath || width <= 0 || height <= 0 || g_dumpEvery == 0) {
        usage();
        return 2;
    }
    if (!g_dumpDir.empty()) {
        mkdir(g_dumpDir.c_str(), 0755);
    }

    // Headless Mesa: no X/Wayland, pbuffer-only configs. Both can be
    // overridden from the environment (e.g. to try a real GPU).
    setenv("EGL_PLATFORM", "surfaceless", 0);
    setenv("LIBGL_ALWAYS_SOFTWARE", "1", 0);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = onSignal;
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);

    int rc = awgl_start(socketPath, width, height);
    if (rc != 0) {
        fprintf(stderr, "aw-gl-renderer: awgl_start failed (%d)\n", rc);
        return 1;
    }
    awgl_set_post_callback(onPost, NULL);

    setvbuf(stdout, NULL, _IOLBF, 0);
    unsigned long last = 0;
    while (!g_quit) {
        sleep(1);
        unsigned long now = g_frames.load();
        printf("aw-gl-renderer: frames posted: %lu total, %lu in the last second\n",
               now, now - last);
        last = now;
    }

    printf("aw-gl-renderer: shutting down\n");
    awgl_stop();
    return 0;
}
