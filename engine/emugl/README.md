# engine/emugl - standalone GL render server ("awgl")

Host side of the GPU path for the Android guest: the Android Emulator's
OpenGL ES renderer ("emugl"), rebuilt as a standalone render server that

1. listens on a **unix domain socket** for guest GL command streams
   (unmodified goldfish wire protocol: 4-byte client-flags word, then the
   gles1 / gles2 / renderControl command stream),
2. decodes them and calls the **system's native EGL / GLESv1_CM / GLESv2**
   directly (emugl's desktop-GL `Translator` libraries are not built),
3. hands every posted guest frame to a callback (pixels) and/or draws it
   straight into a native window (Android `ANativeWindow`).

| | |
|---|---|
| Upstream | `platform/external/qemu`, branch `emu-2.0-release`, directory `distrib/android-emugl` |
| License | emugl is Apache-2.0 (headers in every file; Khronos headers under their own MIT-style terms). Our glue in `aw/` follows the repo license. The sources are fetched, not vendored. |
| Targets | Linux x86_64 (headless test rig, Mesa llvmpipe) and Android arm64-v8a (NDK r29, API 28, 16 KiB pages) |

## Build

```sh
engine/emugl/build.sh linux     # -> build-linux/aw-gl-renderer (+ libawgl.a)
engine/emugl/build.sh android   # -> build-android/libawgl.so (+ libawgl.stripped.so, awgl.h)
```

`build.sh` runs `fetch.sh` (download/copy the pristine tree into `src/`,
gitignored), applies `patches/*.patch`, builds `emugen` for the build machine
and generates the three decoders into `build-*/gen`, then drives CMake
(`CMakeLists.txt`). Needs: cmake, a host C++ compiler, `patch`; for Android
the NDK (`ANDROID_NDK`, default `/opt/android-sdk/ndk-r29`).
No GL development packages are needed: headers come from the emugl tree and
the GL libraries are `dlopen()`ed at run time.

Linux runtime: `apt-get install libegl1 libgles1 libgles2 libegl-mesa0 libgl1-mesa-dri`.

## Run (Linux)

```sh
build-linux/aw-gl-renderer --socket /tmp/awgl.sock --width 480 --height 800 \
    [--dump-dir DIR] [--dump-every N]
```

Sets `EGL_PLATFORM=surfaceless` and `LIBGL_ALWAYS_SOFTWARE=1` unless already
set, logs the GL strings, prints one line per second with the number of
posted frames, writes every Nth frame to `DIR/frame-%06d.ppm` and always
`DIR/latest.ppm`, exits cleanly on SIGINT/SIGTERM.

`tests/rc_client.py SOCKET W H [FRAMES]` is a tiny protocol client
(renderControl + a few GLES2 opcodes) that creates a context/surface/color
buffer, draws a test pattern and posts it - an end-to-end check without a
guest.

Environment knobs: `ANDROID_EGL_LIB`, `ANDROID_GLESv1_LIB`,
`ANDROID_GLESv2_LIB` (library overrides, as upstream), `AWGL_DEBUG`
(verbose emugl context log), `AWGL_RAW_GL_STRINGS` (do not sanitise the GL
strings shown to the guest), `AWGL_NO_STDIO_REDIRECT` (Android: leave
stdout/stderr alone), `RENDERER_DUMP_DIR` (upstream: dump raw streams).

## C API (`aw/awgl.h`)

```c
int  awgl_start(const char *unix_socket_path, int width, int height);
void awgl_set_post_callback(awgl_post_cb cb, void *ctx);   /* pixels per post */
int  awgl_set_window(void *native_window, int x, int y, int w, int h); /* ANativeWindow* / NULL */
void awgl_stop(void);
void awgl_get_gl_strings(const char **vendor, const char **renderer, const char **version);
```

`libawgl.so` exports only these symbols (static libc++, version script
`aw/awgl.map`); a JNI wrapper should be its own library linking against it.
On Android stdout/stderr are redirected to logcat (tag `awgl`) on the first
`awgl_start()`, because emugl reports everything with `fprintf(stderr)`.

## Layout

```
fetch.sh, build.sh, CMakeLists.txt
mkpatch.sh                 maintainer helper: regenerate the patch from an edited src/
patches/0001-*.patch       all changes to upstream files
aw/awgl.h, awgl.cpp        C API; replaces upstream render_api.cpp
aw/dispatch.cpp            replaces host/libs/libOpenGLESDispatch/*.cpp
aw/glstrings.cpp           GL_VERSION / GL_EXTENSIONS clamp for the guest
aw/NativeSubWindow_android.cpp, NativeSubWindow_headless.cpp
aw/include/EGL/eglplatform.h   overrides upstream's X11-based header
aw/main.cpp                aw-gl-renderer (Linux)
aw/awgl.map                export list for libawgl.so
tests/rc_client.py
```

## What was changed, and why

Not compiled at all: `host/libs/Translator` (only its header
`GLcommon/GLutils.h` is included), `host/libs/libOpenGLESDispatch/*.cpp`,
`render_api.cpp`, `NativeSubWindow_{x11,win32,cocoa}`, Windows pipe code.

Replaced by our files (no upstream edits needed):

* **Dispatch** (`aw/dispatch.cpp`): loads `libEGL.so.1` / `libGLESv1_CM.so.1`
  / `libGLESv2.so.2` (Linux) or `libEGL.so` / `libGLESv1_CM.so` /
  `libGLESv2.so` (Android). Core functions via `dlsym`, extension functions
  fall back to `eglGetProcAddress`, anything still missing becomes a named
  stub that logs once instead of a NULL pointer. A summary with the missing
  names is logged at start. (What is missing on a real GLES library are
  desktop-GL names the Translator used internally - `glClearDepth`,
  `glOrtho`, `glBegin`, ... - none of which emugl calls.)
* **EGL platform header** (`aw/include/EGL/eglplatform.h`, first on the
  include path): no X11; `EGLNativeWindowType` is `ANativeWindow*` on Android.
* **Sub-window**: on Android `createSubWindow()` returns the given
  `ANativeWindow` (with a reference held); on Linux it is a stub.
* **API** (`aw/awgl.cpp`): uses `RenderWindow` with its dedicated thread
  (`use_thread = true`; upstream 2.0 hard-codes `false` in render_api.cpp), so
  FrameBuffer init / window setup never touch the caller's EGL state.
  Stops the server the same way upstream does (dummy connection with
  `IOSTREAM_CLIENT_EXIT_SERVER`).

`patches/0001-awgl-standalone-renderer.patch` (6 files, all hunks commented
with `awgl:`):

| File | Change | Why |
|---|---|---|
| `render_api_platform_types.h` | `FBNativeWindowType`: `uint32_t` -> `uintptr_t` on Linux/Android | it carries an `ANativeWindow*`, not an X11 Window id |
| `RenderServer.cpp`, `UnixStream.cpp` | `RenderServer::create()`'s `addr` is in/out; `UnixStream::listen()` binds to exactly that path (removing a stale socket) | upstream invents `/tmp/android-$USER/qemu-gles-<pid>` |
| `FrameBuffer.cpp` (init) | a host without GLES 1.x contexts is a warning, not a failure; `GL_OES_EGL_image` is then checked on the GLES2 context | distro Mesa is built with `-Dgles1=disabled` (see below) |
| `FrameBuffer.cpp` (finalize) | reset/delete the singleton | allow `awgl_stop()` + `awgl_start()` in one process |
| `RenderControl.cpp` | `rcGetGLString` passes strings through `awgl_filter_gl_string()` | see "GL strings" below |
| `lazy_instance.cpp` | memory barriers for non-x86 CPUs | upstream `#error`s on arm64 |

The wire protocol is untouched.

### GL strings shown to the guest

The guest's EGL/GLES libraries return the host's `GL_VERSION` and
`GL_EXTENSIONS` to apps verbatim. The Translator answered
`OpenGL ES 2.0` / `OpenGL ES-CM 1.1` and a short extension list; a real
driver answers e.g. `OpenGL ES 3.2 Mesa ...` plus many extensions this 2016
protocol cannot carry. `aw/glstrings.cpp` therefore reports
`OpenGL ES 2.0 (<host string>)` / `OpenGL ES-CM 1.1 (<host string>)` and only
the extensions the Translator used to advertise, where the host has them
(`GL_OES_EGL_image_external` is always added for GLES2 because the guest
encoder emulates it with 2D textures, as it did with the Translator).

### Frame callback orientation

emugl passes `ydir = -1` ("bottom-to-top") to its post callback, but since
emu-2.0 the texture it reads back is stored vertically flipped, so rows
really arrive top-to-bottom. `awgl` reports `ydir = +1` accordingly (checked
with `tests/rc_client.py`: the band drawn at the GL top ends up in the first
rows).

## Known limitations / risks

* **GLES 1.x on the Linux test rig**: Ubuntu/Debian Mesa cannot create
  GLES 1.x contexts (`eglCreateContext` -> `EGL_BAD_ALLOC`), so guest GLES1
  clients fail there (`rcCreateContext` returns 0). GLES2 works. A Mesa built
  with `-Dgles1=enabled` (or any driver with GLES1) is needed to exercise the
  GLES1 decoder on Linux. Android drivers provide GLES1.
* **Not run on a device yet**: `libawgl.so` builds and links; the
  `ANativeWindow` path (`awgl_set_window`) and GLES1 are untested.
* **Guest-created EGLImages from GL textures** (`rcCreateClientImage`,
  `EGL_GL_TEXTURE_2D_KHR`): emugl squeezes the host `EGLImageKHR` into a
  32-bit handle. Fine with the Translator (small integer handles), wrong for
  64-bit drivers that return pointers. Gralloc-buffer images (the normal
  SurfaceFlinger / app path, `rcBindTexture`) are not affected.
* Requires `EGL_KHR_gl_texture_2D_image` + `GL_OES_EGL_image` (init fails
  otherwise) and pbuffer support on the configs.
* The decoders trust the guest (as in the emulator): a hostile guest can
  crash the renderer process.
