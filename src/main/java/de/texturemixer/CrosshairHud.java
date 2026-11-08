package de.texturemixer;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;

/**
 * Zeichnet das eigene Fadenkreuz direkt nach dem Vanilla-Fadenkreuz.
 *
 * Warum kein Texturepack? Minecraft zeichnet sein Fadenkreuz mit einem Invertier-Blend (Farbe = Gegenfarbe des
 * Hintergrunds). Eine gewählte Farbe würde damit je nach Untergrund anders aussehen. Hier wird normal gezeichnet,
 * die Farben stimmen also genau. Das Vanilla-Fadenkreuz wird im Ausgabe-Pack durch ein durchsichtiges Bild ersetzt
 * (siehe PackScanner), die Angriffsanzeige bleibt dadurch erhalten.
 */
public final class CrosshairHud {
    private CrosshairHud() {}

    public static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
                Identifier.of(TextureMixerClient.MOD_ID, "custom_crosshair"), CrosshairHud::render);
    }

    private static void render(DrawContext ctx, RenderTickCounter tick) {
        if (!CustomCrosshair.active()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !mc.options.getPerspective().isFirstPerson()) return;

        CustomCrosshair cc = MixerConfig.get().crosshair;
        int size = CustomCrosshair.N * cc.scale;
        int x = (ctx.getScaledWindowWidth() - size) / 2;
        int y = (ctx.getScaledWindowHeight() - size) / 2;
        draw(ctx, x, y, cc.pixels, cc.scale);
    }

    /** Zeichnet ein 15x15-Bild (ARGB) an x/y, jeder Pixel ist scale x scale groß. Waagerechte gleiche Pixel werden zusammengefasst. */
    public static void draw(DrawContext ctx, int x, int y, int[] p, int scale) {
        int n = CustomCrosshair.N;
        for (int cy = 0; cy < n; cy++) {
            int cx = 0;
            while (cx < n) {
                int c = p[cy * n + cx];
                if ((c >>> 24) == 0) {
                    cx++;
                    continue;
                }
                int end = cx + 1;
                while (end < n && p[cy * n + end] == c) end++;
                ctx.fill(x + cx * scale, y + cy * scale, x + end * scale, y + (cy + 1) * scale, c);
                cx = end;
            }
        }
    }
}
