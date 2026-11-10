/*
 * androidware override of emugl's host/include/EGL/eglplatform.h.
 *
 * The upstream header pulls in <X11/Xlib.h> on every unix and types native
 * windows as X11 "Window". We talk to the system EGL directly, without X11:
 *   - Android:  EGLNativeWindowType is an ANativeWindow*.
 *   - Linux:    headless (EGL_PLATFORM=surfaceless, pbuffers only); the
 *               native types are opaque, pointer-sized handles (this matches
 *               Khronos' eglplatform.h when EGL_NO_X11 is defined).
 *
 * This directory is put *before* src/host/include on the include path, so
 * <EGL/egl.h> from the emugl tree picks this file up; the pristine tree does
 * not need to be patched for it.
 */
#ifndef __eglplatform_h_
#define __eglplatform_h_

#include <KHR/khrplatform.h>

#ifndef EGLAPI
#define EGLAPI KHRONOS_APICALL
#endif
#ifndef EGLAPIENTRY
#define EGLAPIENTRY KHRONOS_APIENTRY
#endif
#define EGLAPIENTRYP EGLAPIENTRY*

#if defined(__ANDROID__)

struct ANativeWindow;
struct egl_native_pixmap_t;

typedef void*                       EGLNativeDisplayType;
typedef struct egl_native_pixmap_t* EGLNativePixmapType;
typedef struct ANativeWindow*       EGLNativeWindowType;

#elif defined(__unix__)

typedef void*              EGLNativeDisplayType;
typedef khronos_uintptr_t  EGLNativePixmapType;
typedef khronos_uintptr_t  EGLNativeWindowType;

#else
#error "awgl: platform not supported"
#endif

/* EGL 1.2 types, renamed for consistency in EGL 1.3 */
typedef EGLNativeDisplayType NativeDisplayType;
typedef EGLNativePixmapType  NativePixmapType;
typedef EGLNativeWindowType  NativeWindowType;

typedef khronos_int32_t EGLint;

#endif /* __eglplatform_h_ */
