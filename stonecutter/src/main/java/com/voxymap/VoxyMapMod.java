package com.voxymap;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Glue that the map screen expects from a mod entry point: key bindings and the client tick that
 * feeds MapData. Registered from {@link com.voxymap.fabric.VoxyMapFabric}.
 */
public final class VoxyMapMod {
    public static KeyMapping openKey;
    public static KeyMapping centerKey;

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("voxymap", "main"));

    private VoxyMapMod() {
    }

    public static void registerKeys() {
        openKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.voxymap.open", GLFW.GLFW_KEY_N, CATEGORY));
        centerKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.voxymap.center", GLFW.GLFW_KEY_SPACE, CATEGORY));
    }

    public static void registerTick() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MapData.INSTANCE.tick();
            if (openKey == null) return;
            // always drain the queue so a close press does not immediately reopen the map
            while (openKey.consumeClick()) {
                if (client.player == null) continue;
                if (!(client.gui.screen() instanceof MapScreen)) {
                    client.setScreenAndShow(new MapScreen());
                }
            }
        });
    }
}
