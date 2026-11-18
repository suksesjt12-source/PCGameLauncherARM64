#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <mutex>
#include <cmath>
#include <vulkan/vulkan.h>
#include <sstream>
#include <string>
#include <vector>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_suksesjt12_pcgamelauncher_NativeBridge_runtimeStatus(JNIEnv* env, jobject) {
    constexpr const char* status =
        "Native ARM64 bridge loaded. Box64 is packaged in the APK; Wine runtime is imported separately.";
    return env->NewStringUTF(status);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_suksesjt12_pcgamelauncher_NativeBridge_deviceInfo(JNIEnv* env, jobject) {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "PCGameLauncherARM64";
    appInfo.applicationVersion = VK_MAKE_VERSION(0, 5, 0);
    appInfo.pEngineName = "LauncherVulkanProbe";
    appInfo.engineVersion = VK_MAKE_VERSION(0, 5, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    const VkResult createResult = vkCreateInstance(&createInfo, nullptr, &instance);
    if (createResult != VK_SUCCESS) {
        return env->NewStringUTF("Vulkan instance creation failed. Check Android Vulkan driver support.");
    }

    uint32_t count = 0;
    VkResult result = vkEnumeratePhysicalDevices(instance, &count, nullptr);
    if (result != VK_SUCCESS || count == 0) {
        vkDestroyInstance(instance, nullptr);
        return env->NewStringUTF("No Vulkan physical device detected.");
    }
    std::vector<VkPhysicalDevice> devices(count);
    result = vkEnumeratePhysicalDevices(instance, &count, devices.data());
    if (result != VK_SUCCESS || devices.empty()) {
        vkDestroyInstance(instance, nullptr);
        return env->NewStringUTF("Vulkan physical-device enumeration failed.");
    }

    std::ostringstream output;
    output << "Vulkan device(s): " << count;
    for (uint32_t i = 0; i < count; ++i) {
        VkPhysicalDevice device = devices[i];
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(device, &properties);
        uint32_t queueCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, nullptr);
        std::vector<VkQueueFamilyProperties> queues(queueCount);
        if (queueCount > 0) vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, queues.data());

        bool graphicsQueue = false;
        bool computeQueue = false;
        for (const auto& queue : queues) {
            if (queue.queueCount == 0) continue;
            graphicsQueue = graphicsQueue || (queue.queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0;
            computeQueue = computeQueue || (queue.queueFlags & VK_QUEUE_COMPUTE_BIT) != 0;
        }

        VkPhysicalDeviceMemoryProperties memory{};
        vkGetPhysicalDeviceMemoryProperties(device, &memory);
        VkDeviceSize largestHeap = 0;
        for (uint32_t heap = 0; heap < memory.memoryHeapCount; ++heap) {
            if ((memory.memoryHeaps[heap].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0 &&
                memory.memoryHeaps[heap].size > largestHeap) {
                largestHeap = memory.memoryHeaps[heap].size;
            }
        }

        output << "\n" << (i + 1) << ". " << properties.deviceName
               << "\n   API: " << VK_VERSION_MAJOR(properties.apiVersion)
               << "." << VK_VERSION_MINOR(properties.apiVersion)
               << "." << VK_VERSION_PATCH(properties.apiVersion)
               << " | Driver: " << properties.driverVersion
               << "\n   Device type: " << static_cast<int>(properties.deviceType)
               << " | Graphics queue: " << (graphicsQueue ? "yes" : "no")
               << " | Compute queue: " << (computeQueue ? "yes" : "no");
        if (largestHeap > 0) {
            output << "\n   Largest device-local heap: " << (largestHeap / (1024ULL * 1024ULL)) << " MiB";
        }
    }
    output << "\nVulkan device detection is only a capability probe; it does not prove DXVK or a game will run.";
    vkDestroyInstance(instance, nullptr);
    const std::string info = output.str();
    return env->NewStringUTF(info.c_str());
}


namespace {
struct PcglEglSession {
    ANativeWindow* window = nullptr;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;
    EGLint width = 0;
    EGLint height = 0;
    GLuint program = 0;
    GLint positionAttribute = -1;
    GLint colorAttribute = -1;
    bool ready = false;
    std::string info = "PCGL EGL session idle.";
};
std::mutex g_pcglEglMutex;
PcglEglSession g_pcglEgl;

GLuint pcglCompileShader(GLenum type, const char* source, std::string& error) {
    const GLuint shader = glCreateShader(type);
    if (shader == 0) {
        error = "glCreateShader failed.";
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        GLint length = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
        std::string info(static_cast<size_t>(length > 1 ? length : 1), '\0');
        glGetShaderInfoLog(shader, length, nullptr, info.data());
        error = "Shader compile failed: " + info;
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool pcglCreateDiagnosticProgram(std::string& error) {
    static const char* vertexSource =
        "attribute vec2 aPosition;\\n"
        "attribute vec3 aColor;\\n"
        "varying vec3 vColor;\\n"
        "void main(){ gl_Position=vec4(aPosition,0.0,1.0); vColor=aColor; }\\n";
    static const char* fragmentSource =
        "precision mediump float;\\n"
        "varying vec3 vColor;\\n"
        "void main(){ gl_FragColor=vec4(vColor,1.0); }\\n";

    const GLuint vertex = pcglCompileShader(GL_VERTEX_SHADER, vertexSource, error);
    if (vertex == 0) return false;
    const GLuint fragment = pcglCompileShader(GL_FRAGMENT_SHADER, fragmentSource, error);
    if (fragment == 0) {
        glDeleteShader(vertex);
        return false;
    }

    const GLuint program = glCreateProgram();
    if (program == 0) {
        error = "glCreateProgram failed.";
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        return false;
    }
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);

    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        GLint length = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &length);
        std::string info(static_cast<size_t>(length > 1 ? length : 1), '\0');
        glGetProgramInfoLog(program, length, nullptr, info.data());
        error = "Shader link failed: " + info;
        glDeleteProgram(program);
        return false;
    }

    g_pcglEgl.positionAttribute = glGetAttribLocation(program, "aPosition");
    g_pcglEgl.colorAttribute = glGetAttribLocation(program, "aColor");
    if (g_pcglEgl.positionAttribute < 0 || g_pcglEgl.colorAttribute < 0) {
        error = "Diagnostic shader attributes are unavailable.";
        glDeleteProgram(program);
        return false;
    }
    g_pcglEgl.program = program;
    return true;
}

std::string pcglEglError(const char* operation) {
    std::ostringstream out;
    out << operation << " failed (EGL error 0x" << std::hex << eglGetError() << ").";
    return out.str();
}
void pcglDestroyEglLocked() {
    if (g_pcglEgl.display != EGL_NO_DISPLAY) {
        if (g_pcglEgl.context != EGL_NO_CONTEXT && g_pcglEgl.surface != EGL_NO_SURFACE) {
            eglMakeCurrent(g_pcglEgl.display, g_pcglEgl.surface, g_pcglEgl.surface, g_pcglEgl.context);
            if (g_pcglEgl.program != 0) glDeleteProgram(g_pcglEgl.program);
        }
        eglMakeCurrent(g_pcglEgl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_pcglEgl.surface != EGL_NO_SURFACE) eglDestroySurface(g_pcglEgl.display, g_pcglEgl.surface);
        if (g_pcglEgl.context != EGL_NO_CONTEXT) eglDestroyContext(g_pcglEgl.display, g_pcglEgl.context);
        eglTerminate(g_pcglEgl.display);
    }
    if (g_pcglEgl.window != nullptr) ANativeWindow_release(g_pcglEgl.window);
    g_pcglEgl = PcglEglSession{};
}
jstring pcglNewString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_suksesjt12_pcgamelauncher_NativeBridge_createDisplaySurface(
    JNIEnv* env, jobject, jobject javaSurface, jint requestedWidth, jint requestedHeight) {
    std::lock_guard<std::mutex> guard(g_pcglEglMutex);
    pcglDestroyEglLocked();
    if (javaSurface == nullptr) {
        g_pcglEgl.info = "ERROR: Android Surface is null.";
        return pcglNewString(env, g_pcglEgl.info);
    }
    g_pcglEgl.window = ANativeWindow_fromSurface(env, javaSurface);
    if (g_pcglEgl.window == nullptr) {
        g_pcglEgl.info = "ERROR: ANativeWindow_fromSurface returned null.";
        return pcglNewString(env, g_pcglEgl.info);
    }
    g_pcglEgl.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_pcglEgl.display == EGL_NO_DISPLAY) {
        const std::string error = pcglEglError("eglGetDisplay");
        pcglDestroyEglLocked(); g_pcglEgl.info = "ERROR: " + error;
        return pcglNewString(env, g_pcglEgl.info);
    }
    EGLint major = 0, minor = 0;
    if (eglInitialize(g_pcglEgl.display, &major, &minor) != EGL_TRUE) {
        const std::string error = pcglEglError("eglInitialize");
        pcglDestroyEglLocked(); g_pcglEgl.info = "ERROR: " + error;
        return pcglNewString(env, g_pcglEgl.info);
    }
    const EGLint configAttributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE
    };
    EGLConfig config = nullptr;
    EGLint configCount = 0;
    if (eglChooseConfig(g_pcglEgl.display, configAttributes, &config, 1, &configCount) != EGL_TRUE || configCount < 1) {
        const std::string error = pcglEglError("eglChooseConfig");
        pcglDestroyEglLocked(); g_pcglEgl.info = "ERROR: " + error;
        return pcglNewString(env, g_pcglEgl.info);
    }
    EGLint nativeFormat = 0;
    eglGetConfigAttrib(g_pcglEgl.display, config, EGL_NATIVE_VISUAL_ID, &nativeFormat);
    ANativeWindow_setBuffersGeometry(g_pcglEgl.window, 0, 0, nativeFormat);
    g_pcglEgl.surface = eglCreateWindowSurface(g_pcglEgl.display, config, g_pcglEgl.window, nullptr);
    if (g_pcglEgl.surface == EGL_NO_SURFACE) {
        const std::string error = pcglEglError("eglCreateWindowSurface");
        pcglDestroyEglLocked(); g_pcglEgl.info = "ERROR: " + error;
        return pcglNewString(env, g_pcglEgl.info);
    }
    const EGLint contextAttributes[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    g_pcglEgl.context = eglCreateContext(g_pcglEgl.display, config, EGL_NO_CONTEXT, contextAttributes);
    if (g_pcglEgl.context == EGL_NO_CONTEXT) {
        const std::string error = pcglEglError("eglCreateContext");
        pcglDestroyEglLocked(); g_pcglEgl.info = "ERROR: " + error;
        return pcglNewString(env, g_pcglEgl.info);
    }
    if (eglMakeCurrent(g_pcglEgl.display, g_pcglEgl.surface, g_pcglEgl.surface, g_pcglEgl.context) != EGL_TRUE) {
        const std::string error = pcglEglError("eglMakeCurrent");
        pcglDestroyEglLocked(); g_pcglEgl.info = "ERROR: " + error;
        return pcglNewString(env, g_pcglEgl.info);
    }
    std::string shaderError;
    if (!pcglCreateDiagnosticProgram(shaderError)) {
        pcglDestroyEglLocked();
        g_pcglEgl.info = "ERROR: " + shaderError;
        return pcglNewString(env, g_pcglEgl.info);
    }
    eglQuerySurface(g_pcglEgl.display, g_pcglEgl.surface, EGL_WIDTH, &g_pcglEgl.width);
    eglQuerySurface(g_pcglEgl.display, g_pcglEgl.surface, EGL_HEIGHT, &g_pcglEgl.height);
    if (g_pcglEgl.width < 1) g_pcglEgl.width = requestedWidth;
    if (g_pcglEgl.height < 1) g_pcglEgl.height = requestedHeight;
    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    std::ostringstream info;
    info << "READY: EGL " << major << "." << minor << " | OpenGL ES "
         << (version ? version : "unknown") << " | Renderer: "
         << (renderer ? renderer : "unknown") << " | Surface: "
         << g_pcglEgl.width << "x" << g_pcglEgl.height;
    g_pcglEgl.info = info.str(); g_pcglEgl.ready = true;
    return pcglNewString(env, g_pcglEgl.info);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_suksesjt12_pcgamelauncher_NativeBridge_resizeDisplaySurface(JNIEnv*, jobject, jint width, jint height) {
    std::lock_guard<std::mutex> guard(g_pcglEglMutex);
    if (!g_pcglEgl.ready || width < 1 || height < 1) return JNI_FALSE;
    if (eglMakeCurrent(g_pcglEgl.display, g_pcglEgl.surface, g_pcglEgl.surface, g_pcglEgl.context) != EGL_TRUE) {
        g_pcglEgl.info = pcglEglError("eglMakeCurrent during resize"); return JNI_FALSE;
    }
    g_pcglEgl.width = width; g_pcglEgl.height = height;
    glViewport(0, 0, width, height);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_suksesjt12_pcgamelauncher_NativeBridge_renderDisplayFrame(JNIEnv*, jobject, jlong frameIndex) {
    std::lock_guard<std::mutex> guard(g_pcglEglMutex);
    if (!g_pcglEgl.ready) return JNI_FALSE;
    if (eglMakeCurrent(g_pcglEgl.display, g_pcglEgl.surface, g_pcglEgl.surface, g_pcglEgl.context) != EGL_TRUE) {
        g_pcglEgl.info = pcglEglError("eglMakeCurrent during frame"); return JNI_FALSE;
    }
    const float phase = static_cast<float>(frameIndex % 240) / 240.0f;
    glViewport(0, 0, g_pcglEgl.width, g_pcglEgl.height);
    glClearColor(0.025f + phase * 0.055f, 0.045f, 0.085f + (1.0f - phase) * 0.07f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    const float angle = phase * 6.28318530718f;
    const float c = std::cos(angle);
    const float s = std::sin(angle);
    const float source[] = {
         0.00f,  0.60f, 0.20f, 0.80f, 1.00f,
        -0.56f, -0.42f, 1.00f, 0.45f, 0.18f,
         0.56f, -0.42f, 0.30f, 1.00f, 0.52f
    };
    float vertices[15];
    for (int i = 0; i < 3; ++i) {
        const float x = source[i * 5];
        const float y = source[i * 5 + 1];
        vertices[i * 5] = x * c - y * s;
        vertices[i * 5 + 1] = x * s + y * c;
        vertices[i * 5 + 2] = source[i * 5 + 2];
        vertices[i * 5 + 3] = source[i * 5 + 3];
        vertices[i * 5 + 4] = source[i * 5 + 4];
    }

    glUseProgram(g_pcglEgl.program);
    glEnableVertexAttribArray(static_cast<GLuint>(g_pcglEgl.positionAttribute));
    glEnableVertexAttribArray(static_cast<GLuint>(g_pcglEgl.colorAttribute));
    glVertexAttribPointer(static_cast<GLuint>(g_pcglEgl.positionAttribute), 2, GL_FLOAT,
                          GL_FALSE, 5 * sizeof(float), vertices);
    glVertexAttribPointer(static_cast<GLuint>(g_pcglEgl.colorAttribute), 3, GL_FLOAT,
                          GL_FALSE, 5 * sizeof(float), vertices + 2);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glDisableVertexAttribArray(static_cast<GLuint>(g_pcglEgl.positionAttribute));
    glDisableVertexAttribArray(static_cast<GLuint>(g_pcglEgl.colorAttribute));
    glUseProgram(0);

    const GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        std::ostringstream info; info << "OpenGL ES error 0x" << std::hex << error;
        g_pcglEgl.info = info.str(); return JNI_FALSE;
    }
    if (eglSwapBuffers(g_pcglEgl.display, g_pcglEgl.surface) != EGL_TRUE) {
        g_pcglEgl.info = pcglEglError("eglSwapBuffers"); return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suksesjt12_pcgamelauncher_NativeBridge_destroyDisplaySurface(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(g_pcglEglMutex);
    pcglDestroyEglLocked();
}
extern "C"
JNIEXPORT jstring JNICALL
Java_com_suksesjt12_pcgamelauncher_NativeBridge_displayInfo(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> guard(g_pcglEglMutex);
    return pcglNewString(env, g_pcglEgl.info);
}
