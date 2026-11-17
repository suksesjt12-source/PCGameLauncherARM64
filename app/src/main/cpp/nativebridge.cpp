#include <jni.h>
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
