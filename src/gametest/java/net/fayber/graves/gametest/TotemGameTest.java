package net.fayber.graves.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fayber.graves.GraveManager;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * Regression tests for totems: the grave capture runs on Fabric's ALLOW_DEATH, which fires before
 * vanilla's totem check, so it must not strip the inventory when a totem is about to save the player.
 */
public class TotemGameTest {
    private static ServerPlayer newPlayer(GameTestHelper ctx) {
        var server = ctx.getLevel().getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), "tt" + (int) (Math.random() * 1e6)), false);
        ServerPlayer player = new ServerPlayer(server, ctx.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        // mock players are invulnerable until the client reports it has loaded
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.setHealth(2f);
        return player;
    }

    private static long gravesOf(ServerPlayer p) {
        return GraveManager.gravesOf(p.getUUID()).size();
    }

    @GameTest(maxTicks = 40)
    public void totemInOffhandSavesPlayerAndNoGraveIsMade(GameTestHelper ctx) {
        ServerPlayer p = newPlayer(ctx);
        ServerLevel level = ctx.getLevel();
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));

        p.hurtServer(level, level.damageSources().generic(), 1000f);

        if (!p.isAlive() || p.isDeadOrDying()) ctx.fail("player should have survived via the totem");
        if (!p.getOffhandItem().isEmpty()) ctx.fail("totem should have been consumed");
        if (p.getInventory().getItem(0).getCount() != 5) ctx.fail("inventory must stay with the player");
        if (gravesOf(p) != 0) ctx.fail("no grave should be created when a totem saves the player");
        ctx.succeed();
    }

    @GameTest(maxTicks = 40)
    public void totemInMainHandAlsoSavesPlayer(GameTestHelper ctx) {
        ServerPlayer p = newPlayer(ctx);
        ServerLevel level = ctx.getLevel();
        p.getInventory().setItem(5, new ItemStack(Items.DIAMOND, 3));
        p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TOTEM_OF_UNDYING));

        p.hurtServer(level, level.damageSources().generic(), 1000f);

        if (p.isDeadOrDying()) ctx.fail("player should have survived via the main-hand totem");
        if (p.getInventory().getItem(5).getCount() != 3) ctx.fail("inventory must stay with the player");
        if (gravesOf(p) != 0) ctx.fail("no grave expected");
        ctx.succeed();
    }

    @GameTest(maxTicks = 40)
    public void withoutTotemPlayerDiesAndGraveHoldsItems(GameTestHelper ctx) {
        ServerPlayer p = newPlayer(ctx);
        ServerLevel level = ctx.getLevel();
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));

        p.hurtServer(level, level.damageSources().generic(), 1000f);

        if (!p.isDeadOrDying()) ctx.fail("player should have died");
        if (gravesOf(p) != 1) ctx.fail("expected exactly one grave, got " + gravesOf(p));
        if (!p.getInventory().getItem(0).isEmpty()) ctx.fail("items should have moved into the grave");
        ctx.succeed();
    }

    @GameTest(maxTicks = 40)
    public void totemDoesNotHelpAgainstBypassDamageSoGraveIsMade(GameTestHelper ctx) {
        ServerPlayer p = newPlayer(ctx);
        ServerLevel level = ctx.getLevel();
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        DamageSource kill = level.damageSources().genericKill();

        p.hurtServer(level, kill, 1000f);

        if (!p.isDeadOrDying()) ctx.fail("/kill style damage must still kill through a totem");
        if (gravesOf(p) != 1) ctx.fail("expected a grave, got " + gravesOf(p));
        ctx.succeed();
    }
}
