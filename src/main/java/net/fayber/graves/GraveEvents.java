package net.fayber.graves;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

// wires all fabric events to GraveManager / GraveCommands / GraveConfig
public final class GraveEvents {
    private GraveEvents() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                GraveCommands.register(dispatcher));

        // death capture
        ServerLivingEntityEvents.ALLOW_DEATH.register(GraveManager::onAllowDeath);

        // right-click and left-click on the invisible hitbox
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
                GraveManager.onInteract(player, world, hand, entity));
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
                GraveManager.onInteract(player, world, hand, entity));

        ServerTickEvents.END_SERVER_TICK.register(GraveManager::tick);

        ServerLifecycleEvents.SERVER_STARTED.register(GraveManager::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(GraveManager::onServerStopping);
    }
}
