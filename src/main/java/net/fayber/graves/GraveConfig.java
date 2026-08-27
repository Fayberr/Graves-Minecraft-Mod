package net.fayber.graves;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    /** Whether the deepslate_grave look is in the random pool. */
    public boolean grave_look_deepslate_grave = true;
    /** Whether the wooden_cross look is in the random pool. */
    public boolean grave_look_wooden_cross = true;
    /** Whether the deepslate_tombstone look is in the random pool. */
    public boolean grave_look_deepslate_tombstone = true;
    /** Whether the soulgrave look is in the random pool. */
    public boolean grave_look_soulgrave = true;

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
                    if (raw.grave_look_deepslate_grave != null) cfg.grave_look_deepslate_grave = raw.grave_look_deepslate_grave;
                    if (raw.grave_look_wooden_cross != null) cfg.grave_look_wooden_cross = raw.grave_look_wooden_cross;
                    if (raw.grave_look_deepslate_tombstone != null) cfg.grave_look_deepslate_tombstone = raw.grave_look_deepslate_tombstone;
                    if (raw.grave_look_soulgrave != null) cfg.grave_look_soulgrave = raw.grave_look_soulgrave;
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
        raw.grave_look_deepslate_grave = INSTANCE.grave_look_deepslate_grave;
        raw.grave_look_wooden_cross = INSTANCE.grave_look_wooden_cross;
        raw.grave_look_deepslate_tombstone = INSTANCE.grave_look_deepslate_tombstone;
        raw.grave_look_soulgrave = INSTANCE.grave_look_soulgrave;
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
            case "grave_look_deepslate_grave" -> c.grave_look_deepslate_grave = parseBool(value);
            case "grave_look_wooden_cross" -> c.grave_look_wooden_cross = parseBool(value);
            case "grave_look_deepslate_tombstone" -> c.grave_look_deepslate_tombstone = parseBool(value);
            case "grave_look_soulgrave" -> c.grave_look_soulgrave = parseBool(value);
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

    /** Whether the given look is enabled and should take part in random selection. */
    public boolean isLookEnabled(GraveLook look) {
        return switch (look) {
            case DEEPSLATE_GRAVE -> grave_look_deepslate_grave;
            case WOODEN_CROSS -> grave_look_wooden_cross;
            case DEEPSLATE_TOMBSTONE -> grave_look_deepslate_tombstone;
            case SOULGRAVE -> grave_look_soulgrave;
        };
    }

    /**
     * The enabled looks a death can randomly spawn from. If every look is
     * disabled this falls back to all four (with a warning) rather than
     * blocking grave spawning entirely.
     */
    public List<GraveLook> enabledLooks() {
        List<GraveLook> pool = new ArrayList<>();
        for (GraveLook look : GraveLook.values()) {
            if (isLookEnabled(look)) pool.add(look);
        }
        if (pool.isEmpty()) {
            GravesMod.LOGGER.warn("[Graves] All grave looks are disabled in config; ignoring that and using all four so graves keep spawning.");
            return List.of(GraveLook.values());
        }
        return pool;
    }

    @Override
    public String toString() {
        return "allow_robbing=" + allow_robbing
                + ", pick_up_xp=" + pick_up_xp
                + ", allow_locating=" + allow_locating
                + ", compatibility_mode=" + compatibility_mode
                + ", despawn_seconds=" + despawn_seconds
                + ", grave_look_deepslate_grave=" + grave_look_deepslate_grave
                + ", grave_look_wooden_cross=" + grave_look_wooden_cross
                + ", grave_look_deepslate_tombstone=" + grave_look_deepslate_tombstone
                + ", grave_look_soulgrave=" + grave_look_soulgrave;
    }

    /** JSON shape on disk; boxed so missing keys keep their defaults. */
    private static class Raw {
        Boolean allow_robbing;
        Boolean pick_up_xp;
        Boolean allow_locating;
        Boolean compatibility_mode;
        Integer despawn_seconds;
        Boolean grave_look_deepslate_grave;
        Boolean grave_look_wooden_cross;
        Boolean grave_look_deepslate_tombstone;
        Boolean grave_look_soulgrave;
        @SuppressWarnings("unused")
        Boolean user_set_allow_locating;
    }
}
