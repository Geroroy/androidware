// Direct-mode measurement: a 32-bit Arm (armeabi-v7a, bionic, static) program whose GL calls are host thunks.
// Run under the user-mode translator built with linux-user/arm/aw-thunk.c: qemu-arm ./glbench32
#include <GLES2/gl2.h>
#include <stdio.h>
#include <time.h>
#include "glbench.h"
int aw_egl_init(int w, int h); int aw_egl_swap(void);
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }
int main(void) {
    if (!aw_egl_init(64, 64)) { printf("no host GL\n"); return 1; }
    int ok = glb_init(); float acc = 0;
    for (int f = 0; f < 20; f++) { acc += glb_frame(f); aw_egl_swap(); }      // warm-up (translation, shader caches)
    double t0 = now();
    for (int f = 0; f < GLB_FRAMES; f++) { acc += glb_frame(f); aw_egl_swap(); }
    glFinish();
    double t1 = now();
    unsigned char px[4]; glReadPixels(32, 32, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    printf("glbench: link=%d draws/frame=%d ms_per_frame=%.3f (%d frames, chk %.1f, pixel %d,%d,%d, glerror 0x%x)\n",
           ok, GLB_NDRAW, (t1 - t0) * 1e3 / GLB_FRAMES, GLB_FRAMES, acc, px[0], px[1], px[2], glGetError());
    return 0;
}
