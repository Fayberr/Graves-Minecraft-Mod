package net.fayber.graves;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu integration: registers the Graves config screen so the options can
 * be edited from the Mods screen in singleplayer. Only loaded when ModMenu is
 * present (client); dedicated servers never touch this class.
 */
public class GravesModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return GraveConfigScreen::new;
    }
}
