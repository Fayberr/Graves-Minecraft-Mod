package net.fayber.graves;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

// ModMenu integration: registers the Graves config screen so the options can
// be edited from the Mods screen in singleplayer. Only loaded when ModMenu is
// present (client); dedicated servers never touch this class. When Cloth Config
// is installed the nicer Cloth Config screen is used; otherwise it falls back
// to the hand-rolled screen.
public class GravesModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
            return GraveClothScreen::create;
        }
        return GraveConfigScreen::new;
    }
}
