/*
 * awgl - androidware GL render server.
 *
 * A thin C API over the Android Emulator's "emugl" host renderer
 * (emu-2.0-release, Apache-2.0), rebuilt to talk to the system's native
 * EGL / GLESv1_CM / GLESv2 libraries instead of emugl's desktop-GL
 * translator. The guest connects to a unix domain socket and speaks the
 * unmodified goldfish "OpenGL ES pipe" wire protocol (4-byte client flags
 * word, then the gles1/gles2/renderControl command stream).
 *
 * All functions may be called from any thread, but not concurrently with
 * each other (calls are serialised internally with a mutex).
 */
#ifndef AWGL_H
#define AWGL_H

#ifdef __cplusplus
extern "C" {
#endif

#if defined(__GNUC__)
#define AWGL_API __attribute__((visibility("default")))
#else
#define AWGL_API
#endif

/* Frame callback. |pixels| is width*height*4 bytes, format GL_RGBA (0x1908),
 * type GL_UNSIGNED_BYTE (0x1401), tightly packed. |ydir| is +1 when the first
 * row in |pixels| is the TOP row of the image (this is what awgl currently
 * always delivers), -1 when it is the bottom row (OpenGL order).
 * Called on a guest render thread with renderer locks held: copy the pixels
 * and return quickly; do not call awgl_* from inside the callback.
 * The buffer is only valid during the call. */
typedef void (*awgl_post_cb)(void *ctx, int width, int height, int ydir,
                             int format, int type, unsigned char *pixels);

/* Initialise the renderer with an emulated framebuffer of width x height and
 * start listening on the unix socket |unix_socket_path| (a stale socket file
 * at that path is removed first; path must be < 108 bytes).
 * No window is attached initially.
 * Returns 0 on success, negative on failure:
 *   -1 bad arguments / already started
 *   -2 could not load the system EGL / GLES libraries
 *   -3 EGL / framebuffer initialisation failed
 *   -4 could not listen on the socket */
AWGL_API int awgl_start(const char *unix_socket_path, int width, int height);

/* Install (or with cb == NULL remove) a callback that receives the pixels of
 * every frame the guest posts (glReadPixels path - slow but universal).
 * Must be called after a successful awgl_start(). */
AWGL_API void awgl_set_post_callback(awgl_post_cb cb, void *ctx);

/* Attach a native window; every guest post is then drawn straight into it
 * with EGL (scaled to w x h) and swapped. On Android |native_window| is an
 * ANativeWindow* (awgl takes its own reference; the caller keeps its own).
 * Call again with the same or another window to re-attach, e.g. after a
 * size change. NULL detaches (do this before the Surface is destroyed).
 * x, y are accepted for API symmetry and ignored on Android.
 * Returns 0 on success, -1 on failure / unsupported (Linux build). */
AWGL_API int awgl_set_window(void *native_window, int x, int y, int w, int h);

/* Stop the render server, close all guest connections and tear down the
 * EGL objects. awgl_start() may be called again afterwards. */
AWGL_API void awgl_stop(void);

/* Convenience: GL_VENDOR / GL_RENDERER / GL_VERSION of the host GLES2
 * context (valid between awgl_start and awgl_stop; NULL otherwise). */
AWGL_API void awgl_get_gl_strings(const char **vendor, const char **renderer,
                                  const char **version);

/* Number of frames the guest has posted to the screen since the library was
 * loaded (monotonic; for fps readouts). */
AWGL_API unsigned long awgl_get_post_count(void);

#ifdef __cplusplus
}
#endif

#endif /* AWGL_H */
