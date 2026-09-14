#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <unistd.h>
#include <cstdio>
#include <string>
#include <exception>

namespace {
struct EglFrame {
    ANativeWindow* window = nullptr;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    ~EglFrame() {
        if (display != EGL_NO_DISPLAY) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            eglTerminate(display);
        }
        if (window) ANativeWindow_release(window);
    }
};
std::string error(const char* step) {
    char text[160];
    std::snprintf(text, sizeof(text), "PROBE_ERROR step=%s egl=0x%x", step, eglGetError());
    return text;
}
std::string paint(JNIEnv* env, jobject surfaceObject, jint width, jint height) {
    if (!surfaceObject || width <= 0 || height <= 0) return "PROBE_ERROR invalid_surface_or_dimensions";
    EglFrame f;
    f.window = ANativeWindow_fromSurface(env, surfaceObject);
    if (!f.window || env->ExceptionCheck()) return "PROBE_ERROR native_window";
    f.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (f.display == EGL_NO_DISPLAY || !eglInitialize(f.display, nullptr, nullptr)) return error("initialize");
    if (!eglBindAPI(EGL_OPENGL_ES_API)) return error("bind_api");
    const EGLint configAttributes[] = {EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig config; EGLint count = 0;
    if (!eglChooseConfig(f.display, configAttributes, &config, 1, &count) || count != 1) return error("config");
    EGLint format = 0;
    if (!eglGetConfigAttrib(f.display, config, EGL_NATIVE_VISUAL_ID, &format)) return error("format");
    if (ANativeWindow_setBuffersGeometry(f.window, 0, 0, format) != 0) return "PROBE_ERROR buffer_geometry";
    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    f.context = eglCreateContext(f.display, config, EGL_NO_CONTEXT, contextAttributes);
    if (f.context == EGL_NO_CONTEXT) return error("context");
    f.surface = eglCreateWindowSurface(f.display, config, f.window, nullptr);
    if (f.surface == EGL_NO_SURFACE) return error("surface");
    if (!eglMakeCurrent(f.display, f.surface, f.surface, f.context)) return error("make_current");
    EGLint w = 0, h = 0;
    if (!eglQuerySurface(f.display, f.surface, EGL_WIDTH, &w)
            || !eglQuerySurface(f.display, f.surface, EGL_HEIGHT, &h) || w <= 0 || h <= 0)
        return error("surface_size");
    glViewport(0, 0, w, h);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.08f, 0.15f, 0.24f, 1.0f); glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_SCISSOR_TEST); glScissor(0, 0, w / 2, h);
    glClearColor(0.13f, 0.65f, 0.52f, 1.0f); glClear(GL_COLOR_BUFFER_BIT);
    glDisable(GL_SCISSOR_TEST);
    GLenum glError = glGetError();
    if (glError != GL_NO_ERROR) return "PROBE_ERROR gl=" + std::to_string(glError);
    const GLubyte* renderer = glGetString(GL_RENDERER);
    std::string device = renderer ? reinterpret_cast<const char*>(renderer) : "unknown";
    if (!eglSwapBuffers(f.display, f.surface)) return error("swap_buffers");
    return "PROBE_SWAP_OK " + std::to_string(w) + "x" + std::to_string(h)
        + " renderer=" + device + " minecraft_loaded=false";
}
jstring describe(JNIEnv* env, jclass) {
    std::string result = "RegisterNatives=OK pid=" + std::to_string(getpid()) + " minecraft_loaded=false";
    return env->NewStringUTF(result.c_str());
}
jstring draw(JNIEnv* env, jclass, jobject surface, jint width, jint height) {
    try {
        std::string result = paint(env, surface, width, height);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception&) { return env->NewStringUTF("PROBE_ERROR native_exception"); }
}
JNINativeMethod methods[] = {
    {const_cast<char*>("describe"), const_cast<char*>("()Ljava/lang/String;"), reinterpret_cast<void*>(describe)},
    {const_cast<char*>("draw"), const_cast<char*>("(Landroid/view/Surface;II)Ljava/lang/String;"), reinterpret_cast<void*>(draw)}
};
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass owner = env->FindClass("com/balzikz/mathclient/foundation/NativeProbe");
    if (!owner) return JNI_ERR;
    jint registered = env->RegisterNatives(owner, methods, sizeof(methods) / sizeof(methods[0]));
    env->DeleteLocalRef(owner);
    if (registered != JNI_OK) return JNI_ERR;
    __android_log_print(ANDROID_LOG_INFO, "MathFoundation", "PROBE_JNI_ONLOAD_OK pid=%d", getpid());
    return JNI_VERSION_1_6;
}
