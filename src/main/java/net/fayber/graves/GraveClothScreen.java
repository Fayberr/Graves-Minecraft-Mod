package net.fayber.graves;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Cloth Config based config screen (the nicer ModMenu GUI used by most mods).
 * Optional dependency: when Cloth Config is installed, ModMenu opens this
 * instead of the hand-rolled {@link GraveConfigScreen}.
 */
public final class GraveClothScreen {
    private GraveClothScreen() {}

    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Graves"));

        ConfigEntryBuilder eb = builder.entryBuilder();
        ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));

        general.addEntry(bool(eb, "allow_robbing", "Allow Robbing", false,
                "Players other than the owner may open a grave."));
        general.addEntry(bool(eb, "pick_up_xp", "Pick Up XP", true,
                "Restore the owner's XP when the grave is opened."));
        general.addEntry(bool(eb, "allow_locating", "Allow Locating", true,
                "Whether /graves locate works. Follows the reduced debug info gamerule unless set here."));
        general.addEntry(bool(eb, "compatibility_mode", "Compatibility Mode", false,
                "Keeps the mod passive so a datapack version can take over."));
        general.addEntry(slider(eb, "despawn_seconds", "Despawn After (seconds)", 0, 0, 3600,
                "Seconds until graves despawn and spill their contents. 0 disables despawning."));

        return builder.build();
    }

    private static AbstractConfigListEntry bool(ConfigEntryBuilder eb, String key, String label,
                                                boolean defaultValue, String tooltip) {
        GraveConfig c = GraveConfig.get();
        boolean current = switch (key) {
            case "allow_robbing" -> c.allow_robbing;
            case "pick_up_xp" -> c.pick_up_xp;
            case "allow_locating" -> c.allow_locating;
            case "compatibility_mode" -> c.compatibility_mode;
            default -> defaultValue;
        };
        return eb.startBooleanToggle(Component.literal(label), current)
                .setDefaultValue(defaultValue)
                .setTooltip(Component.literal(tooltip))
                .setSaveConsumer(value -> GraveConfig.set(key, String.valueOf(value)))
                .build();
    }

    private static AbstractConfigListEntry slider(ConfigEntryBuilder eb, String key, String label,
                                                  int defaultValue, int min, int max, String tooltip) {
        return eb.startIntSlider(Component.literal(label), GraveConfig.get().despawn_seconds, min, max)
                .setDefaultValue(defaultValue)
                .setTooltip(Component.literal(tooltip))
                .setSaveConsumer(value -> GraveConfig.set(key, String.valueOf(value)))
                .build();
    }
}
