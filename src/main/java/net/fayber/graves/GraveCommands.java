package net.fayber.graves;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * {@code /graves} command, the mod equivalent of the datapack's
 * {@code /trigger graves} menu: list, locate, key, config.
 */
public final class GraveCommands {
    private GraveCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("graves")
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("locate")
                        .executes(ctx -> locate(ctx.getSource(), false)))
                .then(Commands.literal("key")
                        .requires(GraveCommands::isAdmin)
                        .executes(ctx -> giveKey(ctx.getSource())))
                .then(Commands.literal("config")
                        .requires(GraveCommands::isAdmin)
                        .executes(ctx -> showConfig(ctx.getSource()))
                        .then(Commands.literal("get").executes(ctx -> showConfig(ctx.getSource())))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.word())
                                                .executes(ctx -> setConfig(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "key"),
                                                        StringArgumentType.getString(ctx, "value"))))))));
    }

    private static int list(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        List<Grave> graves = player != null
                ? GraveManager.gravesOf(player.getUUID())
                : List.of();
        if (player == null) {
            // Console or command block: list everything.
            graves = GraveManager.allGraves();
        }
        if (graves.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[Graves] You have no active graves."), false);
            return 0;
        }
        for (Grave grave : graves) {
            long age = ageSeconds(source, grave);
            source.sendSuccess(() -> Component.literal("[Graves] " + grave.dimensionId()
                    + " at " + grave.x + " " + grave.y + " " + grave.z
                    + ", owner " + grave.ownerName + ", " + grave.items.size() + " stack(s)"
                    + (age >= 0 ? ", age " + age + "s" : "")), false);
        }
        return graves.size();
    }

    private static int locate(CommandSourceStack source, boolean anyOwner) {
        if (!GraveConfig.get().allow_locating && !isAdmin(source)) {
            source.sendFailure(Component.literal("[Graves] Locating graves is disabled."));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[Graves] Only players can locate graves."));
            return 0;
        }
        Grave nearest = GraveManager.nearestGrave(player.level().dimension(),
                player.blockPosition(), player.getUUID(), anyOwner || isAdmin(source));
        if (nearest == null) {
            source.sendSuccess(() -> Component.literal("[Graves] No graves found nearby."), false);
            return 0;
        }
        double dx = nearest.x + 0.5 - player.getX();
        double dy = nearest.y + 0.5 - player.getY();
        double dz = nearest.z + 0.5 - player.getZ();
        int distance = (int) Math.sqrt(dx * dx + dy * dy + dz * dz);
        String ownerNote = nearest.ownerUuid.equals(player.getUUID()) ? ""
                : " (owned by " + nearest.ownerName + ")";
        source.sendSuccess(() -> Component.literal("[Graves] Nearest grave" + ownerNote
                + ": " + nearest.x + " " + nearest.y + " " + nearest.z
                + " (" + distance + " blocks away)"), false);
        return 1;
    }

    private static int giveKey(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[Graves] Only players can hold a Grave Key."));
            return 0;
        }
        if (!player.getInventory().add(GraveKeyItem.create())) {
            player.drop(GraveKeyItem.create(), false);
        }
        source.sendSuccess(() -> Component.literal("[Graves] Handed you a Grave Key."
                + " It opens any grave."), true);
        return 1;
    }

    private static int showConfig(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("[Graves] Config: "
                + GraveConfig.get()), false);
        return 1;
    }

    private static int setConfig(CommandSourceStack source, String key, String value) {
        try {
            if (GraveConfig.set(key, value)) {
                source.sendSuccess(() -> Component.literal("[Graves] Set " + key
                        + " to " + value + ". New config: " + GraveConfig.get()), true);
                return 1;
            }
        } catch (NumberFormatException e) {
            source.sendFailure(Component.literal("[Graves] " + key
                    + " expects a number."));
            return 0;
        }
        source.sendFailure(Component.literal("[Graves] Unknown config key '" + key
                + "'. Valid keys: allow_robbing, pick_up_xp, allow_locating,"
                + " compatibility_mode, despawn_seconds."));
        return 0;
    }

    /** Old permission level 2 and up: config and key access. */
    private static boolean isAdmin(CommandSourceStack source) {
        return source.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN);
    }

    /** Seconds since the grave was created, or -1 if unknown. */
    private static long ageSeconds(CommandSourceStack source, Grave grave) {
        var level = source.getServer().getLevel(grave.dimension);
        if (level == null) return -1L;
        return (level.getGameTime() - grave.deathGameTime) / 20L;
    }
}
