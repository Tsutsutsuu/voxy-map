package com.voxymap;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

/** Version and platform strings shown on the About tab (Fabric build). */
public final class AboutInfo {
    private AboutInfo() {
    }

    /** The running jar's version, e.g. {@code 0.5.1}, read from the loader metadata. */
    public static String modVersion() {
        return FabricLoader.getInstance()
                .getModContainer("voxymap")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }

    public static String platform() {
        return "Fabric";
    }

    public static String mcVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    /** One {@code modid version} line per loaded mod, sorted; attached to feedback on request. */
    public static String modList() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (var container : FabricLoader.getInstance().getAllMods()) {
            var meta = container.getMetadata();
            lines.add(meta.getId() + " " + meta.getVersion().getFriendlyString());
        }
        java.util.Collections.sort(lines);
        return String.join("\n", lines);
    }
}
