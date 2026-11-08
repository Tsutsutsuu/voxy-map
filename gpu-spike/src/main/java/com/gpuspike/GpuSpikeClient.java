package com.gpuspike;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Standalone client-only GPU spike. Press G to open the fullscreen test UI. */
public final class GpuSpikeClient implements ClientModInitializer {
    public static KeyMapping openKey;
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("gpu_spike", "main"));

    @Override
    public void onInitializeClient() {
        openKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.gpu_spike.open", GLFW.GLFW_KEY_G, CATEGORY));
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(GpuCubeRenderer::render);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openKey == null) return;
            while (openKey.consumeClick()) {
                if (client.player == null) continue;
                if (!(client.gui.screen() instanceof GpuCubeScreen)) {
                    client.setScreenAndShow(new GpuCubeScreen());
                }
            }
        });
    }
}
