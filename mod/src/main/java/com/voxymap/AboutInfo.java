package com.voxymap;

import net.minecraft.SharedConstants;
import net.neoforged.fml.ModList;

/** Version and platform strings shown on the About tab (NeoForge build). */
public final class AboutInfo {
    private AboutInfo() {
    }

    /** The running jar's version, e.g. {@code 0.5.1}, read from the loader metadata. */
    public static String modVersion() {
        return ModList.get()
                .getModContainerById("voxymap")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("?");
    }

    public static String platform() {
        return "NeoForge";
    }

    public static String mcVersion() {
        return SharedConstants.getCurrentVersion().getName();
    }

    /** One {@code modid version} line per loaded mod, sorted; attached to feedback on request. */
    public static String modList() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (var info : ModList.get().getMods()) {
            lines.add(info.getModId() + " " + info.getVersion());
        }
        java.util.Collections.sort(lines);
        return String.join("\n", lines);
    }
}
