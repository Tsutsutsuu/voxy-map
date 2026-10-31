package com.voxymap.fabric;

import com.voxymap.VoxyMapConfig;
import com.voxymap.VoxyMapMod;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric client entry point. The data layer and the map screen are ported from the NeoForge build
 * and adapted to the 26.2 rendering API (GuiGraphicsExtractor / extractRenderState).
 */
public class VoxyMapFabric implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("voxymap");

    @Override
    public void onInitializeClient() {
        VoxyMapConfig.load();
        VoxyMapMod.registerKeys();
        VoxyMapMod.registerTick();
        LOGGER.info("Voxy Map (Fabric, 26.2) initialised");
    }
}
