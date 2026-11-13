// Shared workload for the direct-mode measurement (docs/direct-mode.md). Included by glbench.c (user-mode
// translation + host GL thunks) and by ../android-guest/gles2-test/main.c (same code inside the Android guest).
// One frame = GLB_NDRAW objects. Per object: "game logic" on the CPU (build a matrix, transform 36 vertices),
// one uniform upload, one client-side vertex array, one draw call. The target is 64x64 so that rasterisation
// (software on the PC) stays negligible and the time is CPU + GL call path.
#include <math.h>
#define GLB_NDRAW 300
#define GLB_FRAMES 200
static const char *GLB_VS = "attribute vec3 aPos; uniform mat4 uM; varying vec3 vC;\n"
    "void main(){ gl_Position = uM * vec4(aPos, 1.0); vC = aPos * 0.5 + 0.5; }\n";
static const char *GLB_FS = "precision mediump float; varying vec3 vC;\nvoid main(){ gl_FragColor = vec4(vC, 1.0); }\n";
static GLuint glb_prog; static GLint glb_uM; static float glb_cube[36 * 3], glb_work[36 * 3];
static GLuint glb_shader(GLenum type, const char *src) {
    GLuint s = glCreateShader(type); glShaderSource(s, 1, &src, 0); glCompileShader(s); return s;
}
static int glb_init(void) {
    static const signed char c[8][3] = {{-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},{-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}};
    static const unsigned char idx[36] = {0,1,2,0,2,3, 4,6,5,4,7,6, 0,4,5,0,5,1, 3,2,6,3,6,7, 0,3,7,0,7,4, 1,5,6,1,6,2};
    for (int i = 0; i < 36; i++) for (int k = 0; k < 3; k++) glb_cube[i * 3 + k] = c[idx[i]][k];
    glb_prog = glCreateProgram();
    glAttachShader(glb_prog, glb_shader(GL_VERTEX_SHADER, GLB_VS)); glAttachShader(glb_prog, glb_shader(GL_FRAGMENT_SHADER, GLB_FS));
    glBindAttribLocation(glb_prog, 0, "aPos"); glLinkProgram(glb_prog);
    GLint ok = 0; glGetProgramiv(glb_prog, GL_LINK_STATUS, &ok);
    glUseProgram(glb_prog); glb_uM = glGetUniformLocation(glb_prog, "uM"); glEnableVertexAttribArray(0);
    return ok;
}
static float glb_frame(int frame) {
    float acc = 0;
    glViewport(0, 0, 64, 64); glClearColor(0.1f, 0.1f, 0.1f, 1); glClear(GL_COLOR_BUFFER_BIT);
    for (int i = 0; i < GLB_NDRAW; i++) {
        float a = frame * 0.01f + i * 0.37f, ca = cosf(a), sa = sinf(a), s = 0.05f;
        float tx = ((i % 20) - 9.5f) * 0.1f, ty = ((i / 20) - 7.0f) * 0.12f;
        float m[16] = {ca * s, sa * s, 0, 0,  -sa * s, ca * s, 0, 0,  0, 0, s, 0,  tx, ty, 0, 1};
        for (int v = 0; v < 36; v++) {              // CPU-side transform, as a game's skinning/animation would do
            const float *p = glb_cube + v * 3; float *o = glb_work + v * 3;
            o[0] = p[0] * ca - p[1] * sa + p[2] * 0.1f; o[1] = p[0] * sa + p[1] * ca; o[2] = p[2] * ca + p[0] * 0.1f;
            acc += o[0] * o[1] + o[2];
        }
        glUniformMatrix4fv(glb_uM, 1, GL_FALSE, m);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, glb_work);
        glDrawArrays(GL_TRIANGLES, 0, 36);
    }
    return acc;
}
