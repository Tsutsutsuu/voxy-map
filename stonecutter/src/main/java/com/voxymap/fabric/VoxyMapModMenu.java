package com.voxymap.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.voxymap.MapConfigScreen;

/**
 * Mod Menu integration: adds the "config" button to the mod list entry. Mod Menu is optional, so
 * this class only loads when it is present (declared as a modmenu entrypoint).
 */
public class VoxyMapModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MapConfigScreen::new;
    }
}
