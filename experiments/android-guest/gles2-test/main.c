// GLES 2.0 test for the Android guest's host-GPU path: shader program, a CPU-generated texture and a
// rotating textured quad; touch moves the quad. Logs GL strings and "fps=..." with tag gles2test.
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <android_native_app_glue.h>
#include <math.h>
#include <stdint.h>
#include <time.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "gles2test", __VA_ARGS__)
static EGLDisplay dpy = EGL_NO_DISPLAY; static EGLSurface surf; static EGLContext ctx;
static int32_t W, H; static float cx = 0, cy = 0; static GLuint prog; static GLint uAngle, uCenter;
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }
static const char *VS = "attribute vec2 aPos; attribute vec2 aUv; uniform float uAngle; uniform vec2 uCenter; varying vec2 vUv;\n"
    "void main(){ float c=cos(uAngle), s=sin(uAngle); vec2 p=vec2(aPos.x*c-aPos.y*s, aPos.x*s+aPos.y*c)*0.6+uCenter; gl_Position=vec4(p,0.0,1.0); vUv=aUv; }\n";
static const char *FS = "precision mediump float; varying vec2 vUv; uniform sampler2D uTex;\n"
    "void main(){ gl_FragColor = texture2D(uTex, vUv); }\n";
static GLuint shader(GLenum type, const char *src) {
    GLuint s = glCreateShader(type); glShaderSource(s, 1, &src, 0); glCompileShader(s);
    GLint ok = 0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[512]; glGetShaderInfoLog(s, sizeof log, 0, log); LOG("shader compile failed: %s", log); }
    return s;
}
static void init_gl(struct android_app *app) {
    const EGLint attr[] = {EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
    const EGLint cattr[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLConfig cfg; EGLint n = 0, fmt;
    dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY); eglInitialize(dpy, 0, 0);
    if (!eglChooseConfig(dpy, attr, &cfg, 1, &n) || n < 1) { LOG("no GLES2 EGL config (error 0x%x)", eglGetError()); dpy = EGL_NO_DISPLAY; return; }
    eglGetConfigAttrib(dpy, cfg, EGL_NATIVE_VISUAL_ID, &fmt);
    ANativeWindow_setBuffersGeometry(app->window, 0, 0, fmt);
    surf = eglCreateWindowSurface(dpy, cfg, app->window, NULL);
    ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, cattr);
    if (ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, surf, surf, ctx)) { LOG("GLES2 context failed (error 0x%x)", eglGetError()); dpy = EGL_NO_DISPLAY; return; }
    eglQuerySurface(dpy, surf, EGL_WIDTH, &W); eglQuerySurface(dpy, surf, EGL_HEIGHT, &H);
    LOG("GL_VENDOR=%s GL_RENDERER=%s GL_VERSION=%s surface=%dx%d", glGetString(GL_VENDOR), glGetString(GL_RENDERER), glGetString(GL_VERSION), W, H);
    prog = glCreateProgram(); glAttachShader(prog, shader(GL_VERTEX_SHADER, VS)); glAttachShader(prog, shader(GL_FRAGMENT_SHADER, FS));
    glBindAttribLocation(prog, 0, "aPos"); glBindAttribLocation(prog, 1, "aUv"); glLinkProgram(prog);
    GLint ok = 0; glGetProgramiv(prog, GL_LINK_STATUS, &ok); LOG("program link=%d", ok);
    glUseProgram(prog); uAngle = glGetUniformLocation(prog, "uAngle"); uCenter = glGetUniformLocation(prog, "uCenter");
    static uint8_t pix[64 * 64 * 4];
    for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) { uint8_t *p = pix + (y * 64 + x) * 4; int c = ((x >> 3) ^ (y >> 3)) & 1; p[0] = c ? 255 : 30; p[1] = x * 4; p[2] = y * 4; p[3] = 255; }
    GLuint tex; glGenTextures(1, &tex); glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 64, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, pix);
}
static void draw(int frame) {
    static const GLfloat v[] = {-1, -1, 1, -1, -1, 1, 1, 1}, t[] = {0, 1, 1, 1, 0, 0, 1, 0};
    glViewport(0, 0, W, H); glClearColor(0.1f, 0.2f, 0.5f, 1); glClear(GL_COLOR_BUFFER_BIT);
    glUniform1f(uAngle, frame * 0.02f); glUniform2f(uCenter, cx, cy);
    glEnableVertexAttribArray(0); glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, v); glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, t);
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
    cx = AMotionEvent_getX(e, 0) / W * 2 - 1; cy = 1 - AMotionEvent_getY(e, 0) / H * 2;
    LOG("touch at %.0f,%.0f", AMotionEvent_getX(e, 0), AMotionEvent_getY(e, 0));
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
