package com.voxymap;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

@Mod(VoxyMapMod.MODID)
public class VoxyMapMod {
    public static final String MODID = "voxymap";

    public static KeyMapping openKey;
    public static KeyMapping centerKey;

    public VoxyMapMod(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::registerKeys);
        container.registerConfig(ModConfig.Type.CLIENT, VoxyMapConfig.SPEC);
        // "config" button on the mod list entry opens our own config screen (same UI as Fabric)
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (modContainer, parent) -> new MapConfigScreen(parent));
        NeoForge.EVENT_BUS.addListener(this::clientTick);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
    }


    private void registerKeys(RegisterKeyMappingsEvent event) {
        openKey = new KeyMapping("key.voxymap.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.voxymap");
        centerKey = new KeyMapping("key.voxymap.center", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SPACE, "key.categories.voxymap");
        event.register(openKey);
        event.register(centerKey);
    }

    private void clientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (openKey != null) {
            // always drain clicks (even while the map is open) so closing it can't reopen it
            boolean open = false;
            while (openKey.consumeClick()) {
                open = true;
            }
            if (open && mc.screen == null && mc.player != null) {
                mc.setScreen(new MapScreen());
            }
        }
        MapData.INSTANCE.tick();
    }

    private void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        MapData.INSTANCE.resetForNewWorld();
    }
}
