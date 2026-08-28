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

// Spawns the display entities (block_display / item_display / text_display)
// making up a GraveLook's geometry, all at one shared position and yaw.
// Every piece spawns at the same Pos/Rotation and gets its shape purely from
// its own transformation, so the assembly rotates rigidly and the shake
// animation can move everything with one offset. Renderer gotchas this works
// around: block_display grows from its origin corner, item_display is
// origin-centred, text_display reads on its local +Z and is bottom-anchored.
public final class GraveDisplayBuilder {

    private final Level level;
    private final double ax;
    private final double ay;
    private final double az;
    private final float yaw;
    private final List<Entity> spawned = new ArrayList<>();

    // anchor = block centre x/z, ground height y; yaw points local +Z
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

    // a slab of block texture centred at (cx, cz), resting on cyBase.
    // block_display grows from its origin corner, so the translation pulls it
    // back by half its footprint; that offset must itself be rotated or the
    // piece orbits its corner instead of spinning in place.
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

    // axis-aligned convenience overload
    public Entity block(BlockState state,
                        double cx, double cyBase, double cz,
                        double sx, double sy, double sz) {
        return block(state, cx, cyBase, cz, sx, sy, sz, new Quaternionf());
    }

    // an item centred on the given local point; item_display models are
    // already origin-centred, unlike block_display
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

    // flat text panel fixed in space; background switched off so the glyphs
    // read as carved into the surface behind them instead of a nameplate quad
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

    // helpers
    public static ItemStack playerHead(GameProfile profile) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
        return stack;
    }

    // Rotation of degrees about the local vertical axis.
    public static Quaternionf yawQuat(float degrees) {
        return new Quaternionf().rotateY((float) Math.toRadians(degrees));
    }

    private CompoundTag base(EntityType<?> type) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
        tag.store("UUID", UUIDUtil.CODEC, UUID.randomUUID());
        tag.put("Pos", doubles(ax, ay, az));
        tag.put("Rotation", floats(yaw, 0f));
        // vanilla default render distance, deliberately not cranked
        tag.putFloat("view_range", 1.0f);
        return tag;
    }

    private Entity spawn(EntityType<?> type, CompoundTag nbt) {
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), nbt);
        Entity entity = EntityType.create(type, input, level, EntitySpawnReason.LOAD).orElse(null);
        if (entity == null) {
            return null;
        }
        // not the clickable hitbox, so it must not repel a later grave's
        // placement search the way a bare interaction entity would
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
