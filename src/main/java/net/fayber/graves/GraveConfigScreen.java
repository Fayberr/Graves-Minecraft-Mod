package net.fayber.graves;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Hand-rolled ModMenu config screen. Every control writes through
 * {@link GraveConfig#set(String, String)}, which updates the in-memory config
 * and saves it to {@code config/graves.json}. In singleplayer the integrated
 * server shares the same static config, so changes apply live.
 */
public class GraveConfigScreen extends Screen {
    /** Slider top end, in seconds (0 means "never despawn"). */
    private static final int MAX_DESPAWN_SECONDS = 3600;

    private final Screen parent;

    public GraveConfigScreen(Screen parent) {
        super(Component.literal("Graves Configuration"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int spacing = 22;
        int startY = 25;

        this.addRenderableWidget(booleanButton("allow_robbing", "Allow Robbing", centerX, startY));
        this.addRenderableWidget(booleanButton("pick_up_xp", "Pick Up XP", centerX, startY + spacing));
        this.addRenderableWidget(booleanButton("allow_locating", "Allow Locating", centerX, startY + spacing * 2));
        this.addRenderableWidget(booleanButton("compatibility_mode", "Compatibility Mode", centerX, startY + spacing * 3));

        int despawn = GraveConfig.get().despawn_seconds;
        this.addRenderableWidget(new AbstractSliderButton(centerX - 100, startY + spacing * 4, 200, 20,
                despawnLabel(despawn), (double) despawn / (double) MAX_DESPAWN_SECONDS) {
            @Override
            protected void updateMessage() {
                this.setMessage(despawnLabel(GraveConfig.get().despawn_seconds));
            }

            @Override
            protected void applyValue() {
                int secs = (int) Math.round(this.value * MAX_DESPAWN_SECONDS);
                GraveConfig.set("despawn_seconds", String.valueOf(secs));
                this.setMessage(despawnLabel(secs));
            }
        });

        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), button ->
                this.minecraft.setScreen(this.parent))
                .bounds(centerX - 100, this.height - 30, 200, 20)
                .build());
    }

    /** A toggle that flips the named boolean config key and saves it. */
    private Button booleanButton(String key, String label, int centerX, int y) {
        boolean current = readBool(key);
        return Button.builder(toggleText(label, current), button -> {
            boolean next = !readBool(key);
            GraveConfig.set(key, String.valueOf(next));
            button.setMessage(toggleText(label, next));
        })
                .bounds(centerX - 100, y, 200, 20)
                .build();
    }

    private static boolean readBool(String key) {
        GraveConfig c = GraveConfig.get();
        return switch (key) {
            case "allow_robbing" -> c.allow_robbing;
            case "pick_up_xp" -> c.pick_up_xp;
            case "allow_locating" -> c.allow_locating;
            case "compatibility_mode" -> c.compatibility_mode;
            default -> false;
        };
    }

    private static Component toggleText(String prefix, boolean value) {
        return Component.literal(prefix + ": " + (value ? "ON" : "OFF"));
    }

    private static Component despawnLabel(int secs) {
        return Component.literal(secs <= 0 ? "Despawn After: never" : "Despawn After: " + secs + "s");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
