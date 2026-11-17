#include <jni.h>
#include <vulkan/vulkan.h>

#include <sstream>
#include <string>
#include <vector>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_suksesjt12_pcgamelauncher_NativeBridge_runtimeStatus(
    JNIEnv* env,
    jobject /* thiz */) {
    constexpr const char* status =
        "Native ARM64 bridge loaded. Wine/Box64 runtime is not bundled yet.";
    return env->NewStringUTF(status);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_suksesjt12_pcgamelauncher_NativeBridge_deviceInfo(
    JNIEnv* env,
    jobject /* thiz */) {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "PCGameLauncherARM64";
    appInfo.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    appInfo.pEngineName = "LauncherDeviceProbe";
    appInfo.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    const VkResult createResult = vkCreateInstance(&createInfo, nullptr, &instance);
    if (createResult != VK_SUCCESS) {
        return env->NewStringUTF("Vulkan unavailable or instance creation failed.");
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
        return env->NewStringUTF("Vulkan device enumeration failed.");
    }

    std::ostringstream output;
    output << "Vulkan device(s): " << count;
    for (uint32_t i = 0; i < count; ++i) {
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(devices[i], &properties);
        output << "\n" << (i + 1) << ". " << properties.deviceName
               << " | API " << VK_VERSION_MAJOR(properties.apiVersion)
               << "." << VK_VERSION_MINOR(properties.apiVersion)
               << "." << VK_VERSION_PATCH(properties.apiVersion);
    }

    vkDestroyInstance(instance, nullptr);
    const std::string info = output.str();
    return env->NewStringUTF(info.c_str());
}
