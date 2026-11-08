package de.texturemixer.render;

/**
 * Rendert einen drehenden End Crystal als Bildfolge (Software-Renderer, rein Java, keine Minecraft-Klassen).
 * Eingabe ist die Entity-Textur (64x32 bzw. ein Vielfaches davon), Ausgabe ein senkrechter Streifen
 * aus FRAMES Bildern der Größe FRAME x FRAME (ARGB).
 */
public final class CrystalRenderer {
    public static final int FRAME = 64;
    public static final int FRAMES = 24;

    private static final int SS = 3; // Supersampling pro Achse
    private static final double PITCH = Math.toRadians(22);
    private static final double CRYSTAL_HEIGHT = 9.5;
    private static final double SHRINK = 0.875;

    private CrystalRenderer() {}

    private static final class Face {
        final double[][] pos = new double[4][3]; // TL, TR, BR, BL
        final double[][] uv = new double[4][2];
        final double[] rect = new double[4];      // ux, uy, uw, uh (Texturraum 64x32)
        final int part;

        Face(int part) {
            this.part = part;
        }
    }

    // ---------- Matrizen (4x4, zeilenweise) ----------

    private static double[] identity() {
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j];
                r[i * 4 + j] = s;
            }
        }
        return r;
    }

    private static double[] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1};
    }

    private static double[] rotX(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1};
    }

    private static double[] rotAxis(double x, double y, double z, double a) {
        double len = Math.sqrt(x * x + y * y + z * z);
        x /= len;
        y /= len;
        z /= len;
        double c = Math.cos(a), s = Math.sin(a), t = 1 - c;
        return new double[]{
                t * x * x + c, t * x * y - s * z, t * x * z + s * y, 0,
                t * x * y + s * z, t * y * y + c, t * y * z - s * x, 0,
                t * x * z - s * y, t * y * z + s * x, t * z * z + c, 0,
                0, 0, 0, 1};
    }

    private static double[] scale(double s) {
        return new double[]{s, 0, 0, 0, 0, s, 0, 0, 0, 0, s, 0, 0, 0, 0, 1};
    }

    private static double[] translate(double x, double y, double z) {
        return new double[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1};
    }

    private static double[] apply(double[] m, double[] p) {
        return new double[]{
                m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3],
                m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7],
                m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11]};
    }

    // ---------- Geometrie ----------

    private static void addCuboid(java.util.List<Face> out, int part, double hx, double hy, double hz, double u0, double v0) {
        double w = 2 * hx, h = 2 * hy, d = 2 * hz;
        add(out, part, new double[][]{{-hx, hy, hz}, {hx, hy, hz}, {hx, -hy, hz}, {-hx, -hy, hz}}, u0 + d, v0 + d, w, h);
        add(out, part, new double[][]{{hx, hy, -hz}, {-hx, hy, -hz}, {-hx, -hy, -hz}, {hx, -hy, -hz}}, u0 + 2 * d + w, v0 + d, w, h);
        add(out, part, new double[][]{{hx, hy, hz}, {hx, hy, -hz}, {hx, -hy, -hz}, {hx, -hy, hz}}, u0 + d + w, v0 + d, d, h);
        add(out, part, new double[][]{{-hx, hy, -hz}, {-hx, hy, hz}, {-hx, -hy, hz}, {-hx, -hy, -hz}}, u0, v0 + d, d, h);
        add(out, part, new double[][]{{-hx, hy, -hz}, {hx, hy, -hz}, {hx, hy, hz}, {-hx, hy, hz}}, u0 + d, v0, w, d);
        add(out, part, new double[][]{{-hx, -hy, hz}, {hx, -hy, hz}, {hx, -hy, -hz}, {-hx, -hy, -hz}}, u0 + d + w, v0, w, d);
    }

    private static void add(java.util.List<Face> out, int part, double[][] corners, double ux, double uy, double uw, double uh) {
        Face f = new Face(part);
        for (int i = 0; i < 4; i++) f.pos[i] = corners[i];
        f.uv[0] = new double[]{ux, uy};
        f.uv[1] = new double[]{ux + uw, uy};
        f.uv[2] = new double[]{ux + uw, uy + uh};
        f.uv[3] = new double[]{ux, uy + uh};
        f.rect[0] = ux;
        f.rect[1] = uy;
        f.rect[2] = uw;
        f.rect[3] = uh;
        out.add(f);
    }

    // ---------- Rendern ----------

    public static int[] render(int[] tex, int tw, int th) {
        java.util.List<Face> faces = new java.util.ArrayList<>();
        addCuboid(faces, 0, 6, 2, 6, 0, 16);   // Sockel (Platte), UV (0,16)
        addCuboid(faces, 1, 4, 4, 4, 0, 0);    // äußeres Glas
        addCuboid(faces, 2, 4, 4, 4, 0, 0);    // inneres Glas
        addCuboid(faces, 3, 4, 4, 4, 32, 0);   // Kern

        double[] tilt = rotAxis(Math.sqrt(0.5), 0, Math.sqrt(0.5), Math.toRadians(60));
        double[] cam = rotX(PITCH);
        double[] plateM = mul(cam, translate(0, -2, 0));

        double[][][] mats = new double[FRAMES][4][];
        for (int f = 0; f < FRAMES; f++) {
            double a = 2 * Math.PI * f / FRAMES;
            double[] p = mul(rotY(a), mul(translate(0, CRYSTAL_HEIGHT, 0), tilt));
            double[] m2 = mul(p, mul(scale(SHRINK), mul(tilt, rotY(a))));
            double[] m3 = mul(m2, mul(scale(SHRINK), mul(tilt, rotY(a))));
            mats[f][0] = plateM;
            mats[f][1] = mul(cam, p);
            mats[f][2] = mul(cam, m2);
            mats[f][3] = mul(cam, m3);
        }

        // Plattenmitte: Oberseite liegt bei y = 0 -> Platte hat Zentrum y = -2 (siehe translate oben);
        // die Flächen sind um den Ursprung definiert, daher oben die Verschiebung.
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        for (int f = 0; f < FRAMES; f++) {
            for (Face face : faces) {
                for (double[] c : face.pos) {
                    double[] q = apply(mats[f][face.part], c);
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

        int n = FRAME * SS;
        double ssc = sc * SS;
        double scx = cx * SS, scy = cy * SS;

        int[] out = new int[FRAME * FRAMES * FRAME];
        int[] color = new int[n * n];
        double[] zbuf = new double[n * n];

        for (int f = 0; f < FRAMES; f++) {
            java.util.Arrays.fill(color, 0);
            java.util.Arrays.fill(zbuf, -1e18);

            for (Face face : faces) {
                double[][] s = new double[4][3];
                for (int i = 0; i < 4; i++) {
                    double[] q = apply(mats[f][face.part], face.pos[i]);
                    s[i][0] = q[0] * ssc + scx;
                    s[i][1] = scy - q[1] * ssc;
                    s[i][2] = q[2];
                }
                tri(color, zbuf, n, s[0], s[1], s[2], face.uv[0], face.uv[1], face.uv[2], face.rect, tex, tw, th);
                tri(color, zbuf, n, s[0], s[2], s[3], face.uv[0], face.uv[2], face.uv[3], face.rect, tex, tw, th);
            }

            // herunterrechnen (Boxfilter)
            for (int y = 0; y < FRAME; y++) {
                for (int x = 0; x < FRAME; x++) {
                    long a = 0, r = 0, g = 0, b = 0;
                    for (int dy = 0; dy < SS; dy++) {
                        for (int dx = 0; dx < SS; dx++) {
                            int c = color[(y * SS + dy) * n + (x * SS + dx)];
                            int ca = c >>> 24;
                            if (ca == 0) continue;
                            a += ca;
                            r += (long) ((c >> 16) & 0xFF) * ca;
                            g += (long) ((c >> 8) & 0xFF) * ca;
                            b += (long) (c & 0xFF) * ca;
                        }
                    }
                    int idx = (f * FRAME + y) * FRAME + x;
                    if (a == 0) {
                        out[idx] = 0;
                    } else {
                        int oa = (int) (a / (SS * SS));
                        int or = (int) (r / a), og = (int) (g / a), ob = (int) (b / a);
                        out[idx] = (oa << 24) | (or << 16) | (og << 8) | ob;
                    }
                }
            }
        }
        return out;
    }

    private static void tri(int[] color, double[] zbuf, int n,
                            double[] a, double[] b, double[] c,
                            double[] ua, double[] ub, double[] uc,
                            double[] rect, int[] tex, int tw, int th) {
        double minX = Math.min(a[0], Math.min(b[0], c[0]));
        double maxX = Math.max(a[0], Math.max(b[0], c[0]));
        double minY = Math.min(a[1], Math.min(b[1], c[1]));
        double maxY = Math.max(a[1], Math.max(b[1], c[1]));
        int x0 = Math.max(0, (int) Math.floor(minX));
        int x1 = Math.min(n - 1, (int) Math.ceil(maxX));
        int y0 = Math.max(0, (int) Math.floor(minY));
        int y1 = Math.min(n - 1, (int) Math.ceil(maxY));

        double den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
        if (Math.abs(den) < 1e-9) return;

        double sx = tw / 64.0, sy = th / 32.0;
        double eps = 0.001;

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double px = x + 0.5, py = y + 0.5;
                double w0 = ((b[1] - c[1]) * (px - c[0]) + (c[0] - b[0]) * (py - c[1])) / den;
                double w1 = ((c[1] - a[1]) * (px - c[0]) + (a[0] - c[0]) * (py - c[1])) / den;
                double w2 = 1 - w0 - w1;
                if (w0 < -1e-9 || w1 < -1e-9 || w2 < -1e-9) continue;
                double z = w0 * a[2] + w1 * b[2] + w2 * c[2];
                int idx = y * n + x;
                if (z <= zbuf[idx]) continue;

                double u = w0 * ua[0] + w1 * ub[0] + w2 * uc[0];
                double v = w0 * ua[1] + w1 * ub[1] + w2 * uc[1];
                u = Math.max(rect[0] + eps, Math.min(rect[0] + rect[2] - eps, u));
                v = Math.max(rect[1] + eps, Math.min(rect[1] + rect[3] - eps, v));
                int tx = Math.max(0, Math.min(tw - 1, (int) Math.floor(u * sx)));
                int ty = Math.max(0, Math.min(th - 1, (int) Math.floor(v * sy)));
                int texel = tex[ty * tw + tx];
                if ((texel >>> 24) < 26) continue; // Cutout wie im Spiel
                zbuf[idx] = z;
                color[idx] = texel | 0xFF000000;
            }
        }
    }
}
