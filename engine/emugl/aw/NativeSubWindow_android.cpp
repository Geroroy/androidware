// awgl implementation of emugl's NativeSubWindow.h for Android.
//
// Upstream creates a child window inside the emulator's UI window. On Android
// the "parent window" handed to awgl_set_window() already is the
// ANativeWindow we want to render into, so the "sub-window" is that window
// itself; we only hold a reference while emugl has an EGLSurface on it.
#include "NativeSubWindow.h"

#include <android/native_window.h>

EGLNativeWindowType createSubWindow(FBNativeWindowType p_window,
                                    int x, int y, int width, int height,
                                    SubWindowRepaintCallback repaint_callback,
                                    void* repaint_callback_param) {
    (void)x; (void)y; (void)width; (void)height;
    (void)repaint_callback; (void)repaint_callback_param;
    ANativeWindow* win = (ANativeWindow*)p_window;
    if (!win) return (EGLNativeWindowType)0;
    ANativeWindow_acquire(win);
    return (EGLNativeWindowType)win;
}

void destroySubWindow(EGLNativeWindowType win) {
    if (win) ANativeWindow_release((ANativeWindow*)win);
}

int moveSubWindow(FBNativeWindowType p_parent_window,
                  EGLNativeWindowType p_sub_window,
                  int x, int y, int width, int height) {
    // Geometry is owned by the app's view hierarchy. awgl_set_window()
    // re-creates the EGL surface on every call, so this is never reached with
    // a real change; report success.
    (void)p_parent_window; (void)p_sub_window;
    (void)x; (void)y; (void)width; (void)height;
    return 1;
}
