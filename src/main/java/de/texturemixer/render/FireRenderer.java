package de.texturemixer.render;

/**
 * Rendert einen Netherrack-Block mit Flamme darüber (zwei gekreuzte Ebenen) als animierte Bildfolge.
 * Rein Java (Software-Renderer), keine Minecraft-Klassen. Ausgabe: FRAME x FRAME pro Bild.
 */
public final class FireRenderer {
    public static final int FRAME = 64;
    private static final double YAW = Math.toRadians(35);
    private static final double PITCH = Math.toRadians(26);

    private FireRenderer() {}

    /**
     * @param fire0   fire_0 (senkrechter Animationsstreifen aus quadratischen Bildern)
     * @param fire1   fire_1 (darf null sein, dann wird fire_0 genutzt)
     * @param nether  Netherrack-Textur (quadratisch, ggf. Streifen)
     * @param frames  Reihenfolge der Animationsbilder (Index in den Streifen)
     */
    public static int[] render(Raster.Tex fire0, Raster.Tex fire1, Raster.Tex nether, int[] frames) {
        if (fire1 == null) fire1 = fire0;
        double[] model = Mat.mul(Mat.rotX(PITCH), Mat.rotY(YAW), Mat.translate(0, -16, 0));

        // Rahmen für die Skalierung aus der Geometrie bestimmen
        double[][] pts = {
                {-8, 0, -8}, {8, 0, -8}, {8, 0, 8}, {-8, 0, 8}, {-8, 32, -8}, {8, 32, -8}, {8, 32, 8}, {-8, 32, 8}};
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        for (double[] p : pts) {
            double[] q = Mat.apply(model, p);
            minX = Math.min(minX, q[0]);
            maxX = Math.max(maxX, q[0]);
            minY = Math.min(minY, q[1]);
            maxY = Math.max(maxY, q[1]);
        }
        double margin = 3;
        double sc = Math.min((FRAME - 2 * margin) / (maxX - minX), (FRAME - 2 * margin) / (maxY - minY));
        double cx = FRAME / 2.0 - (minX + maxX) / 2.0 * sc;
        double cy = FRAME / 2.0 + (minY + maxY) / 2.0 * sc;

        int nw = nether.w();
        int nfw = Math.min(nether.w(), nether.h()); // erstes Quadrat bei Animationsstreifen
        double[] nrect = {0, 0, nfw, nfw};
        double[][] nuv = {{0, 0}, {nfw, 0}, {nfw, nfw}, {0, nfw}};

        // Block: Oberseite, Vorderseite (+z), rechte Seite (+x), links, hinten, unten
        double[][][] faces = {
                {{-8, 16, -8}, {8, 16, -8}, {8, 16, 8}, {-8, 16, 8}},   // oben
                {{-8, 16, 8}, {8, 16, 8}, {8, 0, 8}, {-8, 0, 8}},       // +z
                {{8, 16, 8}, {8, 16, -8}, {8, 0, -8}, {8, 0, 8}},       // +x
                {{-8, 16, -8}, {-8, 16, 8}, {-8, 0, 8}, {-8, 0, -8}},   // -x
                {{8, 16, -8}, {-8, 16, -8}, {-8, 0, -8}, {8, 0, -8}},   // -z
                {{-8, 0, 8}, {8, 0, 8}, {8, 0, -8}, {-8, 0, -8}}        // unten
        };
        double[] shades = {1.0, 0.85, 0.7, 0.7, 0.8, 0.5};

        int n = frames.length;
        int[] out = new int[FRAME * FRAME * n];
        Raster raster = new Raster(FRAME, FRAME, 2);
        for (int f = 0; f < n; f++) {
            raster.clear();
            for (int i = 0; i < faces.length; i++) {
                raster.quad(project(model, faces[i], sc, cx, cy), nuv, nrect, nether, shades[i]);
            }
            drawFire(raster, fire0, frames[f], model, sc, cx, cy, true);
            drawFire(raster, fire1, frames[f], model, sc, cx, cy, false);
            raster.resolveInto(out, f);
        }
        return out;
    }

    private static void drawFire(Raster raster, Raster.Tex t, int frame, double[] model,
                                 double sc, double cx, double cy, boolean planeX) {
        int fw = t.w();
        int count = Math.max(1, t.h() / fw);
        int k = ((frame % count) + count) % count;
        double[] rect = {0, k * fw, fw, fw};
        double[][] uv = {{0, k * fw}, {fw, k * fw}, {fw, (k + 1) * fw}, {0, (k + 1) * fw}};
        double[][] quad = planeX
                ? new double[][]{{-8, 32, 0}, {8, 32, 0}, {8, 16, 0}, {-8, 16, 0}}
                : new double[][]{{0, 32, -8}, {0, 32, 8}, {0, 16, 8}, {0, 16, -8}};
        raster.quad(project(model, quad, sc, cx, cy), uv, rect, t, 1.0);
    }

    private static double[][] project(double[] model, double[][] pts, double sc, double cx, double cy) {
        double[][] s = new double[4][];
        for (int i = 0; i < 4; i++) {
            double[] q = Mat.apply(model, pts[i]);
            s[i] = new double[]{q[0] * sc + cx, cy - q[1] * sc, q[2]};
        }
        return s;
    }
}
