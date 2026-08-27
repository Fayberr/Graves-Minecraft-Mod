package net.fayber.graves;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Spawns the vanilla display entities (block_display / item_display /
 * text_display) that make up one {@link GraveLook}'s geometry, all at a
 * shared position and yaw.
 *
 * Ported from the Grave Look Lab scratch mod (a separate project used only to
 * prototype grave visuals before landing them here). See that project's
 * README and the {@code mc-display-entity-geometry} brain note for the
 * rendering gotchas this relies on: block_display grows from its origin
 * corner while item_display is origin-centred, a text_display's readable
 * face is its local +Z, and text is bottom-anchored rather than centred.
 *
 * Every piece spawns at the same entity Pos and Rotation; shape comes only
 * from each display's own {@code transformation} translation. That keeps the
 * whole assembly rotating as a rigid body, and lets {@link GraveManager}'s
 * shake animation move every piece by the same offset instead of tracking
 * per-piece transforms.
 *
 * Entity type lookups go through the registry rather than
 * {@code EntityType.BLOCK_DISPLAY} style constants, matching the pattern
 * {@link GraveSpawner} already uses for interaction/item_display/text_display
 * so this file needs no changes when ported to a Minecraft version that
 * removes those constants (26.2 already did).
 */
public final class GraveDisplayBuilder {

    private final Level level;
    private final double ax;
    private final double ay;
    private final double az;
    private final float yaw;
    private final List<Entity> spawned = new ArrayList<>();

    /**
     * @param anchorX block-centre X of the grave
     * @param anchorY ground height the grave stands on
     * @param anchorZ block-centre Z of the grave
     * @param yaw     entity yaw; local +Z ends up pointing this way
     */
    public GraveDisplayBuilder(Level level, double anchorX, double anchorY, double anchorZ, float yaw) {
        this.level = level;
        this.ax = anchorX;
        this.ay = anchorY;
        this.az = anchorZ;
        this.yaw = yaw;
    }

    public List<Entity> spawned() {
        return List.copyOf(spawned);
    }

    /**
     * A rectangular slab of a block texture, centred at ({@code cx}, {@code cz})
     * and resting on {@code cyBase}.
     *
     * A block_display renders its block model filling the unit cube that
     * starts at the entity origin and grows toward +X/+Y/+Z, so the
     * translation pulls it back by half its footprint. With a per-piece
     * rotation in play that offset must itself be rotated, otherwise the
     * piece orbits its corner instead of spinning in place.
     */
    public Entity block(BlockState state,
                        double cx, double cyBase, double cz,
                        double sx, double sy, double sz,
                        Quaternionf rot) {
        CompoundTag nbt = base(entityType("block_display"));

        BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).result()
                .ifPresent(tag -> nbt.put("block_state", tag));

        Vector3f corner = new Vector3f((float) (sx / 2.0), 0f, (float) (sz / 2.0));
        rot.transform(corner);

        CompoundTag transform = new CompoundTag();
        transform.put("translation", floats(
                (float) cx - corner.x(),
                (float) cyBase - corner.y(),
                (float) cz - corner.z()));
        transform.put("scale", floats((float) sx, (float) sy, (float) sz));
        transform.put("left_rotation", floats(rot.x(), rot.y(), rot.z(), rot.w()));
        transform.put("right_rotation", floats(0f, 0f, 0f, 1f));
        nbt.put("transformation", transform);

        return spawn(entityType("block_display"), nbt);
    }

    /** Convenience overload for an axis-aligned piece. */
    public Entity block(BlockState state,
                        double cx, double cyBase, double cz,
                        double sx, double sy, double sz) {
        return block(state, cx, cyBase, cz, sx, sy, sz, new Quaternionf());
    }

    /**
     * An item rendered in the world, centred on the given local point.
     *
     * Unlike a block_display, an item_display's model is already centred on
     * the entity origin, so no half-size correction is needed here.
     */
    public Entity item(ItemStack stack, double cx, double cy, double cz, double scale, Quaternionf rot) {
        CompoundTag nbt = base(entityType("item_display"));
        nbt.putString("item_display", "fixed");
        nbt.put("item", itemNbt(stack));

        CompoundTag transform = new CompoundTag();
        transform.put("translation", floats((float) cx, (float) cy, (float) cz));
        transform.put("scale", floats((float) scale, (float) scale, (float) scale));
        transform.put("left_rotation", floats(rot.x(), rot.y(), rot.z(), rot.w()));
        transform.put("right_rotation", floats(0f, 0f, 0f, 1f));
        nbt.put("transformation", transform);

        return spawn(entityType("item_display"), nbt);
    }

    /**
     * A flat text panel, fixed in space rather than turning to follow the
     * camera. The background is switched off entirely (transparent,
     * non-default) so the glyphs read as carved into whatever surface sits
     * behind them instead of floating on the usual dark nameplate quad.
     */
    public Entity engravedText(Component text, double cx, double cy, double cz, double scale, Quaternionf rot) {
        CompoundTag nbt = base(entityType("text_display"));
        nbt.putString("billboard", "fixed");
        nbt.putString("alignment", "center");
        nbt.putInt("background", 0);
        nbt.putBoolean("default_background", false);
        nbt.putBoolean("see_through", false);
        nbt.putBoolean("shadow", false);
        nbt.putInt("line_width", 200);
        ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, text).result()
                .ifPresent(tag -> nbt.put("text", tag));

        CompoundTag transform = new CompoundTag();
        transform.put("translation", floats((float) cx, (float) cy, (float) cz));
        transform.put("scale", floats((float) scale, (float) scale, (float) scale));
        transform.put("left_rotation", floats(rot.x(), rot.y(), rot.z(), rot.w()));
        transform.put("right_rotation", floats(0f, 0f, 0f, 1f));
        nbt.put("transformation", transform);

        return spawn(entityType("text_display"), nbt);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A player head item stack wearing the given profile's skin. */
    public static ItemStack playerHead(GameProfile profile) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
        return stack;
    }

    /** Rotation of {@code degrees} about the local vertical axis. */
    public static Quaternionf yawQuat(float degrees) {
        return new Quaternionf().rotateY((float) Math.toRadians(degrees));
    }

    private CompoundTag base(EntityType<?> type) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
        tag.store("UUID", UUIDUtil.CODEC, UUID.randomUUID());
        tag.put("Pos", doubles(ax, ay, az));
        tag.put("Rotation", floats(yaw, 0f));
        // Vanilla default render distance, deliberately not cranked: how far
        // a grave stays visible is part of the design, not an oversight.
        tag.putFloat("view_range", 1.0f);
        return tag;
    }

    private Entity spawn(EntityType<?> type, CompoundTag nbt) {
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), nbt);
        Entity entity = EntityType.create(type, input, level, EntitySpawnReason.LOAD).orElse(null);
        if (entity == null) {
            return null;
        }
        // Not the grave's own interaction hitbox, so it must not repel a
        // later grave's placement search the way a bare `interaction` entity
        // normally would (see graves:grave_repelling).
        entity.addTag(GraveSpawner.ENTITY_TAG_NON_REPELLING);
        level.addFreshEntity(entity);
        spawned.add(entity);
        return entity;
    }

    private CompoundTag itemNbt(ItemStack stack) {
        var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag tag = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
        return tag instanceof CompoundTag ct ? ct : new CompoundTag();
    }

    private static EntityType<?> entityType(String id) {
        return BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", id));
    }

    private static ListTag doubles(double... values) {
        ListTag list = new ListTag();
        for (double v : values) list.add(DoubleTag.valueOf(v));
        return list;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float v : values) list.add(FloatTag.valueOf(v));
        return list;
    }
}
