// awgl - C API over emugl's libOpenglRender internals.
//
// This file replaces upstream's render_api.cpp (which is not compiled):
//   - the renderer listens on a caller-supplied unix socket path,
//   - the RenderWindow runs on its own thread (use_thread = true), so that
//     FrameBuffer initialisation and sub-window setup never touch the EGL
//     state of the caller's thread (important for an Android UI thread),
//   - no emulator-specific logging / crash-reporter plumbing.

#include "awgl.h"
#include "aw_log.h"

#include "DispatchTables.h"
#include "FrameBuffer.h"
#include "IOStream.h"
#include "RenderServer.h"
#include "RenderWindow.h"
#include "UnixStream.h"

#include "OpenGLESDispatch/EGLDispatch.h"
#include "OpenglRender/render_api.h"

#include "emugl/common/crash_reporter.h"
#include "emugl/common/logging.h"

#include <mutex>

#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/un.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

// Globals that upstream defines in render_api.cpp.
GLESv2Dispatch s_gles2;
GLESv1Dispatch s_gles1;
// Read by RenderServer::create() to pick the listening stream class.
extern "C" int gRendererStreamMode;
int gRendererStreamMode = RENDER_API_STREAM_MODE_UNIX;

namespace {

std::mutex s_lock;
RenderServer* s_server = NULL;
RenderWindow* s_window = NULL;
char s_addr[SocketStream::MAX_ADDRSTR_LEN];
int s_width = 0;
int s_height = 0;

// On Android a sub-window (= the app's Surface) can be attached later, so the
// FrameBuffer must pick a window-capable EGLConfig. On headless Linux
// (EGL_PLATFORM=surfaceless) no config has EGL_WINDOW_BIT, so don't ask.
#ifdef __ANDROID__
const bool kUseSubWindow = true;
#else
const bool kUseSubWindow = false;
#endif

// Post-callback trampoline. emugl hard-codes ydir = -1 ("bottom-to-top" per
// render_api.entries), but since emu-2.0 the ColorBuffer texture it reads
// back is stored vertically flipped (blitFromCurrentReadBuffer() draws
// through TextureDraw, which flips), so the rows actually arrive top-to-bottom
// (verified with tests/rc_client.py). Report what the data really is.
struct PostHook {
    awgl_post_cb cb;
    void* ctx;
};
PostHook* s_postHook = NULL;

void postTrampoline(void* context, int width, int height, int /*ydir*/,
                    int format, int type, unsigned char* pixels) {
    PostHook* hook = static_cast<PostHook*>(context);
    hook->cb(hook->ctx, width, height, 1 /* top-to-bottom */, format, type,
             pixels);
}

void coarseLogger(const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    size_t n = strlen(buf);
    while (n && buf[n - 1] == '\n') buf[--n] = 0;
    AWLOG("emugl: %s", buf);
}

void crashReporter(const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    AWLOG("emugl FATAL: %s", buf);
    abort();
}

#ifdef __ANDROID__
// Apps have stdout/stderr pointing at /dev/null, and emugl reports all its
// errors with fprintf(stderr). Redirect both into logcat once.
void* logPump(void* arg) {
    int fd = (int)(intptr_t)arg;
    char buf[1024];
    size_t have = 0;
    for (;;) {
        ssize_t n = read(fd, buf + have, sizeof(buf) - 1 - have);
        if (n <= 0) break;
        have += n;
        buf[have] = 0;
        char* start = buf;
        char* nl;
        while ((nl = strchr(start, '\n')) != NULL) {
            *nl = 0;
            if (*start) __android_log_write(ANDROID_LOG_INFO, "awgl", start);
            start = nl + 1;
        }
        have = strlen(start);
        if (have >= sizeof(buf) - 1) {  // over-long line: flush it as is
            __android_log_write(ANDROID_LOG_INFO, "awgl", start);
            have = 0;
        } else {
            memmove(buf, start, have);
        }
    }
    return NULL;
}

void redirectStdioToLogcat() {
    static bool done = false;
    if (done || getenv("AWGL_NO_STDIO_REDIRECT")) return;
    done = true;
    int fds[2];
    if (pipe(fds) != 0) return;
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    dup2(fds[1], 1);
    dup2(fds[1], 2);
    close(fds[1]);
    pthread_t t;
    if (pthread_create(&t, NULL, logPump, (void*)(intptr_t)fds[0]) == 0) {
        pthread_detach(t);
    }
}
#endif

}  // namespace

int awgl_start(const char* unix_socket_path, int width, int height) {
    std::lock_guard<std::mutex> guard(s_lock);

#ifdef __ANDROID__
    redirectStdioToLogcat();
#endif

    if (s_server || s_window) {
        AWLOG("awgl_start: already started");
        return -1;
    }
    if (!unix_socket_path || !unix_socket_path[0] || width <= 0 || height <= 0) {
        AWLOG("awgl_start: bad arguments");
        return -1;
    }
    if (strlen(unix_socket_path) >= sizeof(((struct sockaddr_un*)0)->sun_path)) {
        AWLOG("awgl_start: socket path too long: %s", unix_socket_path);
        return -1;
    }

    // A guest that disconnects while we are writing a reply must not kill
    // the process.
    signal(SIGPIPE, SIG_IGN);

    set_emugl_logger(coarseLogger);
    set_emugl_cxt_logger(getenv("AWGL_DEBUG") ? coarseLogger : NULL);
    set_emugl_crash_reporter(crashReporter);

    // EGL first: the GLES tables fall back to eglGetProcAddress().
    if (!init_egl_dispatch() ||
        !gles1_dispatch_init(&s_gles1) ||
        !gles2_dispatch_init(&s_gles2)) {
        AWLOG("awgl_start: could not load the system EGL/GLES libraries");
        return -2;
    }

    // use_thread = true: all FrameBuffer management happens on RenderWindow's
    // own thread.
    s_window = new RenderWindow(width, height, true, kUseSubWindow);
    if (!s_window->isValid()) {
        AWLOG("awgl_start: could not initialise the emulated framebuffer "
              "(EGL error 0x%x)", (unsigned)s_egl.eglGetError());
        delete s_window;
        s_window = NULL;
        return -3;
    }

    gRendererStreamMode = RENDER_API_STREAM_MODE_UNIX;
    // In/out: with the awgl patch RenderServer::create() listens on the path
    // passed in |s_addr| instead of inventing /tmp/android-$USER/qemu-gles-PID.
    snprintf(s_addr, sizeof(s_addr), "%s", unix_socket_path);
    s_server = RenderServer::create(s_addr, sizeof(s_addr));
    if (!s_server) {
        AWLOG("awgl_start: could not listen on %s", unix_socket_path);
        delete s_window;
        s_window = NULL;
        return -4;
    }
    s_server->start();

    s_width = width;
    s_height = height;

    const char* vendor = NULL;
    const char* renderer = NULL;
    const char* version = NULL;
    s_window->getHardwareStrings(&vendor, &renderer, &version);
    AWLOG("renderer up: %dx%d, socket %s", width, height, s_addr);
    AWLOG("GL_VENDOR   = %s", vendor ? vendor : "(null)");
    AWLOG("GL_RENDERER = %s", renderer ? renderer : "(null)");
    AWLOG("GL_VERSION  = %s", version ? version : "(null)");
    return 0;
}

void awgl_set_post_callback(awgl_post_cb cb, void* ctx) {
    std::lock_guard<std::mutex> guard(s_lock);
    if (!s_window) {
        AWLOG("awgl_set_post_callback: renderer not started");
        return;
    }
    PostHook* hook = cb ? new PostHook{cb, ctx} : NULL;
    // Synchronous (RenderWindow thread + FrameBuffer lock, which post() also
    // holds while calling back), so the old hook is unused once this returns.
    s_window->setPostCallback(hook ? postTrampoline : NULL, hook);
    delete s_postHook;
    s_postHook = hook;
}

int awgl_set_window(void* native_window, int x, int y, int w, int h) {
    std::lock_guard<std::mutex> guard(s_lock);
    if (!s_window) {
        AWLOG("awgl_set_window: renderer not started");
        return -1;
    }
    if (!kUseSubWindow) {
        AWLOG("awgl_set_window: not supported in this build (headless)");
        return -1;
    }
    // Always drop the previous EGL window surface: emugl would otherwise only
    // "move" the existing sub-window and keep rendering to the old one.
    s_window->removeSubWindow();
    if (!native_window) {
        return 0;
    }
    if (w <= 0 || h <= 0) {
        return -1;
    }
    // fbw/fbh are what emugl uses for the GL viewport of the window surface;
    // use the window size so the guest frame is scaled to fill it.
    bool ok = s_window->setupSubWindow((FBNativeWindowType)(uintptr_t)native_window,
                                       x, y, w, h, w, h, 1.0f, 0.0f);
    if (!ok) {
        AWLOG("awgl_set_window: setupSubWindow failed (EGL error 0x%x)",
              (unsigned)s_egl.eglGetError());
        return -1;
    }
    return 0;
}

void awgl_stop(void) {
    std::lock_guard<std::mutex> guard(s_lock);
    if (s_server) {
        // Same trick as upstream's stopOpenGLRenderer(): a dummy connection
        // whose client flags ask the accept loop to exit.
        UnixStream* dummy = new UnixStream(8);
        if (dummy->connect(s_addr) < 0) {
            AWLOG("awgl_stop: could not connect to %s; server thread leaked",
                  s_addr);
            delete dummy;
            s_server = NULL;
        } else {
            unsigned int* flags =
                    (unsigned int*)dummy->allocBuffer(sizeof(unsigned int));
            *flags = IOSTREAM_CLIENT_EXIT_SERVER;
            dummy->commitBuffer(sizeof(unsigned int));
            s_server->wait(NULL);
            delete s_server;  // closes + unlinks the listening socket
            s_server = NULL;
            delete dummy;
        }
    }
    if (s_window) {
        delete s_window;  // detaches the window, finalizes the FrameBuffer
        s_window = NULL;
    }
    delete s_postHook;
    s_postHook = NULL;
}

void awgl_get_gl_strings(const char** vendor, const char** renderer,
                         const char** version) {
    std::lock_guard<std::mutex> guard(s_lock);
    const char* v = NULL;
    const char* r = NULL;
    const char* ver = NULL;
    if (s_window) {
        s_window->getHardwareStrings(&v, &r, &ver);
    }
    if (vendor) *vendor = v;
    if (renderer) *renderer = r;
    if (version) *version = ver;
}
