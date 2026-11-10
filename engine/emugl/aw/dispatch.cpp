// awgl replacement for emugl's host/libs/libOpenGLESDispatch/*.cpp.
//
// Upstream loads its own desktop-GL "Translator" libraries
// (lib64EGL_translator & co). We load the *system* EGL / GLES libraries and
// are tolerant about symbols a real driver does not export:
//   - core entry points are looked up with dlsym() in the right library,
//   - extension entry points fall back to eglGetProcAddress(),
//   - anything still missing gets a named stub that logs once and returns 0
//     instead of leaving a NULL pointer in the table (upstream would crash).
//
// Library names can be overridden with the same environment variables as
// upstream: ANDROID_EGL_LIB, ANDROID_GLESv1_LIB, ANDROID_GLESv2_LIB.

#include "OpenGLESDispatch/EGLDispatch.h"
#include "OpenGLESDispatch/GLESv1Dispatch.h"
#include "OpenGLESDispatch/GLESv2Dispatch.h"

#include "emugl/common/shared_library.h"

#include "aw_log.h"

#include <stdlib.h>
#include <string.h>
#include <string>

#if defined(__ANDROID__)
#define AWGL_DEFAULT_EGL_LIB     "libEGL.so"
#define AWGL_DEFAULT_GLES_CM_LIB "libGLESv1_CM.so"
#define AWGL_DEFAULT_GLES_V2_LIB "libGLESv2.so"
#else
#define AWGL_DEFAULT_EGL_LIB     "libEGL.so.1"
#define AWGL_DEFAULT_GLES_CM_LIB "libGLESv1_CM.so.1"
#define AWGL_DEFAULT_GLES_V2_LIB "libGLESv2.so.2"
#endif

EGLDispatch s_egl;

static emugl::SharedLibrary* s_egl_lib = NULL;
static emugl::SharedLibrary* s_gles1_lib = NULL;
static emugl::SharedLibrary* s_gles2_lib = NULL;

namespace {

emugl::SharedLibrary* openLib(const char* envName, const char* defaultName) {
    const char* libName = getenv(envName);
    if (!libName || !libName[0]) libName = defaultName;
    char error[256] = "";
    emugl::SharedLibrary* lib =
            emugl::SharedLibrary::open(libName, error, sizeof(error));
    if (!lib) {
        AWLOG("could not load %s: %s", libName, error);
    } else {
        AWLOG("loaded %s", libName);
    }
    return lib;
}

// Bookkeeping for the init-time report.
struct Report {
    int total = 0;
    int viaGetProc = 0;
    std::string viaGetProcNames;
    int missing = 0;
    std::string missingNames;
    void print(const char* what) const {
        AWLOG("%s dispatch: %d entry points, %d exported by the library, "
              "%d via eglGetProcAddress, %d missing (stubbed)",
              what, total, total - viaGetProc - missing, viaGetProc, missing);
        if (viaGetProc) {
            AWLOG("%s via eglGetProcAddress:%s", what, viaGetProcNames.c_str());
        }
        if (missing) {
            AWLOG("%s MISSING:%s", what, missingNames.c_str());
        }
    }
};

void* lookup(emugl::SharedLibrary* lib, const char* name, bool tryGetProc,
             void* stub, Report* r) {
    r->total++;
    void* f = (void*)lib->findSymbol(name);
    if (f) return f;
    if (tryGetProc && s_egl.eglGetProcAddress) {
        f = s_egl.eglGetProcAddress(name);
        if (f) {
            r->viaGetProc++;
            r->viaGetProcNames += ' ';
            r->viaGetProcNames += name;
            return f;
        }
    }
    r->missing++;
    r->missingNames += ' ';
    r->missingNames += name;
    return stub;
}

// One named stub per entry point, so that the log says *which* function the
// guest tried to use. "return (void)0;" is valid C++ for void functions.
#define AWGL_DEFINE_STUB(prefix, return_type, function_name, signature) \
    return_type KHRONOS_APIENTRY prefix##function_name signature { \
        static bool warned = false; \
        if (!warned) { \
            warned = true; \
            AWLOG("unimplemented " #prefix " entry point called: " #function_name); \
        } \
        return (return_type)0; \
    }

#define GLES1_STUB(return_type, function_name, signature, callargs) \
    AWGL_DEFINE_STUB(gles1_stub_, return_type, function_name, signature)
#define GLES2_STUB(return_type, function_name, signature, callargs) \
    AWGL_DEFINE_STUB(gles2_stub_, return_type, function_name, signature)

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-parameter"
LIST_GLES1_FUNCTIONS(GLES1_STUB, GLES1_STUB)
LIST_GLES2_FUNCTIONS(GLES2_STUB, GLES2_STUB)
#pragma GCC diagnostic pop

// Generic stub for names the decoders ask for that are not in our tables.
long generic_unimplemented() {
    static bool warned = false;
    if (!warned) {
        warned = true;
        AWLOG("an unimplemented GLES entry point was called");
    }
    return 0;
}

}  // namespace

bool init_egl_dispatch() {
    if (!s_egl_lib) {
        s_egl_lib = openLib("ANDROID_EGL_LIB", AWGL_DEFAULT_EGL_LIB);
    }
    if (!s_egl_lib) return false;

    bool ok = true;
#define RENDER_EGL_LOAD_FIELD(return_type, function_name, signature) \
    s_egl.function_name = (function_name##_t)s_egl_lib->findSymbol(#function_name); \
    if (!s_egl.function_name) { \
        AWLOG("EGL library lacks required symbol " #function_name); \
        ok = false; \
    }
#define RENDER_EGL_LOAD_OPTIONAL_FIELD(return_type, function_name, signature) \
    s_egl.function_name = (function_name##_t)s_egl_lib->findSymbol(#function_name); \
    if (!s_egl.function_name && s_egl.eglGetProcAddress) { \
        s_egl.function_name = \
                (function_name##_t)s_egl.eglGetProcAddress(#function_name); \
    } \
    if (!s_egl.function_name) { \
        AWLOG("EGL library lacks required extension symbol " #function_name); \
        ok = false; \
    }

    LIST_RENDER_EGL_FUNCTIONS(RENDER_EGL_LOAD_FIELD)
    LIST_RENDER_EGL_EXTENSIONS_FUNCTIONS(RENDER_EGL_LOAD_OPTIONAL_FIELD)
    return ok;
}

bool gles1_dispatch_init(GLESv1Dispatch* dispatch_table) {
    if (!s_gles1_lib) {
        s_gles1_lib = openLib("ANDROID_GLESv1_LIB", AWGL_DEFAULT_GLES_CM_LIB);
    }
    if (!s_gles1_lib) return false;

    Report r;
#define GLES1_LOOKUP(return_type, function_name, signature, callargs) \
    dispatch_table->function_name = reinterpret_cast<function_name##_t>( \
            lookup(s_gles1_lib, #function_name, false, \
                   (void*)gles1_stub_##function_name, &r));
#define GLES1_LOOKUP_EXT(return_type, function_name, signature, callargs) \
    dispatch_table->function_name = reinterpret_cast<function_name##_t>( \
            lookup(s_gles1_lib, #function_name, true, \
                   (void*)gles1_stub_##function_name, &r));

    LIST_GLES1_FUNCTIONS(GLES1_LOOKUP, GLES1_LOOKUP_EXT)
    r.print("GLESv1");
    return true;
}

bool gles2_dispatch_init(GLESv2Dispatch* dispatch_table) {
    if (!s_gles2_lib) {
        s_gles2_lib = openLib("ANDROID_GLESv2_LIB", AWGL_DEFAULT_GLES_V2_LIB);
    }
    if (!s_gles2_lib) return false;

    Report r;
#define GLES2_LOOKUP(return_type, function_name, signature, callargs) \
    dispatch_table->function_name = reinterpret_cast<function_name##_t>( \
            lookup(s_gles2_lib, #function_name, false, \
                   (void*)gles2_stub_##function_name, &r));
#define GLES2_LOOKUP_EXT(return_type, function_name, signature, callargs) \
    dispatch_table->function_name = reinterpret_cast<function_name##_t>( \
            lookup(s_gles2_lib, #function_name, true, \
                   (void*)gles2_stub_##function_name, &r));

    LIST_GLES2_FUNCTIONS(GLES2_LOOKUP, GLES2_LOOKUP_EXT)
    r.print("GLESv2");
    return true;
}

// The two functions below fill the per-thread decoder tables (every wire
// opcode, by name). Called once per guest connection; no logging here, the
// decoders override many names (glDrawElementsOffset, ...) with their own
// implementations right afterwards.
static void* decoder_proc(emugl::SharedLibrary* lib, const char* name) {
    void* func = NULL;
    if (lib) {
        func = (void*)lib->findSymbol(name);
    }
    if (!func && s_egl.eglGetProcAddress) {
        func = s_egl.eglGetProcAddress(name);
    }
    if (!func) {
        func = (void*)generic_unimplemented;
    }
    return func;
}

void* gles1_dispatch_get_proc_func(const char* name, void* userData) {
    (void)userData;
    return decoder_proc(s_gles1_lib, name);
}

void* gles2_dispatch_get_proc_func(const char* name, void* userData) {
    (void)userData;
    return decoder_proc(s_gles2_lib, name);
}
