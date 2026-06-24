#include <jni.h>
#include <android/log.h>
#include <GLES3/gl3.h>

#include <algorithm>
#include <array>
#include <sstream>
#include <string>

namespace {

constexpr char kTag[] = "MATH-HOOK-LAB";

GLuint gSceneProgram = 0;
GLuint gSceneVao = 0;
GLuint gSceneVbo = 0;
GLuint gOverlayProgram = 0;
GLuint gOverlayVao = 0;
GLuint gOverlayVbo = 0;
GLint gOverlayOffset = -1;
GLint gOverlayScale = -1;
GLint gOverlayColor = -1;
int gWidth = 1;
int gHeight = 1;
float gTouchX = 0.5f;
float gTouchY = 0.5f;
bool gPressed = false;
bool gInitialized = false;
bool gLastRestoreOk = false;
unsigned long long gFrames = 0;
unsigned long long gRestorePasses = 0;
unsigned long long gRestoreFailures = 0;
std::string gInitStatus = "Hook simulation not initialized";

void logInfo(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message.c_str());
}

jstring toJavaString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled == GL_TRUE) {
        return shader;
    }

    GLint length = 0;
    glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
    std::string error(std::max(1, length), '\0');
    glGetShaderInfoLog(shader, length, nullptr, error.data());
    logInfo("Shader compilation failed: " + error);
    glDeleteShader(shader);
    return 0;
}

GLuint linkProgram(const char* vertexSource, const char* fragmentSource) {
    GLuint vertex = compileShader(GL_VERTEX_SHADER, vertexSource);
    GLuint fragment = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
    if (vertex == 0 || fragment == 0) {
        if (vertex != 0) glDeleteShader(vertex);
        if (fragment != 0) glDeleteShader(fragment);
        return 0;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);

    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked == GL_TRUE) {
        return program;
    }

    GLint length = 0;
    glGetProgramiv(program, GL_INFO_LOG_LENGTH, &length);
    std::string error(std::max(1, length), '\0');
    glGetProgramInfoLog(program, length, nullptr, error.data());
    logInfo("Program link failed: " + error);
    glDeleteProgram(program);
    return 0;
}

std::string glString(GLenum name) {
    const GLubyte* value = glGetString(name);
    return value == nullptr ? "unknown" : reinterpret_cast<const char*>(value);
}

struct GlState {
    GLint program = 0;
    GLint vertexArray = 0;
    GLint arrayBuffer = 0;
    GLint framebuffer = 0;
    GLint activeTexture = GL_TEXTURE0;
    GLint texture2d = 0;
    GLint viewport[4] = {0, 0, 0, 0};
    GLint scissorBox[4] = {0, 0, 0, 0};
    GLint blendSrcRgb = GL_ONE;
    GLint blendDstRgb = GL_ZERO;
    GLint blendSrcAlpha = GL_ONE;
    GLint blendDstAlpha = GL_ZERO;
    GLint blendEquationRgb = GL_FUNC_ADD;
    GLint blendEquationAlpha = GL_FUNC_ADD;
    GLboolean blend = GL_FALSE;
    GLboolean depth = GL_FALSE;
    GLboolean scissor = GL_FALSE;
    GLboolean cull = GL_FALSE;
    GLboolean depthMask = GL_TRUE;
    GLboolean colorMask[4] = {GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE};
};

GlState captureState() {
    GlState state;
    glGetIntegerv(GL_CURRENT_PROGRAM, &state.program);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &state.vertexArray);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &state.arrayBuffer);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &state.framebuffer);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &state.activeTexture);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &state.texture2d);
    glGetIntegerv(GL_VIEWPORT, state.viewport);
    glGetIntegerv(GL_SCISSOR_BOX, state.scissorBox);
    glGetIntegerv(GL_BLEND_SRC_RGB, &state.blendSrcRgb);
    glGetIntegerv(GL_BLEND_DST_RGB, &state.blendDstRgb);
    glGetIntegerv(GL_BLEND_SRC_ALPHA, &state.blendSrcAlpha);
    glGetIntegerv(GL_BLEND_DST_ALPHA, &state.blendDstAlpha);
    glGetIntegerv(GL_BLEND_EQUATION_RGB, &state.blendEquationRgb);
    glGetIntegerv(GL_BLEND_EQUATION_ALPHA, &state.blendEquationAlpha);
    state.blend = glIsEnabled(GL_BLEND);
    state.depth = glIsEnabled(GL_DEPTH_TEST);
    state.scissor = glIsEnabled(GL_SCISSOR_TEST);
    state.cull = glIsEnabled(GL_CULL_FACE);
    glGetBooleanv(GL_DEPTH_WRITEMASK, &state.depthMask);
    glGetBooleanv(GL_COLOR_WRITEMASK, state.colorMask);
    return state;
}

void setCapability(GLenum capability, GLboolean enabled) {
    if (enabled == GL_TRUE) {
        glEnable(capability);
    } else {
        glDisable(capability);
    }
}

void restoreState(const GlState& state) {
    glUseProgram(static_cast<GLuint>(state.program));
    glBindVertexArray(static_cast<GLuint>(state.vertexArray));
    glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(state.arrayBuffer));
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(state.framebuffer));
    glActiveTexture(static_cast<GLenum>(state.activeTexture));
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(state.texture2d));
    glViewport(state.viewport[0], state.viewport[1], state.viewport[2], state.viewport[3]);
    glScissor(state.scissorBox[0], state.scissorBox[1], state.scissorBox[2], state.scissorBox[3]);
    glBlendFuncSeparate(
            static_cast<GLenum>(state.blendSrcRgb),
            static_cast<GLenum>(state.blendDstRgb),
            static_cast<GLenum>(state.blendSrcAlpha),
            static_cast<GLenum>(state.blendDstAlpha));
    glBlendEquationSeparate(
            static_cast<GLenum>(state.blendEquationRgb),
            static_cast<GLenum>(state.blendEquationAlpha));
    setCapability(GL_BLEND, state.blend);
    setCapability(GL_DEPTH_TEST, state.depth);
    setCapability(GL_SCISSOR_TEST, state.scissor);
    setCapability(GL_CULL_FACE, state.cull);
    glDepthMask(state.depthMask);
    glColorMask(
            state.colorMask[0],
            state.colorMask[1],
            state.colorMask[2],
            state.colorMask[3]);
}

bool sameArray(const GLint* first, const GLint* second, int count) {
    for (int index = 0; index < count; ++index) {
        if (first[index] != second[index]) return false;
    }
    return true;
}

bool sameMask(const GLboolean* first, const GLboolean* second, int count) {
    for (int index = 0; index < count; ++index) {
        if (first[index] != second[index]) return false;
    }
    return true;
}

bool equivalent(const GlState& expected, const GlState& actual) {
    return expected.program == actual.program
            && expected.vertexArray == actual.vertexArray
            && expected.arrayBuffer == actual.arrayBuffer
            && expected.framebuffer == actual.framebuffer
            && expected.activeTexture == actual.activeTexture
            && expected.texture2d == actual.texture2d
            && sameArray(expected.viewport, actual.viewport, 4)
            && sameArray(expected.scissorBox, actual.scissorBox, 4)
            && expected.blendSrcRgb == actual.blendSrcRgb
            && expected.blendDstRgb == actual.blendDstRgb
            && expected.blendSrcAlpha == actual.blendSrcAlpha
            && expected.blendDstAlpha == actual.blendDstAlpha
            && expected.blendEquationRgb == actual.blendEquationRgb
            && expected.blendEquationAlpha == actual.blendEquationAlpha
            && expected.blend == actual.blend
            && expected.depth == actual.depth
            && expected.scissor == actual.scissor
            && expected.cull == actual.cull
            && expected.depthMask == actual.depthMask
            && sameMask(expected.colorMask, actual.colorMask, 4);
}

void destroyResources() {
    if (gSceneProgram != 0) glDeleteProgram(gSceneProgram);
    if (gOverlayProgram != 0) glDeleteProgram(gOverlayProgram);
    if (gSceneVbo != 0) glDeleteBuffers(1, &gSceneVbo);
    if (gOverlayVbo != 0) glDeleteBuffers(1, &gOverlayVbo);
    if (gSceneVao != 0) glDeleteVertexArrays(1, &gSceneVao);
    if (gOverlayVao != 0) glDeleteVertexArrays(1, &gOverlayVao);
    gSceneProgram = 0;
    gOverlayProgram = 0;
    gSceneVbo = 0;
    gOverlayVbo = 0;
    gSceneVao = 0;
    gOverlayVao = 0;
}

void createResources() {
    destroyResources();

    constexpr char sceneVertex[] = R"(
        #version 300 es
        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec3 aColor;
        out vec3 vColor;
        void main() {
            vColor = aColor;
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
    )";

    constexpr char sceneFragment[] = R"(
        #version 300 es
        precision mediump float;
        in vec3 vColor;
        out vec4 fragColor;
        void main() {
            fragColor = vec4(vColor, 1.0);
        }
    )";

    constexpr char overlayVertex[] = R"(
        #version 300 es
        layout(location = 0) in vec2 aPosition;
        uniform vec2 uOffset;
        uniform vec2 uScale;
        void main() {
            gl_Position = vec4(aPosition * uScale + uOffset, 0.0, 1.0);
        }
    )";

    constexpr char overlayFragment[] = R"(
        #version 300 es
        precision mediump float;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() {
            fragColor = uColor;
        }
    )";

    gSceneProgram = linkProgram(sceneVertex, sceneFragment);
    gOverlayProgram = linkProgram(overlayVertex, overlayFragment);
    if (gSceneProgram == 0 || gOverlayProgram == 0) {
        gInitialized = false;
        gInitStatus = "Hook simulation shader pipeline failed";
        return;
    }

    constexpr GLfloat sceneVertices[] = {
            -0.92f,  0.72f, 0.04f, 0.18f, 0.16f,
            -0.92f, -0.72f, 0.03f, 0.09f, 0.12f,
             0.92f, -0.72f, 0.05f, 0.14f, 0.20f,
            -0.92f,  0.72f, 0.04f, 0.18f, 0.16f,
             0.92f, -0.72f, 0.05f, 0.14f, 0.20f,
             0.92f,  0.72f, 0.08f, 0.24f, 0.18f,
             0.66f, -0.56f, 0.84f, 0.92f, 0.88f,
             0.84f, -0.56f, 0.84f, 0.92f, 0.88f,
             0.75f, -0.34f, 0.84f, 0.92f, 0.88f
    };

    constexpr GLfloat overlayVertices[] = {
            -1.0f,  1.0f,
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f, -1.0f,
             1.0f,  1.0f,
             0.0f,  0.75f,
            -0.58f, -0.42f,
             0.58f, -0.42f
    };

    glGenVertexArrays(1, &gSceneVao);
    glGenBuffers(1, &gSceneVbo);
    glBindVertexArray(gSceneVao);
    glBindBuffer(GL_ARRAY_BUFFER, gSceneVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(sceneVertices), sceneVertices, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(GLfloat), nullptr);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(
            1,
            3,
            GL_FLOAT,
            GL_FALSE,
            5 * sizeof(GLfloat),
            reinterpret_cast<void*>(2 * sizeof(GLfloat)));

    glGenVertexArrays(1, &gOverlayVao);
    glGenBuffers(1, &gOverlayVbo);
    glBindVertexArray(gOverlayVao);
    glBindBuffer(GL_ARRAY_BUFFER, gOverlayVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(overlayVertices), overlayVertices, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(GLfloat), nullptr);
    glBindVertexArray(0);

    gOverlayOffset = glGetUniformLocation(gOverlayProgram, "uOffset");
    gOverlayScale = glGetUniformLocation(gOverlayProgram, "uScale");
    gOverlayColor = glGetUniformLocation(gOverlayProgram, "uColor");

    gFrames = 0;
    gRestorePasses = 0;
    gRestoreFailures = 0;
    gLastRestoreOk = false;
    gInitialized = true;

    std::ostringstream status;
    status << "Hook Simulation Lab ONLINE\n"
           << "Renderer: " << glString(GL_RENDERER) << "\n"
           << "Host scene: READY\n"
           << "Overlay pass: READY\n"
           << "GL state capture: READY\n"
           << "GL state restore: READY\n"
           << "Post-restore sentinel: READY";
    gInitStatus = status.str();
    logInfo(gInitStatus);
}

void drawHostScene() {
    int inset = std::max(8, std::min(gWidth, gHeight) / 40);
    int viewportWidth = std::max(1, gWidth - inset * 2);
    int viewportHeight = std::max(1, gHeight - inset * 2);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(inset, inset, viewportWidth, viewportHeight);
    glEnable(GL_SCISSOR_TEST);
    glScissor(inset, inset, viewportWidth, viewportHeight);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDepthMask(GL_TRUE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(gSceneProgram);
    glBindVertexArray(gSceneVao);
    glBindBuffer(GL_ARRAY_BUFFER, gSceneVbo);
    glDrawArrays(GL_TRIANGLES, 0, 6);
}

void drawOverlay() {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, gWidth, gHeight);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(gOverlayProgram);
    glBindVertexArray(gOverlayVao);
    glBindBuffer(GL_ARRAY_BUFFER, gOverlayVbo);

    float normalizedX = (gTouchX / static_cast<float>(std::max(1, gWidth))) * 2.0f - 1.0f;
    float normalizedY = 1.0f - (gTouchY / static_cast<float>(std::max(1, gHeight))) * 2.0f;
    float offsetX = std::clamp(normalizedX * 0.42f, -0.55f, 0.55f);
    float offsetY = std::clamp(normalizedY * 0.42f, -0.48f, 0.48f);

    glUniform2f(gOverlayOffset, offsetX, offsetY);
    glUniform2f(gOverlayScale, 0.52f, 0.30f);
    glUniform4f(gOverlayColor, 0.02f, 0.05f, 0.04f, 0.88f);
    glDrawArrays(GL_TRIANGLES, 0, 6);

    glUniform2f(gOverlayScale, gPressed ? 0.17f : 0.14f, gPressed ? 0.17f : 0.14f);
    glUniform4f(gOverlayColor, 0.38f, 0.94f, 0.60f, 1.0f);
    glDrawArrays(GL_TRIANGLES, 6, 3);
}

void renderFrame() {
    glViewport(0, 0, gWidth, gHeight);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.012f, 0.018f, 0.017f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    if (!gInitialized) return;

    drawHostScene();
    GlState hostState = captureState();

    drawOverlay();
    restoreState(hostState);

    GlState restoredState = captureState();
    gLastRestoreOk = equivalent(hostState, restoredState);
    if (gLastRestoreOk) {
        ++gRestorePasses;
    } else {
        ++gRestoreFailures;
    }

    glDrawArrays(GL_TRIANGLES, 6, 3);
    ++gFrames;
}

std::string statusText() {
    std::ostringstream status;
    status << gInitStatus << "\n"
           << "Frames: " << gFrames << "\n"
           << "Last restore: " << (gLastRestoreOk ? "PASS" : "WAITING/FAIL") << "\n"
           << "Restore passes: " << gRestorePasses << "\n"
           << "Restore failures: " << gRestoreFailures << "\n"
           << "Sentinel meaning: visible white triangle = host state survived";
    return status.str();
}

}  // namespace

extern "C"
JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeHookLabCreate(JNIEnv*, jclass) {
    createResources();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeHookLabResize(
        JNIEnv*, jclass, jint width, jint height) {
    gWidth = std::max(1, static_cast<int>(width));
    gHeight = std::max(1, static_cast<int>(height));
    gTouchX = gWidth * 0.5f;
    gTouchY = gHeight * 0.5f;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeHookLabRender(JNIEnv*, jclass) {
    renderFrame();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeHookLabTouch(
        JNIEnv*, jclass, jfloat x, jfloat y, jboolean pressed) {
    gTouchX = x;
    gTouchY = y;
    gPressed = pressed == JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeHookLabStatus(JNIEnv* env, jclass) {
    return toJavaString(env, statusText());
}
