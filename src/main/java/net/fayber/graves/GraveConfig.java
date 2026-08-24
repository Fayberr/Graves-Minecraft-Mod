package net.fayber.graves;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mod config, stored as {@code config/graves.json}.
 *
 * Defaults mirror the Vanilla Tweaks datapack defaults:
 * allow_robbing=false, pick_up_xp=true, compatibility_mode=false, despawn_seconds=0.
 * allow_locating is derived at server start from the reducedDebugInfo gamerule
 * unless it was set explicitly in the file.
 */
public final class GraveConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("graves.json");

    private static GraveConfig INSTANCE = new GraveConfig();

    /** Whether players other than the owner may open a grave. */
    public boolean allow_robbing = false;
    /** Whether the grave restores XP to the owner when opened. */
    public boolean pick_up_xp = true;
    /** Whether /graves locate works. Derived from reducedDebugInfo unless set in file. */
    public boolean allow_locating = true;
    /** When true the mod stays passive so a datapack version can take over. */
    public boolean compatibility_mode = false;
    /** Seconds until graves despawn and spill their contents. 0 disables despawning. */
    public int despawn_seconds = 0;

    /** True if the config file contained an explicit allow_locating value. */
    private transient boolean userSetAllowLocating = false;

    public static GraveConfig get() {
        return INSTANCE;
    }

    public static void load() {
        GraveConfig cfg = new GraveConfig();
        if (Files.exists(PATH)) {
            try {
                String json = Files.readString(PATH);
                Raw raw = GSON.fromJson(json, Raw.class);
                if (raw != null) {
                    if (raw.allow_robbing != null) cfg.allow_robbing = raw.allow_robbing;
                    if (raw.pick_up_xp != null) cfg.pick_up_xp = raw.pick_up_xp;
                    if (raw.allow_locating != null) {
                        cfg.allow_locating = raw.allow_locating;
                        cfg.userSetAllowLocating = true;
                    }
                    if (raw.compatibility_mode != null) cfg.compatibility_mode = raw.compatibility_mode;
                    if (raw.despawn_seconds != null) cfg.despawn_seconds = raw.despawn_seconds;
                }
            } catch (Exception e) {
                GravesMod.LOGGER.error("[Graves] Failed to read config, using defaults", e);
            }
        }
        INSTANCE = cfg;
        save();
    }

    /**
     * Called once the server (and its gamerules) exist: derive allow_locating
     * from reducedDebugInfo unless the user pinned it in the config file.
     */
    public static void onServerStarted(boolean reducedDebugInfo) {
        if (!INSTANCE.userSetAllowLocating) {
            INSTANCE.allow_locating = !reducedDebugInfo;
            save();
        }
    }

    public static void save() {
        Raw raw = new Raw();
        raw.allow_robbing = INSTANCE.allow_robbing;
        raw.pick_up_xp = INSTANCE.pick_up_xp;
        raw.allow_locating = INSTANCE.allow_locating;
        raw.compatibility_mode = INSTANCE.compatibility_mode;
        raw.despawn_seconds = INSTANCE.despawn_seconds;
        // Preserve whether the user explicitly pinned allow_locating.
        if (INSTANCE.userSetAllowLocating) {
            raw.user_set_allow_locating = true;
        }
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(raw));
        } catch (IOException e) {
            GravesMod.LOGGER.error("[Graves] Failed to save config", e);
        }
    }

    /** Sets a key by name from the command; returns false if unknown. */
    public static boolean set(String key, String value) {
        GraveConfig c = INSTANCE;
        switch (key.toLowerCase()) {
            case "allow_robbing" -> c.allow_robbing = parseBool(value);
            case "pick_up_xp" -> c.pick_up_xp = parseBool(value);
            case "allow_locating" -> { c.allow_locating = parseBool(value); c.userSetAllowLocating = true; }
            case "compatibility_mode" -> c.compatibility_mode = parseBool(value);
            case "despawn_seconds" -> c.despawn_seconds = Integer.parseInt(value);
            default -> {
                return false;
            }
        }
        save();
        return true;
    }

    private static boolean parseBool(String v) {
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes");
    }

    @Override
    public String toString() {
        return "allow_robbing=" + allow_robbing
                + ", pick_up_xp=" + pick_up_xp
                + ", allow_locating=" + allow_locating
                + ", compatibility_mode=" + compatibility_mode
                + ", despawn_seconds=" + despawn_seconds;
    }

    /** JSON shape on disk; boxed so missing keys keep their defaults. */
    private static class Raw {
        Boolean allow_robbing;
        Boolean pick_up_xp;
        Boolean allow_locating;
        Boolean compatibility_mode;
        Integer despawn_seconds;
        @SuppressWarnings("unused")
        Boolean user_set_allow_locating;
    }
}
