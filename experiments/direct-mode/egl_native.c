// Native (untranslated) counterpart of the host thunks aw_egl_init / aw_egl_swap, so that glbench.c can be built
// for the host itself (arm64) as the "no translation at all" reference.
#include <EGL/egl.h>
static EGLDisplay d; static EGLSurface s;
int aw_egl_init(int w, int h) {
    const EGLint ca[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
                         EGL_BLUE_SIZE, 8, EGL_DEPTH_SIZE, 16, EGL_NONE};
    const EGLint xa[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE}, pa[] = {EGL_WIDTH, w, EGL_HEIGHT, h, EGL_NONE};
    EGLConfig c; EGLint n = 0;
    d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(d, 0, 0) || !eglChooseConfig(d, ca, &c, 1, &n) || n < 1) return 0;
    s = eglCreatePbufferSurface(d, c, pa);
    EGLContext x = eglCreateContext(d, c, EGL_NO_CONTEXT, xa);
    return s != EGL_NO_SURFACE && x != EGL_NO_CONTEXT && eglMakeCurrent(d, s, s, x);
}
int aw_egl_swap(void) { return eglSwapBuffers(d, s); }
