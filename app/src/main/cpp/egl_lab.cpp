#include <jni.h>
#include <android/log.h>
#include <GLES3/gl3.h>

#include <algorithm>
#include <sstream>
#include <string>

namespace {

constexpr char kTag[] = "MATH-EGL-LAB";

GLuint gProgram = 0;
GLuint gVao = 0;
GLuint gVbo = 0;
GLint gOffsetLocation = -1;
GLint gScaleLocation = -1;
GLint gColorLocation = -1;
int gWidth = 1;
int gHeight = 1;
float gTouchX = 0.0f;
float gTouchY = 0.0f;
bool gPressed = false;
std::string gStatus = "EGL laboratory not initialized";

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

GLuint createProgram() {
    constexpr char vertexSource[] = R"(
        #version 300 es
        layout(location = 0) in vec2 aPosition;
        uniform vec2 uOffset;
        uniform float uScale;
        void main() {
            gl_Position = vec4(aPosition * uScale + uOffset, 0.0, 1.0);
        }
    )";

    constexpr char fragmentSource[] = R"(
        #version 300 es
        precision mediump float;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() {
            fragColor = uColor;
        }
    )";

    GLuint vertex = compileShader(GL_VERTEX_SHADER, vertexSource);
    GLuint fragment = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
    if (vertex == 0 || fragment == 0) {
        glDeleteShader(vertex);
        glDeleteShader(fragment);
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

void createRenderer() {
    if (gProgram != 0) {
        glDeleteProgram(gProgram);
        glDeleteBuffers(1, &gVbo);
        glDeleteVertexArrays(1, &gVao);
    }

    gProgram = createProgram();
    if (gProgram == 0) {
        gStatus = "GLES shader pipeline failed";
        return;
    }

    constexpr GLfloat vertices[] = {
            0.0f,  0.46f,
           -0.40f, -0.30f,
            0.40f, -0.30f
    };

    glGenVertexArrays(1, &gVao);
    glGenBuffers(1, &gVbo);
    glBindVertexArray(gVao);
    glBindBuffer(GL_ARRAY_BUFFER, gVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(GLfloat), nullptr);
    glBindVertexArray(0);

    gOffsetLocation = glGetUniformLocation(gProgram, "uOffset");
    gScaleLocation = glGetUniformLocation(gProgram, "uScale");
    gColorLocation = glGetUniformLocation(gProgram, "uColor");

    std::ostringstream status;
    status << "EGL / OpenGL ES laboratory ONLINE\n"
           << "Vendor: " << glString(GL_VENDOR) << "\n"
           << "Renderer: " << glString(GL_RENDERER) << "\n"
           << "Version: " << glString(GL_VERSION) << "\n"
           << "GLSL: " << glString(GL_SHADING_LANGUAGE_VERSION) << "\n"
           << "Shader pipeline: LINKED\n"
           << "Touch bridge: READY";
    gStatus = status.str();
    logInfo(gStatus);
}

void renderFrame() {
    glViewport(0, 0, gWidth, gHeight);
    glClearColor(0.035f, 0.043f, 0.039f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    if (gProgram == 0) {
        return;
    }

    float normalizedX = (gTouchX / static_cast<float>(std::max(1, gWidth))) * 2.0f - 1.0f;
    float normalizedY = 1.0f - (gTouchY / static_cast<float>(std::max(1, gHeight))) * 2.0f;

    glUseProgram(gProgram);
    glUniform2f(gOffsetLocation, normalizedX * 0.55f, normalizedY * 0.55f);
    glUniform1f(gScaleLocation, gPressed ? 0.82f : 0.68f);
    if (gPressed) {
        glUniform4f(gColorLocation, 0.38f, 0.94f, 0.60f, 1.0f);
    } else {
        glUniform4f(gColorLocation, 0.18f, 0.56f, 0.34f, 1.0f);
    }

    glBindVertexArray(gVao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
}

}  // namespace

extern "C"
JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeEglLabCreate(JNIEnv*, jclass) {
    createRenderer();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeEglLabResize(
        JNIEnv*, jclass, jint width, jint height) {
    gWidth = std::max(1, static_cast<int>(width));
    gHeight = std::max(1, static_cast<int>(height));
}

extern "C"
JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeEglLabRender(JNIEnv*, jclass) {
    renderFrame();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeEglLabTouch(
        JNIEnv*, jclass, jfloat x, jfloat y, jboolean pressed) {
    gTouchX = x;
    gTouchY = y;
    gPressed = pressed == JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeEglLabStatus(JNIEnv* env, jclass) {
    return toJavaString(env, gStatus);
}
