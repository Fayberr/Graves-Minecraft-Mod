package net.fayber.graves;

import com.mojang.serialization.DataResult;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A single grave: the captured inventory of one death plus bookkeeping data.
 *
 * Items are stored as parallel lists ({@code items}/{@code slots}) so each stack
 * remembers which player inventory slot (0-40) it came from. The whole grave
 * round-trips through a {@link CompoundTag} so it can be persisted to disk.
 */
public class Grave {
    /** Grave contents. Parallel to {@link #slots}. */
    public final List<ItemStack> items = new ArrayList<>();
    /** Origin player inventory slot for each entry in {@link #items} (0-40). */
    public final List<Integer> slots = new ArrayList<>();

    public UUID id;
    public UUID ownerUuid;
    public String ownerName = "?";
    public int xpPoints;
    public long deathGameTime;
    /** UUID of the interaction entity acting as the clickable hitbox. */
    public UUID interactionUuid;
    /**
     * UUIDs of every block_display/item_display/text_display entity that make
     * up this grave's {@link GraveLook} assembly.
     */
    public final List<UUID> displayEntityUuids = new ArrayList<>();
    /** Which {@link GraveLook} this grave was spawned with, kept for reference. */
    public String lookId;

    /**
     * Single-entity UUIDs from before the visual redesign (one item_display
     * icon plus one text_display name). Always null for graves created after
     * it; kept only so a grave saved by an older version of the mod still
     * gets fully cleaned up once picked up, robbed, or despawned.
     */
    public UUID legacyDisplayUuid;
    public UUID legacyNameTagUuid;

    public ResourceKey<Level> dimension;
    /** Final block position of the grave (the block the entities sit in). */
    public int x, y, z;

    // Transient runtime state (not persisted).
    public transient int shakeTicks;
    public transient boolean platformPlaced;

    public Grave() {
        this.id = UUID.randomUUID();
    }

    public boolean isEmpty() {
        boolean anyItem = false;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) { anyItem = true; break; }
        }
        return !anyItem && xpPoints <= 0;
    }

    public Identifier dimensionId() {
        return dimension.identifier();
    }

    /**
     * Removes the stacks at the given indices (used after a partial pickup).
     * Indices must be sorted ascending.
     */
    public void removeIndices(List<Integer> indices) {
        for (int i = indices.size() - 1; i >= 0; i--) {
            int idx = indices.get(i);
            items.remove(idx);
            slots.remove(idx);
        }
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    public CompoundTag toTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        putUuid(tag, "id", id);
        putUuid(tag, "owner", ownerUuid);
        tag.putString("owner_name", ownerName);
        tag.putInt("xp", xpPoints);
        tag.putLong("death_time", deathGameTime);
        putUuid(tag, "interaction", interactionUuid);
        if (!displayEntityUuids.isEmpty()) {
            tag.store("display_entities", UUIDUtil.CODEC.listOf(), displayEntityUuids);
        }
        if (lookId != null) tag.putString("look", lookId);
        // Legacy single-entity fields, only ever non-null for a grave saved
        // before the visual redesign and not yet cleaned up.
        putUuid(tag, "name_tag", legacyNameTagUuid);
        putUuid(tag, "display", legacyDisplayUuid);
        tag.putString("dim", dimensionId().toString());
        tag.putIntArray("pos", new int[]{x, y, z});

        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        ListTag itemList = new ListTag();
        List<Integer> slotList = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;
            DataResult<Tag> encoded = ItemStack.CODEC.encodeStart(ops, stack);
            Tag encodedTag = encoded.result().orElse(null);
            if (encodedTag == null) continue;
            itemList.add(encodedTag);
            slotList.add(slots.get(i));
        }
        tag.put("items", itemList);
        int[] slotArr = new int[slotList.size()];
        for (int i = 0; i < slotList.size(); i++) slotArr[i] = slotList.get(i);
        tag.put("slots", new IntArrayTag(slotArr));
        tag.putBoolean("platform", platformPlaced);
        return tag;
    }

    public static Grave fromTag(HolderLookup.Provider registries, CompoundTag tag) {
        Grave grave = new Grave();
        grave.id = getUuid(tag, "id");
        grave.ownerUuid = getUuid(tag, "owner");
        grave.ownerName = tag.getStringOr("owner_name", "?");
        grave.xpPoints = tag.getIntOr("xp", 0);
        grave.deathGameTime = tag.getLongOr("death_time", 0L);
        grave.interactionUuid = getUuid(tag, "interaction");
        grave.displayEntityUuids.clear();
        grave.displayEntityUuids.addAll(
                tag.read("display_entities", UUIDUtil.CODEC.listOf()).orElse(List.of()));
        grave.lookId = tag.getString("look").orElse(null);
        grave.legacyNameTagUuid = getUuid(tag, "name_tag");
        grave.legacyDisplayUuid = getUuid(tag, "display");

        String dim = tag.getStringOr("dim", "minecraft:overworld");
        int sep = dim.indexOf(':');
        Identifier dimId = sep >= 0
                ? Identifier.fromNamespaceAndPath(dim.substring(0, sep), dim.substring(sep + 1))
                : Identifier.fromNamespaceAndPath("minecraft", "overworld");
        grave.dimension = ResourceKey.create(Registries.DIMENSION, dimId);

        int[] pos = tag.getIntArray("pos").orElse(new int[0]);
        if (pos.length == 3) {
            grave.x = pos[0];
            grave.y = pos[1];
            grave.z = pos[2];
        }

        grave.items.clear();
        grave.slots.clear();
        ListTag itemList = tag.getListOrEmpty("items");
        int[] slotArr = tag.getIntArray("slots").orElse(new int[0]);
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (int i = 0; i < itemList.size(); i++) {
            CompoundTag itemTag = itemList.getCompoundOrEmpty(i);
            ItemStack parsed = ItemStack.OPTIONAL_CODEC.parse(ops, itemTag)
                    .result().orElse(ItemStack.EMPTY);
            grave.items.add(parsed);
            grave.slots.add(i < slotArr.length ? slotArr[i] : 0);
        }
        grave.platformPlaced = tag.getBooleanOr("platform", false);
        return grave;
    }

    private static void putUuid(CompoundTag tag, String key, UUID value) {
        if (value != null) tag.store(key, UUIDUtil.CODEC, value);
    }

    private static UUID getUuid(CompoundTag tag, String key) {
        return tag.read(key, UUIDUtil.CODEC).orElse(null);
    }

    /** Whole-grave NBT compressed and base64 encoded, for storage inside JSON. */
    public String toBase64(HolderLookup.Provider registries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(toTag(registries), bytes);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            GravesMod.LOGGER.error("[Graves] Failed to serialize grave {}", id, e);
            return "";
        }
    }

    public static Grave fromBase64(HolderLookup.Provider registries, String b64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            return fromTag(registries, tag);
        } catch (IOException e) {
            GravesMod.LOGGER.error("[Graves] Failed to deserialize grave", e);
            return null;
        }
    }
}
