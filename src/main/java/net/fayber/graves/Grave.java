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
import java.util.UUID;

// One grave: a captured inventory plus bookkeeping. Items and slots are
// parallel lists so each stack remembers its origin inventory slot (0-40).
// The whole thing round-trips through NBT for persistence.
public class Grave {
    // grave contents, parallel to slots
    public final List<ItemStack> items = new ArrayList<>();
    // origin player inventory slot for each entry in items (0-40)
    public final List<Integer> slots = new ArrayList<>();

    public UUID id;
    public UUID ownerUuid;
    public String ownerName = "?";
    public int xpPoints;
    public long deathGameTime;
    // the interaction entity acting as the clickable hitbox
    public UUID interactionUuid;
    // uuids of every display entity making up this grave's look
    public final List<UUID> displayEntityUuids = new ArrayList<>();
    public String lookId;

    // single-entity uuids from before the visual redesign (one item_display
    // icon plus one text_display name). Kept so graves saved by an older
    // version still get fully cleaned up.
    public UUID legacyDisplayUuid;
    public UUID legacyNameTagUuid;

    public ResourceKey<Level> dimension;
    // final block position (the block the entities sit in)
    public int x, y, z;

    // Transient runtime state (not persisted).
    public transient int shakeTicks;
    public transient boolean platformPlaced;

    public Grave() {
        this.id = UUID.randomUUID();
    }

    public Identifier dimensionId() {
        return dimension.identifier();
    }

    // serialization
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

    // compressed nbt as base64, for storage inside the json file
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
