package de.texturemixer.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Rendert das Schild (Entity-Textur shield_base_nopattern, 64x64 bzw. ein Vielfaches) als langsam drehendes
 * 3D-Modell. Rein Java (Software-Renderer), keine Minecraft-Klassen. Ausgabe: senkrechter Streifen aus
 * FRAMES Bildern der Größe FRAME x FRAME (ARGB).
 *
 * Modell wie im Spiel: Platte 12 x 22 x 1 (Textur ab 0,0) und Griff 2 x 6 x 6 (Textur ab 26,0) auf der Rückseite.
 */
public final class ShieldRenderer {
    public static final int FRAME = 64;
    public static final int FRAMES = 48;
    /** Anzeigedauer pro Bild in Ticks: 48 x 4 = 192 Ticks = 9,6 Sekunden pro Umdrehung. */
    public static final int TICKS_PER_FRAME = 4;

    private static final double PITCH = Math.toRadians(12);
    /** Mittelpunkt des Modells (Platte z = 1..2, Griff z = -5..1), um den gedreht wird. */
    private static final double CENTER_Z = -1.5;

    private ShieldRenderer() {}

    private static final class Face {
        final double[][] pos = new double[4][];   // oben links, oben rechts, unten rechts, unten links
        final double[][] uv = new double[4][];
        final double[] rect = new double[4];
        final double[] normal;

        Face(double[][] corners, double ux, double uy, double uw, double uh, double[] normal) {
            for (int i = 0; i < 4; i++) pos[i] = corners[i];
            uv[0] = new double[]{ux, uy};
            uv[1] = new double[]{ux + uw, uy};
            uv[2] = new double[]{ux + uw, uy + uh};
            uv[3] = new double[]{ux, uy + uh};
            rect[0] = ux;
            rect[1] = uy;
            rect[2] = uw;
            rect[3] = uh;
            this.normal = normal;
        }
    }

    /** Quader um (cx, cy, cz) mit den Halbmaßen hx/hy/hz; Textur ab (u0, v0), Vorderseite = +z. */
    private static void cuboid(List<Face> out, double cx, double cy, double cz,
                               double hx, double hy, double hz, double u0, double v0) {
        double w = 2 * hx, h = 2 * hy, d = 2 * hz;
        out.add(new Face(shift(cx, cy, cz, new double[][]{{-hx, hy, hz}, {hx, hy, hz}, {hx, -hy, hz}, {-hx, -hy, hz}}),
                u0 + d, v0 + d, w, h, new double[]{0, 0, 1}));
        out.add(new Face(shift(cx, cy, cz, new double[][]{{hx, hy, -hz}, {-hx, hy, -hz}, {-hx, -hy, -hz}, {hx, -hy, -hz}}),
                u0 + 2 * d + w, v0 + d, w, h, new double[]{0, 0, -1}));
        out.add(new Face(shift(cx, cy, cz, new double[][]{{hx, hy, hz}, {hx, hy, -hz}, {hx, -hy, -hz}, {hx, -hy, hz}}),
                u0 + d + w, v0 + d, d, h, new double[]{1, 0, 0}));
        out.add(new Face(shift(cx, cy, cz, new double[][]{{-hx, hy, -hz}, {-hx, hy, hz}, {-hx, -hy, hz}, {-hx, -hy, -hz}}),
                u0, v0 + d, d, h, new double[]{-1, 0, 0}));
        out.add(new Face(shift(cx, cy, cz, new double[][]{{-hx, hy, -hz}, {hx, hy, -hz}, {hx, hy, hz}, {-hx, hy, hz}}),
                u0 + d, v0, w, d, new double[]{0, 1, 0}));
        out.add(new Face(shift(cx, cy, cz, new double[][]{{-hx, -hy, hz}, {hx, -hy, hz}, {hx, -hy, -hz}, {-hx, -hy, -hz}}),
                u0 + d + w, v0, w, d, new double[]{0, -1, 0}));
    }

    private static double[][] shift(double cx, double cy, double cz, double[][] pts) {
        double[][] r = new double[pts.length][];
        for (int i = 0; i < pts.length; i++) r[i] = new double[]{pts[i][0] + cx, pts[i][1] + cy, pts[i][2] + cz};
        return r;
    }

    public static int[] render(int[] argb, int tw, int th) {
        List<Face> faces = new ArrayList<>();
        cuboid(faces, 0, 0, 1.5, 6, 11, 0.5, 0, 0);    // Platte, Vorderseite bei z = 2
        cuboid(faces, 0, 0, -2, 1, 3, 3, 26, 0);       // Griff dahinter

        Raster.Tex tex = new Raster.Tex(argb, tw, th, 64, 64);

        double[][] mats = new double[FRAMES][];
        for (int f = 0; f < FRAMES; f++) {
            double a = 2 * Math.PI * f / FRAMES;
            mats[f] = Mat.mul(Mat.rotX(PITCH), Mat.rotY(a), Mat.translate(0, 0, -CENTER_Z));
        }

        // Ausschnitt aus allen Bildern bestimmen, damit die Größe beim Drehen gleich bleibt
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        for (double[] m : mats) {
            for (Face face : faces) {
                for (double[] c : face.pos) {
                    double[] q = Mat.apply(m, c);
                    minX = Math.min(minX, q[0]);
                    maxX = Math.max(maxX, q[0]);
                    minY = Math.min(minY, q[1]);
                    maxY = Math.max(maxY, q[1]);
                }
            }
        }
        double margin = 3;
        double sc = Math.min((FRAME - 2 * margin) / (maxX - minX), (FRAME - 2 * margin) / (maxY - minY));
        double cx = FRAME / 2.0 - (minX + maxX) / 2.0 * sc;
        double cy = FRAME / 2.0 + (minY + maxY) / 2.0 * sc;

        int[] out = new int[FRAME * FRAME * FRAMES];
        Raster raster = new Raster(FRAME, FRAME, 3);
        for (int f = 0; f < FRAMES; f++) {
            raster.clear();
            double[] m = mats[f];
            for (Face face : faces) {
                double[] n = Mat.apply(m, face.normal);
                // reine Drehung: Verschiebung (m[3], m[7], m[11]) wieder abziehen
                double nx = n[0] - m[3], ny = n[1] - m[7], nz = n[2] - m[11];
                if (nz <= 0.0001) continue; // Rückseite
                double shade = Math.max(0.45, Math.min(1.0, 0.62 + 0.38 * nz + 0.14 * ny - 0.08 * nx));
                double[][] s = new double[4][];
                for (int i = 0; i < 4; i++) {
                    double[] q = Mat.apply(m, face.pos[i]);
                    s[i] = new double[]{q[0] * sc + cx, cy - q[1] * sc, q[2]};
                }
                raster.quad(s, face.uv, face.rect, tex, shade);
            }
            raster.resolveInto(out, f);
        }
        return out;
    }
}
