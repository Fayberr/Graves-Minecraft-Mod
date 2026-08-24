package net.fayber.graves;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Central registry of live graves plus the gameplay rules around them:
 * death capture, opening/restoring, robbing rules, despawning and persistence.
 *
 * Graves persist to {@code <world>/data/graves.json} so they survive restarts.
 */
public final class GraveManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** All known graves, keyed by grave id. */
    private static final Map<UUID, Grave> GRAVES = new HashMap<>();
    /** Entity UUID (any of the three spawned entities) to grave id. */
    private static final Map<UUID, UUID> ENTITY_INDEX = new HashMap<>();

    private static MinecraftServer server;

    private GraveManager() {}

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public static void onServerStarted(MinecraftServer srv) {
        server = srv;
        loadFromDisk();
        GraveConfig.onServerStarted(
                srv.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.REDUCED_DEBUG_INFO));
    }

    public static void onServerStopping(MinecraftServer srv) {
        saveToDisk(srv);
        server = null;
    }

    // ------------------------------------------------------------------
    // Death capture (ALLOW_DEATH event)
    // ------------------------------------------------------------------

    /**
     * Called before a living entity dies. When a player without keepInventory
     * dies we capture their inventory and XP into a grave; returning true lets
     * death proceed normally afterwards.
     */
    public static boolean onAllowDeath(net.minecraft.world.entity.LivingEntity entity, DamageSource source, float amount) {
        if (GraveConfig.get().compatibility_mode) return true;
        if (!(entity instanceof ServerPlayer player)) return true;
        if (!(player.level() instanceof ServerLevel level)) return true;

        if (keepInventory(level)) {
            return true; // Items stay with the player, nothing to do.
        }

        List<ItemStack> items = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (int slot = 0; slot <= 40; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty()) {
                items.add(stack.copy());
                slots.add(slot);
                inv.setItem(slot, ItemStack.EMPTY);
            }
        }
        int xp = 0;
        if (GraveConfig.get().pick_up_xp) {
            xp = player.totalExperience;
            if (xp > 0) {
                player.setExperienceLevels(0);
                player.setExperiencePoints(0);
                player.totalExperience = 0;
            }
        }
        if (items.isEmpty() && xp <= 0) {
            return true; // Nothing worth a grave.
        }

        Grave grave = new Grave();
        grave.ownerUuid = player.getUUID();
        grave.ownerName = player.getName().getString();
        grave.xpPoints = xp;
        grave.deathGameTime = level.getGameTime();
        grave.dimension = level.dimension();

        GraveSpawner.PlacementResult result =
                GraveSpawner.placeGrave(facade(level), player.getX(), player.getY(), player.getZ());
        grave.x = result.pos().getX();
        grave.y = result.pos().getY();
        grave.z = result.pos().getZ();
        grave.platformPlaced = result.platform();

        GraveSpawner.spawnEntities(level, result.pos(), grave);
        register(grave);
        saveToDisk();

        message(player, "Your grave was placed at " + grave.x + " " + grave.y + " " + grave.z
                + (xp > 0 ? " (it holds your XP)" : "") + ".");
        return true;
    }

    private static boolean keepInventory(ServerLevel level) {
        return Boolean.TRUE.equals(level.getGameRules()
                .get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY));
    }

    // ------------------------------------------------------------------
    // Interaction (use / attack on the grave hitbox)
    // ------------------------------------------------------------------

    /** Shared handler for UseEntityCallback and AttackEntityCallback. */
    public static InteractionResult onInteract(Player player, Level level, InteractionHand hand, Entity target) {
        if (level.isClientSide()) return InteractionResult.PASS;
        if (!target.entityTags().contains(GraveSpawner.ENTITY_TAG_GRAVE)) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) return InteractionResult.PASS;

        openGrave(sp, sl, target);
        return InteractionResult.SUCCESS;
    }

    private static void openGrave(ServerPlayer player, ServerLevel level, Entity graveEntity) {
        Grave grave = findGraveByEntity(graveEntity);
        if (grave == null) {
            // Stale hitbox with no backing data: clean it up.
            discardAll(level, graveEntity);
            message(player, "That grave lost its contents and crumbled away.");
            return;
        }

        boolean isOwner = player.getUUID().equals(grave.ownerUuid);
        boolean bypass = hasGraveKey(player);
        if (!GraveConfig.get().allow_robbing && !isOwner && !bypass) {
            message(player, grave.ownerName + "'s grave refuses to open for you.");
            startShake(grave);
            return;
        }

        // All-at-once, like the datapack: every item is handed back to the player
        // (preferred slot, then any free slot), and anything that doesn't fit is
        // dropped on the ground. Nothing is left in the grave.
        for (int i = 0; i < grave.items.size(); i++) {
            ItemStack stack = grave.items.get(i);
            if (!stack.isEmpty()) {
                insertIntoPlayer(player, grave.slots.get(i), stack);
            }
        }
        grave.items.clear();
        grave.slots.clear();

        if (GraveConfig.get().pick_up_xp && grave.xpPoints > 0) {
            player.giveExperiencePoints(grave.xpPoints);
            message(player, "Recovered " + grave.xpPoints + " experience points.");
            grave.xpPoints = 0;
        }

        destroyGrave(grave, false, false);
        message(player, "Grave fully recovered.");
    }

    /** Preferred slot first, then any free slot, otherwise drop at the feet. */
    private static void insertIntoPlayer(ServerPlayer player, int preferredSlot, ItemStack stack) {
        Inventory inv = player.getInventory();
        if (inv.getItem(preferredSlot).isEmpty()) {
            inv.setItem(preferredSlot, stack);
            return;
        }
        for (int slot = 0; slot <= 35; slot++) {
            if (inv.getItem(slot).isEmpty()) {
                inv.setItem(slot, stack);
                return;
            }
        }
        player.drop(stack, false);
    }

    /** True while the player holds a Grave Key anywhere in their hands. */
    public static boolean hasGraveKey(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            var custom = held.get(DataComponents.CUSTOM_DATA);
            if (custom != null
                    && custom.copyTag().getCompoundOrEmpty("graves").contains("grave_key")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Shake animation
    // ------------------------------------------------------------------

    private static void startShake(Grave grave) {
        grave.shakeTicks = 20;
    }

    private static void tickShake(Grave grave, ServerLevel level) {
        if (grave.shakeTicks <= 0 || grave.displayUuid == null) return;
        grave.shakeTicks--;
        Entity display = level.getEntity(grave.displayUuid);
        if (display == null) return;
        double baseX = grave.x + 0.5;
        double baseZ = grave.z + 0.5;
        double offset = grave.shakeTicks % 2 == 0 ? 0.05 : -0.05;
        if (grave.shakeTicks == 0) offset = 0;
        display.setPos(baseX + offset, grave.y, baseZ);
    }

    // ------------------------------------------------------------------
    // Tick (despawn timer + shake)
    // ------------------------------------------------------------------

    public static void tick(MinecraftServer srv) {
        if (GraveConfig.get().compatibility_mode || GRAVES.isEmpty()) return;
        long despawnTicks = GraveConfig.get().despawn_seconds * 20L;
        List<Grave> expired = null;
        for (Grave grave : GRAVES.values()) {
            ServerLevel level = srv.getLevel(grave.dimension);
            if (level == null) continue;
            if (grave.shakeTicks > 0) tickShake(grave, level);
            if (despawnTicks > 0 && level.getGameTime() - grave.deathGameTime >= despawnTicks) {
                if (expired == null) expired = new ArrayList<>();
                expired.add(grave);
            }
        }
        if (expired != null) {
            for (Grave grave : expired) {
                ServerLevel level = srv.getLevel(grave.dimension);
                if (level != null) {
                    destroyGrave(grave, true, false);
                    Player owner = level.getServer().getPlayerList()
                            .getPlayerByName(grave.ownerName);
                    if (owner != null) {
                        message((ServerPlayer) owner, "Your grave at " + grave.x + " "
                                + grave.y + " " + grave.z + " decayed and spilled its contents.");
                    }
                } else {
                    GRAVES.remove(grave.id);
                }
            }
            saveToDisk();
        }
    }

    // ------------------------------------------------------------------
    // Destruction
    // ------------------------------------------------------------------

    /**
     * Removes the grave. When {@code spill} is true the remaining contents are
     * dropped as ground items and XP orbs (used by the despawn timer). When
     * {@code notify} is set the owner gets a chat note.
     */
    public static void destroyGrave(Grave grave, boolean spill, boolean notify) {
        ServerLevel level = server != null ? server.getLevel(grave.dimension) : null;
        if (level != null) {
            if (spill) {
                double cx = grave.x + 0.5;
                double cy = grave.y + 0.5;
                double cz = grave.z + 0.5;
                for (ItemStack stack : grave.items) {
                    if (!stack.isEmpty()) {
                        level.addFreshEntity(new ItemEntity(level, cx, cy, cz, stack));
                    }
                }
                spillXp(level, grave);
            }
            for (UUID uuid : new UUID[]{grave.interactionUuid, grave.displayUuid, grave.nameTagUuid}) {
                if (uuid == null) continue;
                Entity entity = level.getEntity(uuid);
                if (entity != null) entity.discard();
            }
        }
        unregister(grave.id);
        saveToDisk();
        if (notify && server != null) {
            ServerPlayer owner = server.getPlayerList().getPlayer(grave.ownerUuid);
            if (owner != null) {
                message(owner, "Your grave at " + grave.x + " " + grave.y + " " + grave.z
                        + (spill ? " broke apart and dropped its contents." : " was recovered."));
            }
        }
    }

    private static void spillXp(ServerLevel level, Grave grave) {
        if (grave.xpPoints > 0) {
            level.addFreshEntity(new ExperienceOrb(level,
                    grave.x + 0.5, grave.y + 0.5, grave.z + 0.5, grave.xpPoints));
        }
    }

    private static void discardAll(ServerLevel level, Entity graveEntity) {
        graveEntity.discard();
        Grave grave = null;
        UUID gid = ENTITY_INDEX.get(graveEntity.getUUID());
        if (gid != null) grave = GRAVES.get(gid);
        if (grave != null) {
            for (UUID uuid : new UUID[]{grave.interactionUuid, grave.displayUuid, grave.nameTagUuid}) {
                if (uuid == null || uuid.equals(graveEntity.getUUID())) continue;
                Entity other = level.getEntity(uuid);
                if (other != null) other.discard();
            }
            unregister(grave.id);
        }
    }

    // ------------------------------------------------------------------
    // Registry helpers
    // ------------------------------------------------------------------

    private static void register(Grave grave) {
        GRAVES.put(grave.id, grave);
        indexEntity(grave.interactionUuid, grave.id);
        indexEntity(grave.displayUuid, grave.id);
        indexEntity(grave.nameTagUuid, grave.id);
    }

    private static void indexEntity(UUID entityUuid, UUID graveId) {
        if (entityUuid != null) ENTITY_INDEX.put(entityUuid, graveId);
    }

    private static void unregister(UUID graveId) {
        Grave grave = GRAVES.remove(graveId);
        if (grave != null) {
            ENTITY_INDEX.remove(grave.interactionUuid);
            ENTITY_INDEX.remove(grave.displayUuid);
            ENTITY_INDEX.remove(grave.nameTagUuid);
        }
    }

    public static Grave findGraveByEntity(Entity entity) {
        UUID gid = ENTITY_INDEX.get(entity.getUUID());
        return gid != null ? GRAVES.get(gid) : null;
    }

    /** All graves owned by the given player, nearest first relative to origin. */
    public static List<Grave> gravesOf(UUID ownerUuid) {
        List<Grave> out = new ArrayList<>();
        for (Grave grave : GRAVES.values()) {
            if (grave.ownerUuid != null && grave.ownerUuid.equals(ownerUuid)) out.add(grave);
        }
        return out;
    }

    public static List<Grave> allGraves() {
        return new ArrayList<>(GRAVES.values());
    }

    /** Nearest grave to the position within one dimension, or null. */
    public static Grave nearestGrave(ResourceKey<Level> dimension, BlockPos origin, UUID ownerFilter, boolean anyOwner) {
        Grave best = null;
        double bestDist = Double.MAX_VALUE;
        for (Grave grave : GRAVES.values()) {
            if (!grave.dimension.equals(dimension)) continue;
            if (!anyOwner && ownerFilter != null
                    && !grave.ownerUuid.equals(ownerFilter)) continue;
            double dist = Math.pow(grave.x + 0.5 - origin.getX(), 2)
                    + Math.pow(grave.y + 0.5 - origin.getY(), 2)
                    + Math.pow(grave.z + 0.5 - origin.getZ(), 2);
            if (dist < bestDist) {
                bestDist = dist;
                best = grave;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Persistence (JSON file with base64 NBT blobs)
    // ------------------------------------------------------------------

    private static Path storageFile(MinecraftServer srv) {
        return srv.getWorldPath(LevelResource.ROOT).resolve("data").resolve("graves.json");
    }

    public static void saveToDisk(MinecraftServer srv) {
        saveToDisk();
    }

    private static void saveToDisk() {
        if (server == null) return;
        try {
            Path file = storageFile(server);
            Files.createDirectories(file.getParent());
            HolderLookup.Provider registries = server.registryAccess();
            JsonArray arr = new JsonArray();
            for (Grave grave : GRAVES.values()) {
                String b64 = grave.toBase64(registries);
                if (!b64.isEmpty()) arr.add(b64);
            }
            JsonObject root = new JsonObject();
            root.add("graves", arr);
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            GravesMod.LOGGER.error("[Graves] Failed to save graves", e);
        }
    }

    private static void loadFromDisk() {
        if (server == null) return;
        GRAVES.clear();
        ENTITY_INDEX.clear();
        Path file = storageFile(server);
        if (!Files.exists(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("graves");
            HolderLookup.Provider registries = server.registryAccess();
            if (arr != null) {
                for (var element : arr) {
                    Grave grave = Grave.fromBase64(registries, element.getAsString());
                    if (grave != null) register(grave);
                }
            }
            GravesMod.LOGGER.info("[Graves] Loaded {} grave(s) from disk.", GRAVES.size());
        } catch (Exception e) {
            GravesMod.LOGGER.error("[Graves] Failed to load graves", e);
        }
    }

    // ------------------------------------------------------------------
    // World facade for the placement state machine
    // ------------------------------------------------------------------

    private static GraveSpawner.WorldFacade facade(ServerLevel level) {
        return new GraveSpawner.WorldFacade() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                return level.getBlockState(pos);
            }

            @Override
            public void setBlock(BlockPos pos, BlockState state) {
                level.setBlock(pos, state, 3);
            }

            @Override
            public int getHeight(Heightmap.Types type, int x, int z) {
                return level.getHeight(type, x, z);
            }

            @Override
            public boolean isInWorldAndLoaded(BlockPos pos) {
                return pos.getY() >= level.getMinY()
                        && pos.getY() <= level.getMaxY()
                        && level.isLoaded(pos);
            }

            @Override
            public List<Entity> getRepellingEntities(AABB box) {
                return level.getEntitiesOfClass(Entity.class, box,
                        e -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                .wrapAsHolder(e.getType()).is(GraveSpawner.TAG_REPELLING_ENTITY)
                                && !e.entityTags().contains(GraveSpawner.ENTITY_TAG_NON_REPELLING));
            }
        };
    }

    private static void message(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[Graves] " + text));
    }
}
