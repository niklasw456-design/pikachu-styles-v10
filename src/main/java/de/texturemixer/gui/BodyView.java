package de.texturemixer.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Zeichnet den eigenen Spieler-Skin mit angezogener Rüstung aus einem Pack, sich drehend (um die Hochachse).
 * Alles läuft über einzelne Texturausschnitte (kein Pixel-Zugriff auf den Skin nötig): Jedes Körperteil ist
 * ein Quader, von dem pro Bild nur die zwei dem Betrachter zugewandten Seiten gezeichnet werden. Die Seiten
 * werden waagerecht gestaucht (Matrix-Skalierung), das ergibt eine saubere Drehung ohne 3D-Engine.
 *
 * Maße in Skin-Pixeln: Figur 16 x 32 (Kopf 8x8x8, Körper 8x12x4, Arme 4x12x4, Beine 4x12x4).
 * Z = GUI-Pixel pro Skin-Pixel.
 */
final class BodyView {
    static final int Z = 2;
    /** Breite des Zeichenbereichs (Platz für Drehung und aufgeblähte Rüstung). */
    static final int W = 22 * Z;
    /** Höhe: 32 Skin-Pixel plus je 1 Pixel Rand für die aufgeblähte Rüstung. */
    static final int H = 34 * Z;
    /** Umlaufzeit in Millisekunden. */
    private static final long PERIOD_MS = 16000L;

    private static final Identifier STEVE = Identifier.of("minecraft", "textures/entity/player/wide/steve.png");

    private BodyView() {}

    /** Quelle der Textur einer Ebene. */
    private enum Src { SKIN, ARMOR_OUTER, ARMOR_LEGS }

    /** Eine Ebene eines Körperteils: Textur-Ursprung (u, v), Aufblähung in Skin-Pixeln, Spiegelung (linke Rüstungsglieder). */
    private record Layer(Src src, int u, int v, float inflate, boolean mirror) {}

    /** Ein Körperteil: Mitte (cx), Oberkante (top), Halbbreite/-tiefe, Höhe, Texturmaße w/h/d und die Ebenen von innen nach außen. */
    private record Part(float cx, float top, int w, int h, int d, List<Layer> layers) {}

    /** Skin-Textur des Spielers, ohne Welt Steve. */
    private static Identifier skin() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                return mc.player.getSkin().body().texturePath();
            }
        } catch (Throwable ignored) {
            // fällt auf Steve zurück
        }
        return STEVE;
    }

    /** Schmale Arme (Alex)? Wird über den Modelltyp bzw. den Pfad der Skin-Textur erkannt. */
    private static boolean slim(Identifier skin) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                Object model = mc.player.getSkin().model();
                if (model != null && model.toString().toLowerCase().contains("slim")) return true;
            }
        } catch (Throwable ignored) {
            // weiter mit dem Pfad
        }
        return skin.getPath().contains("/slim/");
    }

    /** Skin, zweite Skin-Ebene, optional Hosen-Ebene, Rüstung außen (von innen nach außen). */
    private static List<Layer> layers(int su, int sv, int ou, int ov, float oInfl,
                                      boolean legsLayer, int au, int av, boolean armorMirror) {
        List<Layer> l = new ArrayList<>();
        l.add(new Layer(Src.SKIN, su, sv, 0f, false));
        l.add(new Layer(Src.SKIN, ou, ov, oInfl, false));
        if (legsLayer) l.add(new Layer(Src.ARMOR_LEGS, au, av, 0.5f, armorMirror));
        l.add(new Layer(Src.ARMOR_OUTER, au, av, 1.0f, armorMirror));
        return l;
    }

    /** Die sechs Körperteile. Arme sind bei schmalem Skin 3 Pixel breit. */
    private static List<Part> parts(boolean slim) {
        int aw = slim ? 3 : 4;
        float armCx = slim ? 5.5f : 6f;
        List<Part> p = new ArrayList<>();
        // Kopf: Skin (0,0), Hut (32,0, 0.5), Helm (0,0)
        p.add(new Part(0f, 0f, 8, 8, 8,
                layers(0, 0, 32, 0, 0.5f, false, 0, 0, false)));
        // Körper: Skin (16,16), Jacke (16,32), Hose-Bund + Brustplatte (16,16)
        p.add(new Part(0f, 8f, 8, 12, 4,
                layers(16, 16, 16, 32, 0.25f, true, 16, 16, false)));
        // Rechter Arm (links im Bild), linker Arm (rechts im Bild; Rüstung gespiegelt vom rechten Arm)
        p.add(new Part(-armCx, 8f, aw, 12, 4, armLayers(40, 16, 40, 32, false)));
        p.add(new Part(armCx, 8f, aw, 12, 4, armLayers(32, 48, 48, 48, true)));
        // Beine
        p.add(new Part(-2f, 20f, 4, 12, 4,
                layers(0, 16, 0, 32, 0.25f, true, 0, 16, false)));
        p.add(new Part(2f, 20f, 4, 12, 4,
                layers(16, 48, 0, 48, 0.25f, true, 0, 16, true)));
        return p;
    }

    private static List<Layer> armLayers(int su, int sv, int ou, int ov, boolean mirror) {
        List<Layer> l = new ArrayList<>();
        l.add(new Layer(Src.SKIN, su, sv, 0f, false));
        l.add(new Layer(Src.SKIN, ou, ov, 0.25f, false));
        l.add(new Layer(Src.ARMOR_OUTER, 40, 16, 1.0f, mirror));
        return l;
    }

    /** x, y = linke obere Ecke des Zeichenbereichs (W x H GUI-Pixel). */
    static void draw(DrawContext ctx, int x, int y, PreviewCache.Body body) {
        Identifier skin = skin();
        boolean slim = slim(skin);
        List<Part> parts = parts(slim);

        // Gleichmäßig langsame Drehung, leicht schräg gestartet (35 Grad), damit man gleich Seite und Front sieht
        double yaw = (System.currentTimeMillis() % PERIOD_MS) / (double) PERIOD_MS * Math.PI * 2.0 + Math.toRadians(35);
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);

        // Hinten zuerst zeichnen: Tiefe der Teilmitte nach der Drehung (kleiner = weiter weg)
        List<Part> sorted = new ArrayList<>(parts);
        sorted.sort((a, b) -> Double.compare(-a.cx() * sin, -b.cx() * sin));

        float originX = x + W / 2f;      // Drehachse
        float originY = y + Z;           // Skin-Pixel y = 0 (1 Pixel Rand oben)

        // Weicher Bodenschatten unter den Füßen (Ellipse aus drei Streifen)
        int cxs = Math.round(originX);
        int fy = Math.round(originY + 32 * Z) + 1;
        ctx.fill(cxs - 9 * Z, fy, cxs + 9 * Z, fy + 1, 0x22000000);
        ctx.fill(cxs - 7 * Z, fy + 1, cxs + 7 * Z, fy + 2, 0x33000000);
        ctx.fill(cxs - 5 * Z, fy + 2, cxs + 5 * Z, fy + 3, 0x22000000);

        for (Part part : sorted) {
            for (Layer layer : part.layers()) {
                drawLayer(ctx, skin, body, part, layer, cos, sin, originX, originY, slim);
            }
        }
    }

    /**
     * Zeichnet die sichtbaren Seiten einer Ebene. Rechter Spieler-Arm = links im Bild.
     * Ebenen-Quader haben die Halbmaße (w/2 + Aufblähung) x (d/2 + Aufblähung).
     */
    private static void drawLayer(DrawContext ctx, Identifier skin, PreviewCache.Body body, Part part, Layer layer,
                                  double cos, double sin, float originX, float originY, boolean slim) {
        PreviewCache.Tex tex;
        Identifier id;
        int texW, texH, m;
        switch (layer.src()) {
            case SKIN -> {
                id = skin;
                texW = 64;
                texH = 64;
                m = 1;
            }
            case ARMOR_OUTER -> {
                tex = body.outer();
                if (tex == null) return;
                id = tex.id();
                texW = tex.texW();
                texH = tex.texH();
                m = Math.max(1, tex.grid() / 64) * tex.scale();
            }
            default -> {
                tex = body.legs();
                if (tex == null) return;
                id = tex.id();
                texW = tex.texW();
                texH = tex.texH();
                m = Math.max(1, tex.grid() / 64) * tex.scale();
            }
        }

        // Rüstungsarme sind immer 4 breit, auch bei schmalem Skin; Skin-Ebenen nutzen die echte Breite
        boolean armor = layer.src() != Src.SKIN;
        int w = part.w();
        float cx = part.cx();
        if (armor && w == 3) {
            w = 4;
            cx = cx < 0 ? -6f : 6f;
        }
        int h = part.h();
        int d = part.d();
        float inf = layer.inflate();
        float hw = w / 2f + inf;
        float hd = d / 2f + inf;
        float top = part.top() - inf;
        float height = h + 2 * inf;

        // Kanten (links->rechts in Texturrichtung) für Vorderseite, Rückseite, rechte Seite (x < 0), linke Seite (x > 0)
        // Punkt (px, pz) -> Bildschirm-x = px * cos + pz * sin
        double[][] corners = {
                {-hw, hd, hw, hd},     // vorne
                {hw, -hd, -hw, -hd},   // hinten
                {-hw, -hd, -hw, hd},   // Seite x < 0
                {hw, hd, hw, -hd}      // Seite x > 0
        };
        // Sichtbarkeit: z-Anteil der Flächennormalen nach der Drehung
        double[] nz = {cos, -cos, sin, -sin};
        // Texturbereiche: vorne, hinten, Seite x < 0 ("rechts"), Seite x > 0 ("links")
        int[][] region = {
                {layer.u() + d, layer.v() + d, w, h},
                {layer.u() + 2 * d + w, layer.v() + d, w, h},
                {layer.u(), layer.v() + d, d, h},
                {layer.u() + d + w, layer.v() + d, d, h}
        };

        for (int f = 0; f < 4; f++) {
            if (nz[f] <= 0.0001) continue;
            int[] r = region[f];
            boolean flip = false;
            if (layer.mirror()) {
                // gespiegelter Quader: Seiten tauschen ihre Texturbereiche, jede Seite wird gespiegelt
                if (f == 2) r = region[3];
                else if (f == 3) r = region[2];
                flip = true;
            }
            // Teilmitte cx liegt seitlich der Körperachse und wird mitgedreht
            double ax = corners[f][0] * cos + corners[f][1] * sin + cx * cos;
            double bx = corners[f][2] * cos + corners[f][3] * sin + cx * cos;
            float sxa = originX + (float) (flip ? bx : ax) * Z;
            float sxb = originX + (float) (flip ? ax : bx) * Z;
            float sy = originY + top * Z;
            drawFace(ctx, id, r[0] * m, r[1] * m, r[2] * m, r[3] * m, sxa, sxb, sy, height * Z, texW, texH);
        }
    }

    /**
     * Eine Seite: Textur-links liegt bei xa, Textur-rechts bei xb (xb < xa = gespiegelt).
     * Gespiegelt wird über die UV-Koordinaten, nicht über eine negative Matrix-Skalierung: Das GUI-System
     * berechnet aus der Matrix die Grenzen des Bildes, bei negativer Breite werden Bilder verworfen
     * (dann fehlte die Rüstung an den gespiegelten Gliedern).
     */
    private static void drawFace(DrawContext ctx, Identifier id, int u, int v, int rw, int rh,
                                 float xa, float xb, float y, float hPx, int texW, int texH) {
        boolean flip = xb < xa;
        float left = flip ? xb : xa;
        float span = Math.abs(xb - xa);
        if (span < 0.05f) return;
        int baseW = 16;
        int baseH = Math.max(1, Math.round(hPx));
        var mat = ctx.getMatrices();
        mat.pushMatrix();
        mat.translate(left, y);
        mat.scale(span / baseW, hPx / baseH);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id, 0, 0,
                (float) (flip ? u + rw : u), (float) v, baseW, baseH, flip ? -rw : rw, rh, texW, texH);
        mat.popMatrix();
    }
}
