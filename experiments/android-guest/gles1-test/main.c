// GLES 1.x test for the Android 7 guest: a 256x256 RGB565 "software framebuffer" is redrawn on the
// CPU every frame, uploaded with glTexSubImage2D and drawn as one quad (glOrthof). Touch moves a box.
// Logs "fps=..." and the GL strings with tag gles1test.
#include <EGL/egl.h>
#include <GLES/gl.h>
#include <android/log.h>
#include <android_native_app_glue.h>
#include <stdint.h>
#include <time.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "gles1test", __VA_ARGS__)
#define TW 256
#define TH 256
static EGLDisplay dpy = EGL_NO_DISPLAY; static EGLSurface surf; static EGLContext ctx;
static int32_t W, H, touch_x = 128, touch_y = 128;
static uint16_t pix[TW * TH]; static GLuint tex;
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }

static void init_gl(struct android_app *app) {
    const EGLint attr[] = {EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RED_SIZE, 5, EGL_GREEN_SIZE, 6, EGL_BLUE_SIZE, 5, EGL_NONE};
    EGLConfig cfg; EGLint n;
    dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY); eglInitialize(dpy, 0, 0);
    eglChooseConfig(dpy, attr, &cfg, 1, &n);
    EGLint fmt; eglGetConfigAttrib(dpy, cfg, EGL_NATIVE_VISUAL_ID, &fmt);
    ANativeWindow_setBuffersGeometry(app->window, 0, 0, fmt);  // window buffers must match the EGL config
    surf = eglCreateWindowSurface(dpy, cfg, app->window, NULL);
    ctx = eglCreateContext(dpy, cfg, NULL, NULL);
    eglMakeCurrent(dpy, surf, surf, ctx);
    eglQuerySurface(dpy, surf, EGL_WIDTH, &W); eglQuerySurface(dpy, surf, EGL_HEIGHT, &H);
    LOG("GL_VENDOR=%s GL_RENDERER=%s GL_VERSION=%s surface=%dx%d", glGetString(GL_VENDOR), glGetString(GL_RENDERER), glGetString(GL_VERSION), W, H);
    glGenTextures(1, &tex); glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, TW, TH, 0, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, NULL);
    glEnable(GL_TEXTURE_2D); glMatrixMode(GL_PROJECTION); glLoadIdentity(); glOrthof(0, 1, 1, 0, -1, 1);
    glMatrixMode(GL_MODELVIEW); glLoadIdentity();
}

static void draw(int frame) {
    for (int y = 0; y < TH; y++) for (int x = 0; x < TW; x++) {
        int r = (x + frame) & 31, g = (y * 2 + frame) & 63, b = ((x ^ y) >> 3) & 31;
        int in = x > touch_x - 20 && x < touch_x + 20 && y > touch_y - 20 && y < touch_y + 20;
        pix[y * TW + x] = in ? 0xffff : (uint16_t)((r << 11) | (g << 5) | b);
    }
    glViewport(0, 0, W, H);
    glClearColor(0.8f, 0.1f, 0.1f, 1);  // red border shows if the quad does not cover the surface
    glClear(GL_COLOR_BUFFER_BIT);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, TW, TH, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, pix);
    static const GLfloat v[] = {0, 0, 1, 0, 0, 1, 1, 1}, t[] = {0, 0, 1, 0, 0, 1, 1, 1};
    glEnableClientState(GL_VERTEX_ARRAY); glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glVertexPointer(2, GL_FLOAT, 0, v); glTexCoordPointer(2, GL_FLOAT, 0, t);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    eglSwapBuffers(dpy, surf);
}

static void on_cmd(struct android_app *app, int32_t cmd) {
    if (cmd == APP_CMD_INIT_WINDOW && app->window) init_gl(app);
    if (cmd == APP_CMD_TERM_WINDOW && dpy != EGL_NO_DISPLAY) {
        eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroyContext(dpy, ctx); eglDestroySurface(dpy, surf); eglTerminate(dpy); dpy = EGL_NO_DISPLAY;
    }
}

static int32_t on_input(struct android_app *app, AInputEvent *e) {
    if (AInputEvent_getType(e) != AINPUT_EVENT_TYPE_MOTION || !W || !H) return 0;
    touch_x = (int32_t)(AMotionEvent_getX(e, 0) * TW / W); touch_y = (int32_t)(AMotionEvent_getY(e, 0) * TH / H);
    LOG("touch action=%d at %.0f,%.0f pointers=%zu", AMotionEvent_getAction(e) & AMOTION_EVENT_ACTION_MASK,
        AMotionEvent_getX(e, 0), AMotionEvent_getY(e, 0), AMotionEvent_getPointerCount(e));
    return 1;
}

void android_main(struct android_app *app) {
    app->onAppCmd = on_cmd; app->onInputEvent = on_input;
    int frame = 0, frames = 0; double t0 = now();
    for (;;) {
        int ev; struct android_poll_source *src;
        while (ALooper_pollOnce(dpy != EGL_NO_DISPLAY ? 0 : -1, NULL, &ev, (void **)&src) >= 0) {
            if (src) src->process(app, src);
            if (app->destroyRequested) return;
        }
        if (dpy == EGL_NO_DISPLAY) continue;
        draw(frame++); frames++;
        double t = now();
        if (t - t0 >= 5) { LOG("fps=%.1f", frames / (t - t0)); frames = 0; t0 = t; }
    }
}
