// awgl: sanitise the GL strings reported to the guest.
//
// The guest's EGL/GLES libraries ask the host for GL_VERSION / GL_EXTENSIONS
// through renderControl (rcGetGLString) and hand them to applications
// unmodified. With emugl's Translator the host answered "OpenGL ES 2.0" /
// "OpenGL ES-CM 1.1" and a short, curated extension list. A real driver
// answers e.g. "OpenGL ES 3.2 ..." and dozens of extensions the emu-2.0 wire
// protocol cannot carry; guests then take ES3 code paths (PBOs, VAOs, ...)
// or call entry points that do not exist in the guest encoder.
//
// So: clamp the version to what the protocol implements and only pass
// through the extensions the Translator used to advertise (and that the
// guest encoder is therefore known to handle), if the host has them.
// Set AWGL_RAW_GL_STRINGS=1 to disable all of this for experiments.

#include "aw_log.h"

#include <stdlib.h>
#include <string.h>
#include <string>

#define GL_VENDOR                    0x1F00
#define GL_RENDERER                  0x1F01
#define GL_VERSION                   0x1F02
#define GL_EXTENSIONS                0x1F03
#define GL_SHADING_LANGUAGE_VERSION  0x8B8C

namespace {

// Same sets as Translator/GLES_V2/GLESv2Context.cpp and
// Translator/GLES_CM/GLEScmContext.cpp in emu-2.0-release.
const char* const kGles2Extensions[] = {
    "GL_OES_EGL_image",
    "GL_OES_depth24",
    "GL_OES_depth32",
    "GL_OES_element_index_uint",
    "GL_OES_texture_float",
    "GL_OES_texture_float_linear",
    "GL_OES_compressed_paletted_texture",
    "GL_OES_compressed_ETC1_RGB8_texture",
    "GL_OES_depth_texture",
    "GL_OES_texture_half_float",
    "GL_OES_texture_half_float_linear",
    "GL_OES_packed_depth_stencil",
    "GL_OES_vertex_half_float",
    "GL_OES_standard_derivatives",
    "GL_OES_texture_npot",
    "GL_OES_rgb8_rgba8",
    NULL
};

const char* const kGles1Extensions[] = {
    "GL_OES_blend_func_separate",
    "GL_OES_blend_equation_separate",
    "GL_OES_blend_subtract",
    "GL_OES_byte_coordinates",
    "GL_OES_compressed_paletted_texture",
    "GL_OES_point_size_array",
    "GL_OES_point_sprite",
    "GL_OES_single_precision",
    "GL_OES_stencil_wrap",
    "GL_OES_texture_env_crossbar",
    "GL_OES_texture_mirrored_repeat",
    "GL_OES_EGL_image",
    "GL_OES_element_index_uint",
    "GL_OES_draw_texture",
    "GL_OES_texture_cube_map",
    "GL_OES_read_format",
    "GL_OES_framebuffer_object",
    "GL_OES_depth24",
    "GL_OES_depth32",
    "GL_OES_fbo_render_mipmap",
    "GL_OES_rgb8_rgba8",
    "GL_OES_stencil1",
    "GL_OES_stencil4",
    "GL_OES_stencil8",
    "GL_OES_packed_depth_stencil",
    "GL_OES_matrix_palette",
    "GL_OES_extended_matrix_palette",
    "GL_OES_compressed_ETC1_RGB8_texture",
    NULL
};

bool hasExtension(const char* list, const char* ext) {
    size_t n = strlen(ext);
    for (const char* p = list; (p = strstr(p, ext)) != NULL; p += n) {
        bool startOk = (p == list) || (p[-1] == ' ');
        bool endOk = (p[n] == ' ') || (p[n] == '\0');
        if (startOk && endOk) return true;
    }
    return false;
}

}  // namespace

// Called from rcGetGLString() (see patches/). |hostStr| is what the host
// driver returned for |name| in the current guest context (may be NULL).
// The result stays valid until the next call on the same thread.
const char* awgl_filter_gl_string(unsigned int name, bool isGL2,
                                  const char* hostStr) {
    static const bool raw = getenv("AWGL_RAW_GL_STRINGS") != NULL;
    if (raw || !hostStr) return hostStr;

    static thread_local std::string out;
    switch (name) {
        case GL_VERSION:
            out = isGL2 ? "OpenGL ES 2.0 (" : "OpenGL ES-CM 1.1 (";
            out += hostStr;
            out += ")";
            return out.c_str();

        case GL_SHADING_LANGUAGE_VERSION:
            if (!isGL2) return hostStr;
            out = "OpenGL ES GLSL ES 1.00 (";
            out += hostStr;
            out += ")";
            return out.c_str();

        case GL_EXTENSIONS: {
            out.clear();
            const char* const* list = isGL2 ? kGles2Extensions : kGles1Extensions;
            for (; *list; ++list) {
                if (hasExtension(hostStr, *list)) {
                    out += *list;
                    out += ' ';
                }
            }
            if (isGL2 && hasExtension(hostStr, "GL_OES_EGL_image")) {
                // Emulated on the guest side: its GLES2 encoder rewrites
                // GL_TEXTURE_EXTERNAL_OES / samplerExternalOES to plain 2D
                // textures before anything reaches the host, exactly as it
                // did with the Translator (which advertised this
                // unconditionally).
                out += "GL_OES_EGL_image_external ";
            }
            return out.c_str();
        }

        default:
            return hostStr;
    }
}
