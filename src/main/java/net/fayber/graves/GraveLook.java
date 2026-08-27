package net.fayber.graves;

import com.mojang.authlib.GameProfile;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.joml.Quaternionf;

import java.util.List;

/**
 * The four grave designs a death can spawn from, all built from vanilla
 * display entities so a client without this mod still renders the whole
 * thing (see {@link GraveDisplayBuilder}).
 *
 * Geometry and the tuning constants below are exactly what was approved after
 * prototyping in the separate Grave Look Lab scratch mod
 * ({@code Minecraft Mods/Grave Look Lab}), which stays around as a sketchbook
 * for the next design pass. See its README and the
 * {@code mc-display-entity-geometry} brain note for how each number was
 * derived and which renderer quirks it works around.
 *
 * Which of these four spawns for a given death is decided by
 * {@link GraveConfig#enabledLooks()}; ids below are also the config keys
 * ({@code grave_look_<id>}) and command values.
 */
public enum GraveLook {

    /** Upright deepslate headstone, dirt mound, half-buried head. */
    DEEPSLATE_GRAVE("deepslate_grave") {
        @Override
        public void build(GraveDisplayBuilder b, GameProfile owner) {
            BlockState stone = Blocks.COBBLED_DEEPSLATE.defaultBlockState();
            BlockState plinth = Blocks.POLISHED_DEEPSLATE.defaultBlockState();

            // Footing the stone sits on, slightly wider than the stone itself
            // so the silhouette does not read as a floating rectangle.
            b.block(plinth, 0.0, 0.00, -0.28, 1.00, 0.14, 0.44);
            // Main slab.
            b.block(stone, 0.0, 0.14, -0.28, 0.86, 0.92, 0.16);
            // Narrower cap, which at any real viewing distance reads as an arched top.
            b.block(stone, 0.0, 1.06, -0.28, 0.62, 0.10, 0.16);

            mound(b, Blocks.COARSE_DIRT.defaultBlockState());
            buriedHead(b, owner);
            engrave(b, owner, centreText(STONE_TEXT_CENTRE), STONE_FRONT_Z);
        }
    },

    /** Wooden cross instead of a slab, for a rougher field-burial look. */
    WOODEN_CROSS("wooden_cross") {
        @Override
        public void build(GraveDisplayBuilder b, GameProfile owner) {
            BlockState wood = Blocks.STRIPPED_SPRUCE_WOOD.defaultBlockState();

            b.block(Blocks.COBBLESTONE.defaultBlockState(), 0.0, 0.00, -0.28, 0.44, 0.10, 0.36);
            // Upright.
            b.block(wood, 0.0, 0.10, -0.28, 0.17, 1.15, 0.15);
            // Crossbar.
            b.block(wood, 0.0, 0.80, -0.28, 0.64, 0.16, 0.14);

            mound(b, Blocks.COARSE_DIRT.defaultBlockState());
            buriedHead(b, owner);
            // On the crossbar, not the upright: the post is only 0.17 wide, so
            // a name centred on it would hang off both sides into empty air.
            engrave(b, owner, centreText(0.88), -0.28 + 0.07);
        }
    },

    /** Full-length sarcophagus with the name engraved flat on the lid. */
    DEEPSLATE_TOMBSTONE("deepslate_tombstone") {
        @Override
        public void build(GraveDisplayBuilder b, GameProfile owner) {
            BlockState base = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
            BlockState lid = Blocks.CHISELED_DEEPSLATE.defaultBlockState();

            // Body of the tomb, running front to back.
            b.block(base, 0.0, 0.00, 0.05, 1.00, 0.30, 1.60);
            // Inset lid.
            b.block(lid, 0.0, 0.30, 0.05, 0.88, 0.14, 1.48);
            // Small upright headboard at the far end.
            b.block(base, 0.0, 0.44, -0.70, 0.72, 0.46, 0.12);

            // The only head that is not half-buried: there is no soil here, it
            // sits squarely on the stone lid.
            restingHead(b, owner, 0.0, 0.44, -0.35);

            // Name lies flat on the lid rather than standing up on a face.
            // Tipped back with lidRotation(), the text grows toward -Z, hence
            // centring adds rather than subtracts a half line.
            b.engravedText(nameText(owner),
                    0.0, 0.44 + TEXT_LIFT, 0.05 + textHalfLine(),
                    TEXT_SCALE, lidRotation());
        }
    },

    /** Overgrown mossy grave lit by a soul lantern. */
    SOULGRAVE("soulgrave") {
        @Override
        public void build(GraveDisplayBuilder b, GameProfile owner) {
            BlockState stone = Blocks.MOSSY_COBBLESTONE.defaultBlockState();

            b.block(Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), 0.0, 0.00, -0.28, 1.00, 0.14, 0.44);
            b.block(stone, 0.0, 0.14, -0.28, 0.86, 0.92, 0.16);
            b.block(stone, 0.0, 1.06, -0.28, 0.62, 0.10, 0.16);

            mound(b, Blocks.PODZOL.defaultBlockState());
            buriedHead(b, owner);
            engrave(b, owner, centreText(STONE_TEXT_CENTRE), STONE_FRONT_Z);

            // A light source makes the grave findable at night without a
            // beacon beam, and soul fire blue reads as "grave" rather than
            // "campsite".
            b.block(Blocks.SOUL_LANTERN.defaultBlockState(), 0.62, 0.00, -0.10, 0.34, 0.42, 0.34);
        }
    };

    // ------------------------------------------------------------------
    // Tuned constants (Grave Look Lab 0.1.2 defaults, approved as-is).
    // ------------------------------------------------------------------

    /**
     * Local Z of the front face of a standard headstone: its centre plus half
     * its thickness. Text clearance on top of this comes from {@link #TEXT_LIFT}.
     */
    private static final double STONE_FRONT_Z = -0.28 + 0.08;

    /**
     * Height an engraved name is centred on for an upright stone. The slab
     * spans 0.14 to 1.06 (mid 0.60); sitting the name in the upper third
     * instead leaves the lower half clear for the mound and head in front of
     * it, the way a real headstone carries its inscription up top.
     */
    private static final double STONE_TEXT_CENTRE = 0.74;

    /** Local Y of the top of the mound, where a head would rest unburied. */
    private static final double MOUND_TOP = 0.26;

    /** Height of a player head item model at transformation scale 1. */
    private static final double HEAD_MODEL_HEIGHT = 0.5;

    private static final double HEAD_SCALE = 0.55;

    /** Fraction of its own height a mound head is pushed into the soil. */
    private static final double HEAD_SINK = 0.42;

    /** Degrees the face is tipped up out of the ground. */
    private static final double HEAD_TILT_DEG = 24.0;

    /** Degrees the head is rolled off vertical, so it isn't square to any axis. */
    private static final double HEAD_ROLL_DEG = 15.0;

    private static final double TEXT_SCALE = 0.40;

    /** Clearance between an engraved name and the surface behind it. */
    private static final double TEXT_LIFT = 0.012;

    /**
     * World height of one line of display text at transformation scale 1.
     * Text is bottom-anchored, not centred, so every call site below
     * positions by centre and subtracts half of this.
     */
    private static final double TEXT_LINE_HEIGHT = 0.225;

    private static double textHalfLine() {
        return TEXT_LINE_HEIGHT * TEXT_SCALE / 2.0;
    }

    /** Placement point that leaves one line of text centred on {@code centre}. */
    private static double centreText(double centre) {
        return centre - textHalfLine();
    }

    private final String id;

    GraveLook(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Spawns this design's pieces. Implementations only call into {@code b}. */
    public abstract void build(GraveDisplayBuilder b, GameProfile owner);

    public static GraveLook byId(String id) {
        for (GraveLook look : values()) {
            if (look.id.equalsIgnoreCase(id)) {
                return look;
            }
        }
        return null;
    }

    /**
     * Spawns a complete grave of this design at the given block-centre anchor
     * and returns every entity it created, so the caller can record their
     * UUIDs for later cleanup.
     *
     * @param yaw direction the grave's front should face
     */
    public List<Entity> spawn(Level level, double x, double y, double z, float yaw, GameProfile owner) {
        GraveDisplayBuilder b = new GraveDisplayBuilder(level, x, y, z, yaw);
        build(b, owner);
        return b.spawned();
    }

    // ------------------------------------------------------------------
    // Shared parts
    // ------------------------------------------------------------------

    /** Two-tier heap of disturbed earth in front of the stone. */
    private static void mound(GraveDisplayBuilder b, BlockState soil) {
        b.block(soil, 0.0, 0.00, 0.30, 0.92, 0.16, 0.86);
        b.block(soil, 0.0, 0.16, 0.30, 0.70, 0.10, 0.64);
    }

    /**
     * The owner's head pushed down into the mound and knocked off square, so
     * it reads as something half-dug-up rather than an ornament set on a
     * shelf. Keeps the plan position of a resting head; only height and
     * orientation change.
     */
    private static void buriedHead(GraveDisplayBuilder b, GameProfile owner) {
        double height = HEAD_MODEL_HEIGHT * HEAD_SCALE;
        double centre = MOUND_TOP + height / 2.0 - height * HEAD_SINK;
        b.item(GraveDisplayBuilder.playerHead(owner), 0.0, centre, 0.30, HEAD_SCALE, headTilt());
    }

    /**
     * A head sitting squarely on a surface, for the tomb lid where there is
     * no soil to sink into.
     *
     * A player head model is half a block tall and centred on its origin, so
     * its centre has to be lifted by a quarter block times its own scale to
     * leave it on the surface rather than through it.
     */
    private static void restingHead(GraveDisplayBuilder b, GameProfile owner, double cx, double surfaceY, double cz) {
        double restY = surfaceY + HEAD_MODEL_HEIGHT / 2.0 * HEAD_SCALE;
        b.item(GraveDisplayBuilder.playerHead(owner), cx, restY, cz, HEAD_SCALE, new Quaternionf());
    }

    /**
     * Tilt of a half-buried head: tipped back so the face angles up out of
     * the soil, then rolled so it is not square to any axis. Two axes on
     * purpose, one alone still reads as deliberately placed.
     */
    private static Quaternionf headTilt() {
        return new Quaternionf()
                .rotateX((float) Math.toRadians(-HEAD_TILT_DEG))
                .rotateZ((float) Math.toRadians(HEAD_ROLL_DEG));
    }

    /** Name carved onto the front face of an upright stone. */
    private static void engrave(GraveDisplayBuilder b, GameProfile owner, double height, double faceZ) {
        b.engravedText(nameText(owner), 0.0, height, faceZ + TEXT_LIFT, TEXT_SCALE, new Quaternionf());
    }

    /**
     * Orientation for a name lying flat on a horizontal surface: tipping the
     * text a quarter turn back aims its face at the sky and swings the top of
     * the glyphs away from the foot of the tomb, which is what makes it read
     * right way up for someone standing there.
     */
    private static Quaternionf lidRotation() {
        return new Quaternionf().rotateX((float) (-Math.PI / 2.0));
    }

    private static Component nameText(GameProfile owner) {
        return Component.literal(owner.name()).withStyle(ChatFormatting.WHITE);
    }

    /** Comma-separated ids, for command feedback. */
    public static String idList() {
        StringBuilder sb = new StringBuilder();
        for (GraveLook look : values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(look.id);
        }
        return sb.toString();
    }
}
