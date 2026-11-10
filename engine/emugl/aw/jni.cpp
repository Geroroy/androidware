// JNI wrapper for the Android app (dev.androidware.app.GlRenderer).
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <stdio.h>
#include "awgl.h"

static ANativeWindow *g_window;

extern "C" {

JNIEXPORT jint JNICALL Java_dev_androidware_app_GlRenderer_nativeStart(JNIEnv *env, jclass, jstring path, jint w, jint h) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    int r = awgl_start(p, w, h);
    env->ReleaseStringUTFChars(path, p);
    return r;
}

JNIEXPORT jint JNICALL Java_dev_androidware_app_GlRenderer_nativeSetSurface(JNIEnv *env, jclass, jobject surface, jint w, jint h) {
    ANativeWindow *win = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    int r = awgl_set_window(win, 0, 0, w, h);
    if (g_window) ANativeWindow_release(g_window);
    g_window = win;
    return r;
}

JNIEXPORT void JNICALL Java_dev_androidware_app_GlRenderer_nativeStop(JNIEnv *, jclass) {
    awgl_set_window(nullptr, 0, 0, 0, 0);
    if (g_window) { ANativeWindow_release(g_window); g_window = nullptr; }
    awgl_stop();
}

JNIEXPORT jstring JNICALL Java_dev_androidware_app_GlRenderer_nativeGlStrings(JNIEnv *env, jclass) {
    const char *v = "", *r = "", *ver = "";
    awgl_get_gl_strings(&v, &r, &ver);
    char buf[512];
    snprintf(buf, sizeof buf, "%s / %s / %s", v ? v : "?", r ? r : "?", ver ? ver : "?");
    return env->NewStringUTF(buf);
}

}
