package net.fayber.graves;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Spawns the grave entity stack (item_display + interaction + text_display) and
 * implements the Vanilla Tweaks grave placement state machine.
 *
 * Display entities expose no public setters for their render state in 26.1, so
 * all display configuration happens through spawn NBT.
 */
public final class GraveSpawner {
    private GraveSpawner() {}

    public static final TagKey<Block> TAG_ATTRACTING =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("graves", "grave_attracting"));
    public static final TagKey<Block> TAG_IMPENETRABLE =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("graves", "grave_impenetrable"));
    public static final TagKey<Block> TAG_STOP_BOTTOM =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("graves", "grave_stopping_on_bottom"));
    public static final TagKey<Block> TAG_STOP_TOP =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("graves", "grave_stopping_on_top"));
    public static final TagKey<EntityType<?>> TAG_REPELLING_ENTITY =
            TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("graves", "grave_repelling"));

    public static final String ENTITY_TAG_GRAVE = "graves:grave";
    public static final String ENTITY_TAG_NON_REPELLING = "graves:non_grave_repelling";

    /** Where a void platform search starts below the world. */
    private static final int WORLD_BOTTOM_Y = -2032;
    /** Highest Y used when searching down from above the world for void placement. */
    private static final int WORLD_TOP_Y = 2031;

    /** Where a grave ended up plus whether we built a cobblestone platform for it. */
    public record PlacementResult(BlockPos pos, boolean platform) {}

    // ------------------------------------------------------------------
    // Entity spawning
    // ------------------------------------------------------------------

    /**
     * Spawns the three grave entities around {@code pos} and fills in the
     * entity UUIDs on the grave object. The interaction entity rides the
     * item display so its hitbox follows any movement.
     */
    public static void spawnEntities(Level level, BlockPos pos, Grave grave) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY();
        double cz = pos.getZ() + 0.5;

        UUID displayId = UUID.randomUUID();
        UUID textId = UUID.randomUUID();
        UUID interactionId = UUID.randomUUID();

        // --- item display: floating icon above the grave ---
        CompoundTag displayNbt = baseNbt(EntityType.ITEM_DISPLAY, displayId, cx, cy, cz);
        CompoundTag transform = new CompoundTag();
        transform.put("translation", floatList(0.0f, 0.3125f, 0.0f));
        transform.put("scale", floatList(0.625f, 0.625f, 0.625f));
        transform.put("left_rotation", floatList(0f, 0f, 0f, 1f));
        transform.put("right_rotation", floatList(0f, 0f, 0f, 1f));
        displayNbt.put("transformation", transform);
        displayNbt.putString("item_display", "head");
        displayNbt.putInt("teleport_duration", 1);
        displayNbt.putInt("PosRotInterpolationDuration", 1);
        displayNbt.put("item", itemStackNbt(iconItem(grave)));

        // --- text display: owner name label ---
        CompoundTag textNbt = baseNbt(EntityType.TEXT_DISPLAY, textId, cx, cy + 0.75, cz);
        textNbt.putString("billboard", "center");
        textNbt.putFloat("view_range", 0.0625f);
        textNbt.putString("alignment", "center");
        Component label = Component.literal(grave.ownerName).withStyle(ChatFormatting.YELLOW);
        ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, label)
                .result()
                .ifPresent(tag -> textNbt.put("text", tag));

        // --- interaction: invisible clickable hitbox riding the display ---
        CompoundTag interactNbt = baseNbt(EntityType.INTERACTION, interactionId, cx, cy, cz);
        interactNbt.putFloat("width", 0.75f);
        interactNbt.putFloat("height", 1.8f);
        interactNbt.putBoolean("attack", true);
        interactNbt.putBoolean("interaction", true);
        interactNbt.putBoolean("response", true);

        Entity display = loadEntity(level, EntityType.ITEM_DISPLAY, displayNbt);
        Entity text = loadEntity(level, EntityType.TEXT_DISPLAY, textNbt);
        Entity interaction = loadEntity(level, EntityType.INTERACTION, interactNbt);

        if (display != null) {
            level.addFreshEntity(display);
            if (interaction != null) {
                interaction.startRiding(display);
                level.addFreshEntity(interaction);
                interaction.addTag(ENTITY_TAG_GRAVE);
                interaction.addTag(ENTITY_TAG_NON_REPELLING);
            }
        } else if (interaction != null) {
            // Fallback: spawn the hitbox on its own so the grave still works.
            level.addFreshEntity(interaction);
            interaction.addTag(ENTITY_TAG_GRAVE);
            interaction.addTag(ENTITY_TAG_NON_REPELLING);
        }
        if (text != null) {
            level.addFreshEntity(text);
            text.addTag(ENTITY_TAG_NON_REPELLING);
        }

        grave.displayUuid = display != null ? display.getUUID() : displayId;
        grave.nameTagUuid = text != null ? text.getUUID() : textId;
        grave.interactionUuid = interaction != null ? interaction.getUUID() : interactionId;
    }

    private static CompoundTag baseNbt(EntityType<?> type, UUID uuid, double x, double y, double z) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
        tag.store("UUID", net.minecraft.core.UUIDUtil.CODEC, uuid);
        tag.put("Pos", doubleList(x, y, z));
        return tag;
    }

    /**
     * Builds an entity from hand-written NBT without going through spawn logic,
     * exactly like a chunk-load would.
     */
    private static Entity loadEntity(Level level, EntityType<?> type, CompoundTag nbt) {
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), nbt);
        return EntityType.create(type, input, level, EntitySpawnReason.LOAD).orElse(null);
    }

    private static ListTag doubleList(double... values) {
        ListTag list = new ListTag();
        for (double v : values) list.add(DoubleTag.valueOf(v));
        return list;
    }

    private static ListTag floatList(float... values) {
        ListTag list = new ListTag();
        for (float v : values) list.add(FloatTag.valueOf(v));
        return list;
    }

    private static ItemStack iconItem(Grave grave) {
        for (ItemStack stack : grave.items) {
            if (!stack.isEmpty()) return stack;
        }
        return new ItemStack(Items.PLAYER_HEAD);
    }

    /** Serializes an ItemStack into the compact entity-item NBT format. */
    private static CompoundTag itemStackNbt(ItemStack stack) {
        CompoundTag out = new CompoundTag();
        out.putString("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        out.putInt("count", stack.getCount());
        return out;
    }

    // ------------------------------------------------------------------
    // Placement state machine (port of the datapack functions)
    // ------------------------------------------------------------------

    /**
     * Runs the full placement search synchronously from the death position and
     * returns where the grave should sit. Never fails: worst case it builds a
     * cobblestone platform in the void.
     */
    public static PlacementResult placeGrave(WorldFacade level, double px, double py, double pz) {
        BlockPos p0 = new BlockPos(floor(px), floor(py + 0.5), floor(pz));

        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, p0.getX(), p0.getZ());
        if (p0.getY() >= surfaceY) {
            return startOverWorldSurface(level, p0);
        }
        if (level.isInWorldAndLoaded(p0)) {
            return continuePlacement(level, p0, p0);
        }
        if (p0.getY() < WORLD_BOTTOM_Y) {
            return approachWorldBottom(level, p0.getX(), WORLD_BOTTOM_Y, p0.getZ(), p0);
        }
        return approachWorldBottom(level, p0.getX(), p0.getY() - 9, p0.getZ(), p0);
    }

    private static PlacementResult startOverWorldSurface(WorldFacade level, BlockPos pos) {
        if (isRepelling(level, pos)) {
            return startRepelling(level, pos, false, pos);
        }
        return continueFalling(level, pos, pos);
    }

    private static PlacementResult continuePlacement(WorldFacade level, BlockPos pos, BlockPos origin) {
        if (isRepelling(level, pos)) {
            return startRepelling(level, pos, false, origin);
        }
        return continueInAttracting(level, pos, origin);
    }

    private static PlacementResult continueInAttracting(WorldFacade level, BlockPos pos, BlockPos origin) {
        // should_grave_float is always false in the datapack: graves never float.
        return continueFalling(level, pos, origin);
    }

    private static PlacementResult continueFalling(WorldFacade level, BlockPos pos, BlockPos origin) {
        BlockPos cur = pos;
        while (true) {
            BlockPos below = cur.below();
            if (isRepelling(level, below)) return stop(cur);
            if (level.getBlockState(cur).is(TAG_STOP_BOTTOM)) return stop(cur);
            if (level.getBlockState(below).is(TAG_STOP_TOP)) return stop(cur);
            if (!level.isInWorldAndLoaded(below)) {
                return handleFallIntoVoid(level, origin);
            }
            cur = below;
        }
    }

    private static PlacementResult stop(BlockPos pos) {
        return new PlacementResult(new BlockPos(floor(pos.getX() + 0.5), pos.getY(), floor(pos.getZ() + 0.5)), false);
    }

    private static PlacementResult handleFallIntoVoid(WorldFacade level, BlockPos p0) {
        if (level.isInWorldAndLoaded(p0)) {
            return continueAboveVoid(level, p0, p0);
        }
        if (p0.getY() >= WORLD_TOP_Y + 1) {
            return approachWorldTop(level, p0.getX(), WORLD_TOP_Y, p0.getZ(), p0);
        }
        return approachWorldTop(level, p0.getX(), p0.getY() - 9, p0.getZ(), p0);
    }

    private static PlacementResult continueAboveVoid(WorldFacade level, BlockPos pos, BlockPos origin) {
        if (isRepelling(level, pos)) {
            return startRepelling(level, pos, false, origin);
        }
        if (level.getBlockState(pos).is(BlockTags.REPLACEABLE)) {
            return stopOnVoidPlatform(level, pos);
        }
        return stop(pos);
    }

    private static PlacementResult stopOnVoidPlatform(WorldFacade level, BlockPos pos) {
        BlockState slab = Blocks.COBBLESTONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        level.setBlock(pos, slab);
        // The grave rests on top of the slab.
        return new PlacementResult(new BlockPos(floor(pos.getX() + 0.5), pos.getY() + 1, floor(pos.getZ() + 0.5)), true);
    }

    private static PlacementResult approachWorldBottom(WorldFacade level, int x, int y, int z, BlockPos origin) {
        BlockPos pos = new BlockPos(x, y, z);
        while (!level.isInWorldAndLoaded(pos)) {
            pos = pos.above(16);
        }
        return continuePlacement(level, pos, origin);
    }

    private static PlacementResult approachWorldTop(WorldFacade level, int x, int y, int z, BlockPos origin) {
        BlockPos pos = new BlockPos(x, y, z);
        while (!level.isInWorldAndLoaded(pos)) {
            pos = pos.below(16);
        }
        return continueAboveVoid(level, pos, origin);
    }

    private static PlacementResult startRepelling(WorldFacade level, BlockPos pos, boolean bypass, BlockPos origin) {
        if (anyMarkerFree(level, pos)) {
            return continueInAttracting(level, pos, origin);
        }
        if (level.getBlockState(pos).is(TAG_IMPENETRABLE)) {
            return repelFromImpenetrable(level, pos, bypass, origin);
        }
        if (!bypass && level.getBlockState(pos.above()).is(TAG_IMPENETRABLE)) {
            return stop(pos);
        }
        if (isRepelling(level, pos.above())) {
            return continueRepelling(level, pos.above(), bypass, origin);
        }
        return continueInAttracting(level, pos, origin);
    }

    private static PlacementResult continueRepelling(WorldFacade level, BlockPos pos, boolean bypass, BlockPos origin) {
        if (anyMarkerFree(level, pos)) {
            return continueInAttracting(level, pos, origin);
        }
        if (level.getBlockState(pos).is(TAG_IMPENETRABLE)) {
            return repelFromImpenetrable(level, pos, bypass, origin);
        }
        if (!bypass && level.getBlockState(pos.above()).is(TAG_IMPENETRABLE)) {
            return stop(pos);
        }
        if (isRepelling(level, pos.above())) {
            return continueRepelling(level, pos.above(), bypass, origin);
        }
        return continueInAttracting(level, pos, origin);
    }

    private static PlacementResult repelFromImpenetrable(WorldFacade level, BlockPos pos, boolean bypass, BlockPos origin) {
        bypass = true;
        if (!isRepelling(level, pos.above())) {
            return continueInAttracting(level, pos, origin);
        }
        BlockPos marker = nearestFreeSideMarker(level, pos);
        if (marker != null) {
            return continueRepelling(level, marker, bypass, origin);
        }
        return continueRepelling(level, pos.above(), bypass, origin);
    }

    /**
     * True if ANY of the four side markers is NOT repelling; markers sit just
     * outside each wall of the block at the grave's Y level.
     */
    private static boolean anyMarkerFree(WorldFacade level, BlockPos pos) {
        return !isRepelling(level, markerPos(pos, Side.NORTH))
                || !isRepelling(level, markerPos(pos, Side.SOUTH))
                || !isRepelling(level, markerPos(pos, Side.WEST))
                || !isRepelling(level, markerPos(pos, Side.EAST));
    }

    /** First side marker whose block is NOT impenetrable, or null. Order: N, S, W, E. */
    private static BlockPos nearestFreeSideMarker(WorldFacade level, BlockPos pos) {
        for (Side side : List.of(Side.NORTH, Side.SOUTH, Side.WEST, Side.EAST)) {
            BlockPos marker = markerPos(pos, side);
            if (!level.getBlockState(marker).is(TAG_IMPENETRABLE)) {
                return marker;
            }
        }
        return null;
    }

    /**
     * Marker coordinates mirror the datapack offsets:
     * N=(x,y,z-0.25), S=(x,y,z+1.25), W=(x-0.25,y,z), E=(x+1.25,y,z),
     * converted back to the block each marker falls inside.
     */
    private static BlockPos markerPos(BlockPos pos, Side side) {
        double mx = pos.getX();
        double mz = pos.getZ();
        switch (side) {
            case NORTH -> mz -= 0.25;
            case SOUTH -> mz += 1.25;
            case WEST -> mx -= 0.25;
            case EAST -> mx += 1.25;
        }
        return BlockPos.containing(mx, pos.getY(), mz);
    }

    /**
     * A position is repelling when its block does NOT attract graves, or when a
     * repelling-type entity (not flagged non_grave_repelling) occupies it.
     */
    private static boolean isRepelling(WorldFacade level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(TAG_ATTRACTING)) {
            return true;
        }
        return !level.getRepellingEntities(new AABB(pos)).isEmpty();
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    private enum Side { NORTH, SOUTH, WEST, EAST }

    /** Minimal facade over ServerLevel so the state machine stays decoupled. */
    public interface WorldFacade {
        BlockState getBlockState(BlockPos pos);
        void setBlock(BlockPos pos, BlockState state);
        int getHeight(Heightmap.Types type, int x, int z);
        boolean isInWorldAndLoaded(BlockPos pos);
        List<Entity> getRepellingEntities(AABB box);
    }
}
