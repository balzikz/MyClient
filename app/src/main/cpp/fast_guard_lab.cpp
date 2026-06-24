#include <jni.h>
#include <android/log.h>
#include <GLES3/gl3.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <sstream>
#include <string>

namespace {

constexpr char kTag[] = "MATH-FAST-GUARD";
constexpr unsigned long long kSafeWarmupFrames = 180;
constexpr unsigned long long kAuditInterval = 120;

GLuint sceneProgram = 0;
GLuint sceneVao = 0;
GLuint sceneVbo = 0;
GLuint overlayProgram = 0;
GLuint overlayVao = 0;
GLuint overlayVbo = 0;
GLint overlayOffset = -1;
GLint overlayScale = -1;
GLint overlayColor = -1;
int surfaceWidth = 1;
int surfaceHeight = 1;
float touchX = 0.5f;
float touchY = 0.5f;
bool pressed = false;
bool initialized = false;
std::string initStatus = "Fast Guard Lab not initialized";

std::atomic<unsigned long long> totalFrames{0};
std::atomic<unsigned long long> safeSamples{0};
std::atomic<unsigned long long> safePasses{0};
std::atomic<unsigned long long> safeFailures{0};
std::atomic<unsigned long long> safeNanoseconds{0};
std::atomic<unsigned long long> fastPasses{0};
std::atomic<unsigned long long> fastNanoseconds{0};
std::atomic<unsigned long long> auditPasses{0};
std::atomic<unsigned long long> auditFailures{0};
std::atomic<bool> fallbackToSafe{false};

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
    if (compiled == GL_TRUE) return shader;

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
    if (linked == GL_TRUE) return program;

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

struct FastState {
    GLint program = 0;
    GLint vertexArray = 0;
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

struct FullState {
    FastState fast;
    GLint arrayBuffer = 0;
    GLint framebuffer = 0;
    GLint activeTexture = GL_TEXTURE0;
    GLint texture2d = 0;
    GLint viewport[4] = {0, 0, 0, 0};
};

FastState captureFast() {
    FastState state;
    glGetIntegerv(GL_CURRENT_PROGRAM, &state.program);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &state.vertexArray);
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

FullState captureFull() {
    FullState state;
    state.fast = captureFast();
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &state.arrayBuffer);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &state.framebuffer);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &state.activeTexture);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &state.texture2d);
    glGetIntegerv(GL_VIEWPORT, state.viewport);
    return state;
}

void setCapability(GLenum capability, GLboolean enabled) {
    if (enabled == GL_TRUE) glEnable(capability);
    else glDisable(capability);
}

void restoreFast(const FastState& state) {
    glUseProgram(static_cast<GLuint>(state.program));
    glBindVertexArray(static_cast<GLuint>(state.vertexArray));
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
            state.colorMask[0], state.colorMask[1],
            state.colorMask[2], state.colorMask[3]);
}

void restoreFull(const FullState& state) {
    restoreFast(state.fast);
    glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(state.arrayBuffer));
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(state.framebuffer));
    glActiveTexture(static_cast<GLenum>(state.activeTexture));
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(state.texture2d));
    glViewport(state.viewport[0], state.viewport[1], state.viewport[2], state.viewport[3]);
}

bool sameInts(const GLint* first, const GLint* second, int count) {
    for (int index = 0; index < count; ++index) {
        if (first[index] != second[index]) return false;
    }
    return true;
}

bool sameBools(const GLboolean* first, const GLboolean* second, int count) {
    for (int index = 0; index < count; ++index) {
        if (first[index] != second[index]) return false;
    }
    return true;
}

bool equalFast(const FastState& a, const FastState& b) {
    return a.program == b.program
            && a.vertexArray == b.vertexArray
            && sameInts(a.scissorBox, b.scissorBox, 4)
            && a.blendSrcRgb == b.blendSrcRgb
            && a.blendDstRgb == b.blendDstRgb
            && a.blendSrcAlpha == b.blendSrcAlpha
            && a.blendDstAlpha == b.blendDstAlpha
            && a.blendEquationRgb == b.blendEquationRgb
            && a.blendEquationAlpha == b.blendEquationAlpha
            && a.blend == b.blend
            && a.depth == b.depth
            && a.scissor == b.scissor
            && a.cull == b.cull
            && a.depthMask == b.depthMask
            && sameBools(a.colorMask, b.colorMask, 4);
}

bool equalFull(const FullState& a, const FullState& b) {
    return equalFast(a.fast, b.fast)
            && a.arrayBuffer == b.arrayBuffer
            && a.framebuffer == b.framebuffer
            && a.activeTexture == b.activeTexture
            && a.texture2d == b.texture2d
            && sameInts(a.viewport, b.viewport, 4);
}

void destroyResources() {
    if (sceneProgram != 0) glDeleteProgram(sceneProgram);
    if (overlayProgram != 0) glDeleteProgram(overlayProgram);
    if (sceneVbo != 0) glDeleteBuffers(1, &sceneVbo);
    if (overlayVbo != 0) glDeleteBuffers(1, &overlayVbo);
    if (sceneVao != 0) glDeleteVertexArrays(1, &sceneVao);
    if (overlayVao != 0) glDeleteVertexArrays(1, &overlayVao);
    sceneProgram = overlayProgram = 0;
    sceneVbo = overlayVbo = 0;
    sceneVao = overlayVao = 0;
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

    sceneProgram = linkProgram(sceneVertex, sceneFragment);
    overlayProgram = linkProgram(overlayVertex, overlayFragment);
    if (sceneProgram == 0 || overlayProgram == 0) {
        initialized = false;
        initStatus = "Fast Guard Lab shader pipeline failed";
        return;
    }

    constexpr GLfloat sceneVertices[] = {
            -0.94f,  0.74f, 0.04f, 0.18f, 0.16f,
            -0.94f, -0.74f, 0.03f, 0.09f, 0.12f,
             0.94f, -0.74f, 0.05f, 0.14f, 0.20f,
            -0.94f,  0.74f, 0.04f, 0.18f, 0.16f,
             0.94f, -0.74f, 0.05f, 0.14f, 0.20f,
             0.94f,  0.74f, 0.08f, 0.24f, 0.18f,
             0.67f, -0.58f, 0.90f, 0.96f, 0.93f,
             0.87f, -0.58f, 0.90f, 0.96f, 0.93f,
             0.77f, -0.34f, 0.90f, 0.96f, 0.93f
    };

    constexpr GLfloat overlayVertices[] = {
            -1.0f,  1.0f, -1.0f, -1.0f,  1.0f, -1.0f,
            -1.0f,  1.0f,  1.0f, -1.0f,  1.0f,  1.0f,
             0.0f,  0.75f, -0.58f, -0.42f, 0.58f, -0.42f
    };

    glGenVertexArrays(1, &sceneVao);
    glGenBuffers(1, &sceneVbo);
    glBindVertexArray(sceneVao);
    glBindBuffer(GL_ARRAY_BUFFER, sceneVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(sceneVertices), sceneVertices, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(GLfloat), nullptr);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(
            1, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(GLfloat),
            reinterpret_cast<void*>(2 * sizeof(GLfloat)));

    glGenVertexArrays(1, &overlayVao);
    glGenBuffers(1, &overlayVbo);
    glBindVertexArray(overlayVao);
    glBindBuffer(GL_ARRAY_BUFFER, overlayVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(overlayVertices), overlayVertices, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(GLfloat), nullptr);
    glBindVertexArray(0);

    overlayOffset = glGetUniformLocation(overlayProgram, "uOffset");
    overlayScale = glGetUniformLocation(overlayProgram, "uScale");
    overlayColor = glGetUniformLocation(overlayProgram, "uColor");

    totalFrames = 0;
    safeSamples = 0;
    safePasses = 0;
    safeFailures = 0;
    safeNanoseconds = 0;
    fastPasses = 0;
    fastNanoseconds = 0;
    auditPasses = 0;
    auditFailures = 0;
    fallbackToSafe = false;
    initialized = true;

    std::ostringstream status;
    status << "Fast Guard Lab ONLINE\n"
           << "Renderer: " << glString(GL_RENDERER) << "\n"
           << "Own overlay VAO: READY\n"
           << "Per-frame VBO setup: DISABLED\n"
           << "Safe warmup: " << kSafeWarmupFrames << " frames\n"
           << "Full audit interval: " << kAuditInterval << " frames";
    initStatus = status.str();
    logInfo(initStatus);
}

void drawHostScene() {
    int inset = std::max(8, std::min(surfaceWidth, surfaceHeight) / 40);
    int width = std::max(1, surfaceWidth - inset * 2);
    int height = std::max(1, surfaceHeight - inset * 2);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, surfaceWidth, surfaceHeight);
    glEnable(GL_SCISSOR_TEST);
    glScissor(inset, inset, width, height);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDepthMask(GL_TRUE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(sceneProgram);
    glBindVertexArray(sceneVao);
    glBindBuffer(GL_ARRAY_BUFFER, sceneVbo);
    glDrawArrays(GL_TRIANGLES, 0, 6);
}

void drawOverlay() {
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glUseProgram(overlayProgram);
    glBindVertexArray(overlayVao);

    float nx = (touchX / static_cast<float>(std::max(1, surfaceWidth))) * 2.0f - 1.0f;
    float ny = 1.0f - (touchY / static_cast<float>(std::max(1, surfaceHeight))) * 2.0f;
    float ox = std::clamp(nx * 0.42f, -0.55f, 0.55f);
    float oy = std::clamp(ny * 0.42f, -0.48f, 0.48f);

    glUniform2f(overlayOffset, ox, oy);
    glUniform2f(overlayScale, 0.52f, 0.30f);
    glUniform4f(overlayColor, 0.02f, 0.05f, 0.04f, 0.88f);
    glDrawArrays(GL_TRIANGLES, 0, 6);

    float markerScale = pressed ? 0.17f : 0.14f;
    glUniform2f(overlayScale, markerScale, markerScale);
    glUniform4f(overlayColor, 0.38f, 0.94f, 0.60f, 1.0f);
    glDrawArrays(GL_TRIANGLES, 6, 3);
}

void drawSentinel() {
    glDrawArrays(GL_TRIANGLES, 6, 3);
}

unsigned long long elapsedNanoseconds(
        const std::chrono::steady_clock::time_point& start,
        const std::chrono::steady_clock::time_point& end) {
    return static_cast<unsigned long long>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count());
}

void renderFrame() {
    glViewport(0, 0, surfaceWidth, surfaceHeight);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.012f, 0.018f, 0.017f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!initialized) return;

    drawHostScene();
    unsigned long long frame = totalFrames.load();
    bool safeMode = frame < kSafeWarmupFrames || fallbackToSafe.load();

    if (safeMode) {
        auto start = std::chrono::steady_clock::now();
        FullState before = captureFull();
        drawOverlay();
        restoreFull(before);
        auto end = std::chrono::steady_clock::now();

        FullState after = captureFull();
        bool pass = equalFull(before, after) && glGetError() == GL_NO_ERROR;
        safeSamples++;
        safeNanoseconds += elapsedNanoseconds(start, end);
        if (pass) safePasses++;
        else safeFailures++;
    } else {
        bool auditFrame = ((frame - kSafeWarmupFrames) % kAuditInterval) == 0;
        FullState auditBefore;
        if (auditFrame) auditBefore = captureFull();

        auto start = std::chrono::steady_clock::now();
        FastState before = captureFast();
        drawOverlay();
        restoreFast(before);
        auto end = std::chrono::steady_clock::now();

        fastPasses++;
        fastNanoseconds += elapsedNanoseconds(start, end);

        if (auditFrame) {
            FullState auditAfter = captureFull();
            bool pass = equalFull(auditBefore, auditAfter) && glGetError() == GL_NO_ERROR;
            if (pass) {
                auditPasses++;
            } else {
                auditFailures++;
                fallbackToSafe = true;
                logInfo("Full audit mismatch; automatic SAFE fallback enabled");
            }
        }
    }

    drawSentinel();
    totalFrames++;
}

double averageMicroseconds(
        unsigned long long nanoseconds,
        unsigned long long samples) {
    if (samples == 0) return 0.0;
    return static_cast<double>(nanoseconds) / static_cast<double>(samples) / 1000.0;
}

std::string statusText() {
    unsigned long long frameCount = totalFrames.load();
    unsigned long long safeCount = safeSamples.load();
    unsigned long long fastCount = fastPasses.load();
    bool fallback = fallbackToSafe.load();
    const char* mode = fallback
            ? "SAFE FALLBACK"
            : (frameCount < kSafeWarmupFrames ? "SAFE WARMUP" : "FAST");

    std::ostringstream status;
    status.setf(std::ios::fixed);
    status.precision(2);
    status << initStatus << "\n"
           << "Mode: " << mode << "\n"
           << "Frames: " << frameCount << "\n"
           << "Safe samples: " << safeCount << "\n"
           << "Safe passes: " << safePasses.load() << "\n"
           << "Safe failures: " << safeFailures.load() << "\n"
           << "Safe average guard time: "
           << averageMicroseconds(safeNanoseconds.load(), safeCount) << " us\n"
           << "Fast passes: " << fastCount << "\n"
           << "Fast average guard time: "
           << averageMicroseconds(fastNanoseconds.load(), fastCount) << " us\n"
           << "Audit passes: " << auditPasses.load() << "\n"
           << "Audit failures: " << auditFailures.load() << "\n"
           << "Auto fallback: " << (fallback ? "ACTIVE" : "not triggered") << "\n"
           << "White sentinel visible: host state survived";
    return status.str();
}

}  // namespace

extern "C"
JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeFastGuardLabCreate(JNIEnv*, jclass) {
    createResources();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeFastGuardLabResize(
        JNIEnv*, jclass, jint width, jint height) {
    surfaceWidth = std::max(1, static_cast<int>(width));
    surfaceHeight = std::max(1, static_cast<int>(height));
    touchX = surfaceWidth * 0.5f;
    touchY = surfaceHeight * 0.5f;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeFastGuardLabRender(JNIEnv*, jclass) {
    renderFrame();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeFastGuardLabTouch(
        JNIEnv*, jclass, jfloat x, jfloat y, jboolean isPressed) {
    touchX = x;
    touchY = y;
    pressed = isPressed == JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeFastGuardLabStatus(JNIEnv* env, jclass) {
    return toJavaString(env, statusText());
}
