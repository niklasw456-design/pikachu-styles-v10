package de.texturemixer.render;

import java.util.List;
import java.util.Map;

/**
 * Rendert ein Item-Modell im JSON-Format des Spiels (elements mit from/to, Flächen, Drehung) so, wie es im
 * Inventar (display.gui) aussieht. Rein Java (Software-Renderer), keine Minecraft-Klassen.
 * Ausgabe: ein Bild FRAME x FRAME (ARGB). 1 Block = FRAME Pixel, Mitte des Bildes = Mitte des Inventarfelds.
 */
public final class ModelRenderer {
    public static final int FRAME = 64;

    /** uv darf null sein (dann Standardwerte), texture ist der Schlüssel in Model.textures, rotation 0/90/180/270. */
    public record Face(double[] uv, String texture, int rotation) {}

    /** from/to und origin in Modellpixeln (0..16); axis null = keine Drehung. */
    public record Element(double[] from, double[] to, double[] origin, String axis, double angle,
                          boolean rescale, boolean shade, Map<String, Face> faces) {}

    /** Textur; strip = senkrechter Animationsstreifen (nur das erste quadratische Bild wird genutzt). */
    public record Tex(int[] argb, int w, int h, boolean strip) {}

    /** rotation/translation/scale aus display.gui; frontLight = flach beleuchtet (gui_light: front). */
    public record Model(List<Element> elements, Map<String, Tex> textures,
                        double[] rotation, double[] translation, double[] scale, boolean frontLight) {}

    private ModelRenderer() {}

    private static double[] rotZ(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static double[][] faceVerts(String dir, double x1, double y1, double z1, double x2, double y2, double z2) {
        return switch (dir) {
            case "down" -> new double[][]{{x1, y1, z2}, {x1, y1, z1}, {x2, y1, z1}, {x2, y1, z2}};
            case "up" -> new double[][]{{x1, y2, z1}, {x1, y2, z2}, {x2, y2, z2}, {x2, y2, z1}};
            case "north" -> new double[][]{{x2, y2, z1}, {x2, y1, z1}, {x1, y1, z1}, {x1, y2, z1}};
            case "south" -> new double[][]{{x1, y2, z2}, {x1, y1, z2}, {x2, y1, z2}, {x2, y2, z2}};
            case "west" -> new double[][]{{x1, y2, z1}, {x1, y1, z1}, {x1, y1, z2}, {x1, y2, z2}};
            case "east" -> new double[][]{{x2, y2, z2}, {x2, y1, z2}, {x2, y1, z1}, {x2, y2, z1}};
            default -> null;
        };
    }

    private static double[] defaultUv(String dir, double x1, double y1, double z1, double x2, double y2, double z2) {
        return switch (dir) {
            case "down" -> new double[]{x1, 16 - z2, x2, 16 - z1};
            case "up" -> new double[]{x1, z1, x2, z2};
            case "north" -> new double[]{16 - x2, 16 - y2, 16 - x1, 16 - y1};
            case "south" -> new double[]{x1, 16 - y2, x2, 16 - y1};
            case "west" -> new double[]{z1, 16 - y2, z2, 16 - y1};
            case "east" -> new double[]{16 - z2, 16 - y2, 16 - z1, 16 - y1};
            default -> new double[]{0, 0, 16, 16};
        };
    }

    public static int[] render(Model model) {
        double[] rot = model.rotation();
        double[] trn = model.translation();
        double[] scl = model.scale();
        double[] rm = Mat.mul(Mat.rotX(Math.toRadians(rot[0])), Mat.rotY(Math.toRadians(rot[1])),
                rotZ(Math.toRadians(rot[2])));
        boolean mirrored = scl[0] * scl[1] * scl[2] < 0;

        int[] out = new int[FRAME * FRAME];
        Raster raster = new Raster(FRAME, FRAME, 4);
        raster.clear();

        // Licht von links oben vorne (grobe Annäherung an die Block-Beleuchtung im Inventar)
        double[] light = {-0.5, 0.6, 0.62};
        double ll = Math.sqrt(light[0] * light[0] + light[1] * light[1] + light[2] * light[2]);

        for (Element el : model.elements()) {
            double x1 = Math.min(el.from()[0], el.to()[0]), x2 = Math.max(el.from()[0], el.to()[0]);
            double y1 = Math.min(el.from()[1], el.to()[1]), y2 = Math.max(el.from()[1], el.to()[1]);
            double z1 = Math.min(el.from()[2], el.to()[2]), z2 = Math.max(el.from()[2], el.to()[2]);

            double[] em = null;
            double rescale = 1;
            if (el.axis() != null && el.angle() != 0) {
                double a = Math.toRadians(el.angle());
                em = switch (el.axis()) {
                    case "x" -> Mat.rotX(a);
                    case "y" -> Mat.rotY(a);
                    default -> rotZ(a);
                };
                if (el.rescale()) rescale = 1.0 / Math.max(0.2, Math.cos(a));
            }

            for (Map.Entry<String, Face> fe : el.faces().entrySet()) {
                String dir = fe.getKey();
                Face face = fe.getValue();
                double[][] verts = faceVerts(dir, x1, y1, z1, x2, y2, z2);
                if (verts == null) continue;
                Tex t = model.textures().get(face.texture());
                if (t == null) continue;

                double[][] pw = new double[4][];
                for (int i = 0; i < 4; i++) {
                    double[] p = verts[i].clone();
                    if (em != null) {
                        double[] o = el.origin();
                        double[] d = {p[0] - o[0], p[1] - o[1], p[2] - o[2]};
                        double[] r = Mat.apply(em, d);
                        double sx = 1, sy = 1, sz = 1;
                        switch (el.axis()) {
                            case "x" -> {
                                sy = rescale;
                                sz = rescale;
                            }
                            case "y" -> {
                                sx = rescale;
                                sz = rescale;
                            }
                            default -> {
                                sx = rescale;
                                sy = rescale;
                            }
                        }
                        p = new double[]{o[0] + r[0] * sx, o[1] + r[1] * sy, o[2] + r[2] * sz};
                    }
                    // Modell (0..16) -> Blockeinheiten, Mitte 0; dann Skalierung, Drehung, Verschiebung (display.gui)
                    double[] b = {(p[0] / 16 - 0.5) * scl[0], (p[1] / 16 - 0.5) * scl[1], (p[2] / 16 - 0.5) * scl[2]};
                    double[] r = Mat.apply(rm, b);
                    pw[i] = new double[]{r[0] + trn[0] / 16, r[1] + trn[1] / 16, r[2] + trn[2] / 16};
                }

                // Normale aus den ersten drei Ecken (gegen den Uhrzeigersinn von außen gesehen)
                double ax = pw[1][0] - pw[0][0], ay = pw[1][1] - pw[0][1], az = pw[1][2] - pw[0][2];
                double bx = pw[2][0] - pw[1][0], by = pw[2][1] - pw[1][1], bz = pw[2][2] - pw[1][2];
                double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl < 1e-12) continue;
                nx /= nl;
                ny /= nl;
                nz /= nl;
                if (mirrored) nz = -nz;
                if (nz <= 0.0001) continue; // abgewandt

                double shade = 1.0;
                if (!model.frontLight() && el.shade()) {
                    double dot = (nx * light[0] + ny * light[1] + nz * light[2]) / ll;
                    shade = 0.62 + 0.38 * Math.max(0, dot);
                }

                double[] uv = face.uv() != null ? face.uv() : defaultUv(dir, x1, y1, z1, x2, y2, z2);
                double u1 = uv[0], v1 = uv[1], u2 = uv[2], v2 = uv[3];
                double[][] uvs = new double[4][];
                int shift = ((face.rotation() / 90) % 4 + 4) % 4;
                for (int i = 0; i < 4; i++) {
                    int k = (i + shift) % 4;
                    double u = (k == 0 || k == 1) ? u1 : u2;
                    double v = (k == 0 || k == 3) ? v1 : v2;
                    uvs[i] = new double[]{u, v};
                }
                double[] rect = {Math.min(u1, u2), Math.min(v1, v2), Math.abs(u2 - u1), Math.abs(v2 - v1)};

                int baseH = t.strip() ? 16 * Math.max(1, t.h() / Math.max(1, t.w())) : 16;
                Raster.Tex rt = new Raster.Tex(t.argb(), t.w(), t.h(), 16, baseH);

                double[][] s = new double[4][];
                for (int i = 0; i < 4; i++) {
                    s[i] = new double[]{FRAME / 2.0 + pw[i][0] * FRAME, FRAME / 2.0 - pw[i][1] * FRAME, pw[i][2]};
                }
                raster.quad(s, uvs, rect, rt, shade);
            }
        }
        raster.resolveInto(out, 0);
        return out;
    }
}
