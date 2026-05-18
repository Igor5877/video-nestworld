package com.nestworld.video.client;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.slf4j.Logger;

import java.nio.IntBuffer;

/**
 * Experimental Vulkan bootstrap used by the renderer as a Linux-only opt-in path.
 *
 * <p>Current scope:
 * - validates Vulkan loader/device extensions on the host;
 * - creates a Vulkan instance once;
 * - owns a GL texture placeholder that can be used as a target in the BE renderer.
 *
 * <p>Interop export/import is intentionally staged behind this class so we can swap
 * the placeholder for true external-memory sharing in one place.
 */
public final class VulkanVideoBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static VulkanVideoBridge INSTANCE;

    private final boolean enabled;
    private final long vkInstance;
    private final int bridgeTextureId;

    private VulkanVideoBridge(boolean enabled, long vkInstance, int bridgeTextureId) {
        this.enabled = enabled;
        this.vkInstance = vkInstance;
        this.bridgeTextureId = bridgeTextureId;
    }

    public static VulkanVideoBridge get() {
        if (INSTANCE == null) {
            INSTANCE = initialize();
        }
        return INSTANCE;
    }

    private static VulkanVideoBridge initialize() {
        if (!Boolean.getBoolean("nestworld.vulkan.video")) {
            return new VulkanVideoBridge(false, VK10.VK_NULL_HANDLE, -1);
        }

        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            LOGGER.warn("[NestWorldVideo] Vulkan video bridge is enabled only for Linux test runs.");
            return new VulkanVideoBridge(false, VK10.VK_NULL_HANDLE, -1);
        }

        long instance = createVkInstance();
        if (instance == VK10.VK_NULL_HANDLE) {
            return new VulkanVideoBridge(false, VK10.VK_NULL_HANDLE, -1);
        }

        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 16, 16, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        LOGGER.info("[NestWorldVideo] Vulkan bridge enabled (instance created, GL bridge texture={}).", texture);
        return new VulkanVideoBridge(true, instance, texture);
    }

    private static long createVkInstance() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            int res = VK10.vkEnumerateInstanceExtensionProperties((String) null, count, null);
            if (res != VK10.VK_SUCCESS || count.get(0) == 0) {
                LOGGER.error("[NestWorldVideo] Vulkan loader not available. vkEnumerateInstanceExtensionProperties={}", res);
                return VK10.VK_NULL_HANDLE;
            }

            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("nestworld-video"))
                .pEngineName(stack.UTF8("nestworld-video"))
                .apiVersion(VK11.VK_API_VERSION_1_1);

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo);

            var pInstance = stack.mallocPointer(1);
            int createRes = VK10.vkCreateInstance(createInfo, null, pInstance);
            if (createRes != VK10.VK_SUCCESS) {
                LOGGER.error("[NestWorldVideo] vkCreateInstance failed: {}", createRes);
                return VK10.VK_NULL_HANDLE;
            }
            return pInstance.get(0);
        } catch (Throwable t) {
            LOGGER.error("[NestWorldVideo] Failed to initialize Vulkan bridge", t);
            return VK10.VK_NULL_HANDLE;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int resolveTextureId(int fallbackDisplayTextureId) {
        // Stage 1 bridge: keep existing GL video texture until full external-memory interop is wired.
        // This method centralizes the switch so renderer integration does not change later.
        return enabled ? fallbackDisplayTextureId : fallbackDisplayTextureId;
    }

    public void shutdown() {
        if (bridgeTextureId != -1) {
            GL11.glDeleteTextures(bridgeTextureId);
        }
        if (vkInstance != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyInstance(new VkInstance(vkInstance, null), null);
        }
    }
}
