package de.texturemixer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Das selbst gezeichnete Fadenkreuz: 15 x 15 Pixel (ARGB, 0 = leer), Größenfaktor und zuletzt benutzte Farbe.
 * Wird in der Config gespeichert und im Spiel von {@link CrosshairHud} mit echten Farben gezeichnet.
 *
 * Auswahl im Reiter "Fadenkreuz": selections["crosshair"] = {@link #ID}.
 */
public final class CustomCrosshair {
    public static final int N = 15;
    public static final int MAX_SCALE = 3;
    /** Wert in der Auswahl (statt eines Pack-Namens). Kein Dateiname, kann also nie mit einem Pack kollidieren. */
    public static final String ID = "@custom";
    /** Kategorie-ID des Reiters "Fadenkreuz". */
    public static final String KEY = "crosshair";
    public static final int DEFAULT_COLOR = 0xFFFFFF;

    public int[] pixels = preset(0, DEFAULT_COLOR);
    public int scale = 1;
    public int color = DEFAULT_COLOR;

    /** Macht eine gelesene Config wieder gültig (falsche Länge, ungültige Größe). */
    public void normalize() {
        if (pixels == null || pixels.length != N * N) pixels = preset(0, DEFAULT_COLOR);
        if (scale < 1 || scale > MAX_SCALE) scale = 1;
        color &= 0xFFFFFF;
    }

    /** Ist das eigene Fadenkreuz im aktiven Profil ausgewählt? */
    public static boolean active() {
        return ID.equals(MixerConfig.get().selections.get(KEY));
    }

    // ---------- Vorlagen ----------

    public static final String[] PRESET_NAMES = {"Kreuz", "Punkt", "Plus", "Kreis"};

    /** 0 = Kreuz mit Lücke, 1 = Punkt (3x3), 2 = Plus ohne Lücke, 3 = Kreis mit Mittelpunkt. */
    public static int[] preset(int type, int rgb) {
        int[] p = new int[N * N];
        int c = 0xFF000000 | (rgb & 0xFFFFFF);
        int m = N / 2;
        switch (type) {
            case 0 -> {
                for (int d = 3; d <= 6; d++) {
                    set(p, m + d, m, c);
                    set(p, m - d, m, c);
                    set(p, m, m + d, c);
                    set(p, m, m - d, c);
                }
            }
            case 1 -> {
                for (int y = m - 1; y <= m + 1; y++) {
                    for (int x = m - 1; x <= m + 1; x++) set(p, x, y, c);
                }
            }
            case 2 -> {
                for (int d = 0; d <= 6; d++) {
                    set(p, m + d, m, c);
                    set(p, m - d, m, c);
                    set(p, m, m + d, c);
                    set(p, m, m - d, c);
                }
            }
            default -> {
                for (int y = 0; y < N; y++) {
                    for (int x = 0; x < N; x++) {
                        double dist = Math.hypot(x - m, y - m);
                        if (dist >= 4.5 && dist <= 5.5) set(p, x, y, c);
                    }
                }
                set(p, m, m, c);
            }
        }
        return p;
    }

    private static void set(int[] p, int x, int y, int c) {
        if (x >= 0 && y >= 0 && x < N && y < N) p[y * N + x] = c;
    }

    // ---------- Ausgabe-Pack ----------

    /**
     * Schreibt ein komplett durchsichtiges 15x15-Bild. Es ersetzt im Ausgabe-Pack das Vanilla-Fadenkreuz,
     * damit nur das eigene (echte Farben) zu sehen ist, die Angriffsanzeige aber weiter normal funktioniert.
     */
    public static void writeBlankSprite(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        BufferedImage img = new BufferedImage(N, N, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(img, "png", file.toFile());
    }
}
