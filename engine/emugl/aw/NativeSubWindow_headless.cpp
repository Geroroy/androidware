// awgl implementation of emugl's NativeSubWindow.h for headless Linux
// (replaces NativeSubWindow_x11.cpp; no X11 dependency). There is no window
// system, so sub-windows cannot be created; frames are delivered through the
// post callback only.
#include "NativeSubWindow.h"

EGLNativeWindowType createSubWindow(FBNativeWindowType p_window,
                                    int x, int y, int width, int height,
                                    SubWindowRepaintCallback repaint_callback,
                                    void* repaint_callback_param) {
    (void)p_window; (void)x; (void)y; (void)width; (void)height;
    (void)repaint_callback; (void)repaint_callback_param;
    return (EGLNativeWindowType)0;
}

void destroySubWindow(EGLNativeWindowType win) { (void)win; }

int moveSubWindow(FBNativeWindowType p_parent_window,
                  EGLNativeWindowType p_sub_window,
                  int x, int y, int width, int height) {
    (void)p_parent_window; (void)p_sub_window;
    (void)x; (void)y; (void)width; (void)height;
    return 0;
}
