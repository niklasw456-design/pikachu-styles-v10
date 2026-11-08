package de.texturemixer.gui;

import de.texturemixer.CrosshairHud;
import de.texturemixer.CustomCrosshair;
import de.texturemixer.MixerConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Editor für das eigene Fadenkreuz: 15 x 15 Pixel zeichnen, Farbe wählen (Farbfeld, Farbton, Hex, Palette),
 * Werkzeuge Stift / Radierer / Füllen / Pipette, Spiegeln, Rückgängig, Vorlagen und Vorschau auf drei Hintergründen.
 *
 * Maus: Links = malen, Rechts = löschen. Gezeichnet wird auf einer Kopie, erst "Fertig" übernimmt die Änderung
 * (und wählt "Eigenes" aus), ESC / "Abbrechen" verwirft sie.
 *
 * Die Maus wird (wie die Tasten im Hauptmenü) direkt über GLFW abgefragt, damit Ziehen über die Fläche ohne
 * Version-abhängige Maus-Methoden funktioniert.
 */
public class CrosshairEditorScreen extends Screen {
    private static final int BG = 0xF2101319;
    private static final int PANEL_BORDER = 0xFF2A2F3B;
    private static final int HEADER = 0xFF0B0D11;
    private static final int ACCENT = 0xFFFFC933;
    private static final int ACCENT_DARK = 0xFF5C4712;
    private static final int CARD = 0xFF171B22;
    private static final int CARD_HOVER = 0xFF212733;
    private static final int HOVER_BORDER = 0xFF4A5366;
    private static final int TEXT = 0xFFE6E9EF;
    private static final int MUTED = 0xFF8A93A3;

    private static final int N = CustomCrosshair.N;
    private static final int PREV = 46;
    private static final int[] PALETTE = {
            0xFFFFFF, 0x000000, 0xFF3B30, 0xFF9500, 0xFFE100, 0x7CFF3A,
            0x00C853, 0x00E5FF, 0x2979FF, 0xB052FF, 0xFF4DB8};
    private static final int[] PREVIEW_BG = {0xFF3B3B3B, 0xFF5B8A3C, 0xFFD9E6F2};

    private enum Tool {
        PEN("Stift"), ERASER("Radierer"), FILL("Füllen"), PICK("Pipette");
        final String label;

        Tool(String label) {
            this.label = label;
        }
    }

    private enum Drag {NONE, CANVAS, CANVAS_ERASE, SV, HUE}

    private record Ctl(int x, int y, int w, int h, Supplier<String> label,
                       BooleanSupplier selected, BooleanSupplier enabled) {}

    private final Screen parent;
    private final Runnable onApply;
    private final List<Ctl> ctls = new ArrayList<>();
    private final Map<String, Float> anim = new HashMap<>();
    private final ArrayDeque<int[]> undo = new ArrayDeque<>();

    // Arbeitskopie
    private final int[] px = new int[N * N];
    private int scale;
    private float hue, sat = 1f, val = 1f;
    private int rgb;
    private Tool tool = Tool.PEN;
    private boolean symX, symY, grid = true;

    // Hex-Feld
    private TextFieldWidget hex;
    private boolean hexBusy = false;
    private boolean focusHexNext = false;

    // Layout (in init berechnet)
    private int pxL, pxR, pyT, pyB;
    private int cs, canvasX, canvasY, prevX, prevY;
    private int svX, svY, svW, svH, hueX, hueY, hueW, hueH;
    private int palX, palY, palW, palH, sizeY, bx, bw, rx, rw;
    private int footY;

    // Maus
    private Drag drag = Drag.NONE;
    private boolean wasLeft, wasRight, mouseInit = false;
    private int lastCx = -1, lastCy = -1;
    private boolean zWas = false;

    // Animation
    private final long openStart = nowMs();
    private long lastFrameNs = System.nanoTime();
    private float dt = 0.016f;
    private float gA = 0f;

    public CrosshairEditorScreen(Screen parent, Runnable onApply) {
        super(Text.literal("Fadenkreuz zeichnen"));
        this.parent = parent;
        this.onApply = onApply;
        CustomCrosshair cc = MixerConfig.get().crosshair;
        System.arraycopy(cc.pixels, 0, px, 0, px.length);
        scale = cc.scale;
        setRgb(cc.color, false);
    }

    // ---------- Farbe ----------

    private static int hsv(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        float c = v * s;
        float hh = h * 6f;
        float x = c * (1f - Math.abs(hh % 2f - 1f));
        float m = v - c;
        float r, g, b;
        switch ((int) hh) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        int ri = Math.round((r + m) * 255f);
        int gi = Math.round((g + m) * 255f);
        int bi = Math.round((b + m) * 255f);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    /** Setzt die aktuelle Farbe. Bei Grau/Schwarz bleibt der bisherige Farbton erhalten. */
    private void setRgb(int color, boolean fromHex) {
        rgb = color & 0xFFFFFF;
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        val = max;
        sat = max <= 0f ? 0f : d / max;
        if (d > 0.0001f) {
            float h;
            if (max == r) h = ((g - b) / d) % 6f;
            else if (max == g) h = (b - r) / d + 2f;
            else h = (r - g) / d + 4f;
            h /= 6f;
            if (h < 0f) h += 1f;
            hue = h;
        }
        if (!fromHex) syncHex();
    }

    /** Farbfeld / Farbton wurden bewegt. */
    private void fromHsv() {
        rgb = hsv(hue, sat, val) & 0xFFFFFF;
        syncHex();
    }

    private void syncHex() {
        if (hex == null) return;
        hexBusy = true;
        hex.setText(String.format("%06X", rgb));
        hexBusy = false;
    }

    private void onHex(String text) {
        if (hexBusy) return;
        String t = text.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() == 6 && t.matches("[0-9a-fA-F]{6}")) {
            setRgb(Integer.parseInt(t, 16), true);
        }
    }

    private int ink() {
        return 0xFF000000 | rgb;
    }

    // ---------- Aufbau ----------

    private ButtonWidget hit(int x, int y, int w, int h, Runnable action) {
        ButtonWidget b = ButtonWidget.builder(Text.empty(), btn -> action.run()).dimensions(x, y, w, h).build();
        b.setAlpha(0f); // Klickfläche, gezeichnet wird alles selbst
        return this.addDrawableChild(b);
    }

    private void addCtl(int x, int y, int w, int h, Supplier<String> label,
                        BooleanSupplier selected, BooleanSupplier enabled, Runnable action) {
        ctls.add(new Ctl(x, y, w, h, label, selected, enabled));
        hit(x, y, w, h, () -> {
            if (enabled.getAsBoolean()) action.run();
        });
    }

    @Override
    protected void init() {
        ctls.clear();

        int w = Math.min(this.width - 16, 470);
        pxL = (this.width - w) / 2;
        pxR = pxL + w;
        pyT = 8;
        pyB = this.height - 8;
        footY = pyB - 24;
        int bodyTop = pyT + 38;
        int bodyBot = footY - 8;

        // Linke Spalte: Zeichenfläche + Vorschau
        cs = Math.max(5, Math.min(12, (bodyBot - bodyTop - (PREV + 6)) / N));
        int canvasW = cs * N;
        int previewW = 3 * PREV + 6;
        int colA = Math.max(canvasW, previewW);
        int ax = pxL + 12;
        canvasX = ax + (colA - canvasW) / 2;
        canvasY = bodyTop;
        prevX = ax + (colA - previewW) / 2;
        prevY = canvasY + canvasW + 6;

        // Rechte Spalte
        bx = ax + colA + 14;
        bw = Math.max(150, pxR - 12 - bx);
        int y = bodyTop;

        int tw = (bw - 12) / 4;
        int i = 0;
        for (Tool t : Tool.values()) {
            addCtl(bx + i * (tw + 4), y, tw, 18, () -> t.label, () -> tool == t, () -> true, () -> tool = t);
            i++;
        }
        y += 22;

        addCtl(bx, y, tw, 18, () -> "Sym X", () -> symX, () -> true, () -> symX = !symX);
        addCtl(bx + (tw + 4), y, tw, 18, () -> "Sym Y", () -> symY, () -> true, () -> symY = !symY);
        addCtl(bx + 2 * (tw + 4), y, tw, 18, () -> "Raster", () -> grid, () -> true, () -> grid = !grid);
        addCtl(bx + 3 * (tw + 4), y, tw, 18, () -> "Zurück", () -> false, () -> !undo.isEmpty(), this::undo);
        y += 22 + 4;

        // Farbfeld (Sättigung / Helligkeit) links, rechts daneben Farbe + Hex
        int below = 4 + 10 + 4 + 16 + 6 + 18;
        svH = Math.max(56, Math.min(70, bodyBot - y - below));
        svW = Math.max(70, Math.min(128, bw - 84));
        svX = bx;
        svY = y;
        rx = bx + svW + 8;
        rw = bx + bw - rx;
        hex = hex != null ? hex : makeHex();
        hex.setX(rx + 12);
        hex.setY(svY + 36 + 5);
        hex.setWidth(Math.max(20, rw - 16));
        hex.setHeight(10);
        this.addSelectableChild(hex);
        hit(rx, svY + 36, rw, 18, () -> focusHexNext = true);

        hueX = bx;
        hueW = bw;
        hueY = svY + svH + 4;
        hueH = 10;

        palX = bx;
        palY = hueY + hueH + 4;
        palW = (bw - (PALETTE.length - 1) * 2) / PALETTE.length;
        palH = 16;
        for (int k = 0; k < PALETTE.length; k++) {
            final int color = PALETTE[k];
            hit(palX + k * (palW + 2), palY, palW, palH, () -> setRgb(color, false));
        }

        // Größe
        sizeY = palY + palH + 6;
        addCtl(bx + 46, sizeY, 18, 18, () -> "-", () -> false, () -> scale > 1, () -> scale--);
        addCtl(bx + 46 + 18 + 28, sizeY, 18, 18, () -> "+", () -> false, () -> scale < CustomCrosshair.MAX_SCALE, () -> scale++);

        // Fußzeile
        int fx = pxL + 12;
        for (int k = 0; k < CustomCrosshair.PRESET_NAMES.length; k++) {
            final int type = k;
            final String name = CustomCrosshair.PRESET_NAMES[k];
            addCtl(fx + k * 44, footY, 42, 18, () -> name, () -> false, () -> true, () -> preset(type));
        }
        addCtl(fx + 4 * 44 + 4, footY, 50, 18, () -> "Leeren", () -> false, () -> true, this::clearAll);

        int rxr = pxR - 12;
        addCtl(rxr - 56, footY, 56, 18, () -> "Fertig", () -> false, () -> true, this::finish);
        addCtl(rxr - 56 - 4 - 66, footY, 66, 18, () -> "Abbrechen", () -> false, () -> true, this::close);
    }

    private TextFieldWidget makeHex() {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, 0, 0, 10, 10, Text.literal("Hex"));
        f.setDrawsBackground(false);
        f.setMaxLength(7);
        f.setText(String.format("%06X", rgb));
        f.setChangedListener(this::onHex);
        return f;
    }

    // ---------- Aktionen ----------

    private void pushUndo() {
        undo.push(px.clone());
        while (undo.size() > 40) undo.removeLast();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        System.arraycopy(undo.pop(), 0, px, 0, px.length);
    }

    private void clearAll() {
        pushUndo();
        Arrays.fill(px, 0);
    }

    private void preset(int type) {
        pushUndo();
        System.arraycopy(CustomCrosshair.preset(type, rgb), 0, px, 0, px.length);
    }

    private void finish() {
        MixerConfig cfg = MixerConfig.get();
        CustomCrosshair cc = cfg.crosshair;
        System.arraycopy(px, 0, cc.pixels, 0, px.length);
        cc.scale = scale;
        cc.color = rgb;
        cfg.save();
        onApply.run();
        close();
    }

    /** Schließt ohne zu speichern (ESC / Abbrechen). */
    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        if (hex != null && hex.isFocused()) {
            unfocusHex();
            return false;
        }
        return true;
    }

    private void unfocusHex() {
        if (hex != null && hex.isFocused()) {
            this.setFocused(null);
            hex.setFocused(false);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.client == null) return;
        long h = this.client.getWindow().getHandle();
        boolean ctrl = GLFW.glfwGetKey(h, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        boolean z = GLFW.glfwGetKey(h, GLFW.GLFW_KEY_Z) == GLFW.GLFW_PRESS;
        if (ctrl && z && !zWas && !(hex != null && hex.isFocused())) undo();
        zWas = z;
    }

    // ---------- Malen ----------

    private void setPx(int x, int y, int v) {
        if (x < 0 || y < 0 || x >= N || y >= N) return;
        px[y * N + x] = v;
    }

    /** Setzt einen Pixel, bei aktiver Spiegelung auch die gespiegelten. */
    private void paint(int x, int y, int v) {
        setPx(x, y, v);
        if (symX) setPx(N - 1 - x, y, v);
        if (symY) setPx(x, N - 1 - y, v);
        if (symX && symY) setPx(N - 1 - x, N - 1 - y, v);
    }

    private void line(int x0, int y0, int x1, int y1, int v) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            paint(x0, y0, v);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    private void fill(int x, int y, int v) {
        int target = px[y * N + x];
        if (target == v) return;
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{x, y});
        px[y * N + x] = v;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] d : dirs) {
                int nx = c[0] + d[0], ny = c[1] + d[1];
                if (nx < 0 || ny < 0 || nx >= N || ny >= N) continue;
                if (px[ny * N + nx] != target) continue;
                px[ny * N + nx] = v;
                q.add(new int[]{nx, ny});
            }
        }
    }

    /** Index der Zelle unter der Maus (y * N + x) oder -1. */
    private int cellAt(int mx, int my) {
        int rx0 = mx - canvasX, ry0 = my - canvasY;
        if (rx0 < 0 || ry0 < 0 || rx0 >= cs * N || ry0 >= cs * N) return -1;
        return (ry0 / cs) * N + (rx0 / cs);
    }

    private void handleMouse(int mx, int my) {
        if (this.client == null) return;
        long h = this.client.getWindow().getHandle();
        boolean left = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean right = GLFW.glfwGetMouseButton(h, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        if (!mouseInit) {
            // Der Klick, der den Editor geöffnet hat, soll nicht malen
            mouseInit = true;
            wasLeft = left;
            wasRight = right;
            return;
        }
        boolean pressL = left && !wasLeft;
        boolean pressR = right && !wasRight;
        wasLeft = left;
        wasRight = right;

        if (drag == Drag.NONE && (pressL || pressR)) {
            int cell = cellAt(mx, my);
            if (cell >= 0) {
                unfocusHex();
                startStroke(cell % N, cell / N, pressR);
            } else if (pressL && inside(mx, my, svX, svY, svW, svH)) {
                unfocusHex();
                drag = Drag.SV;
            } else if (pressL && inside(mx, my, hueX, hueY, hueW, hueH)) {
                unfocusHex();
                drag = Drag.HUE;
            }
        }

        switch (drag) {
            case CANVAS, CANVAS_ERASE -> {
                boolean held = drag == Drag.CANVAS ? left : right;
                if (!held) {
                    drag = Drag.NONE;
                    lastCx = -1;
                } else {
                    int cell = cellAt(mx, my);
                    if (cell < 0) {
                        lastCx = -1;
                    } else {
                        int cx = cell % N, cy = cell / N;
                        int v = (drag == Drag.CANVAS_ERASE || tool == Tool.ERASER) ? 0 : ink();
                        if (lastCx < 0) paint(cx, cy, v);
                        else line(lastCx, lastCy, cx, cy, v);
                        lastCx = cx;
                        lastCy = cy;
                    }
                }
            }
            case SV -> {
                if (!left) {
                    drag = Drag.NONE;
                } else {
                    sat = clamp01((mx - svX) / (float) (svW - 1));
                    val = 1f - clamp01((my - svY) / (float) (svH - 1));
                    fromHsv();
                }
            }
            case HUE -> {
                if (!left) {
                    drag = Drag.NONE;
                } else {
                    hue = clamp01((mx - hueX) / (float) (hueW - 1));
                    fromHsv();
                }
            }
            default -> {
            }
        }
    }

    private void startStroke(int cx, int cy, boolean erase) {
        if (tool == Tool.FILL) {
            pushUndo();
            fill(cx, cy, erase ? 0 : ink());
            return;
        }
        if (tool == Tool.PICK && !erase) {
            int v = px[cy * N + cx];
            if ((v >>> 24) != 0) setRgb(v, false);
            return;
        }
        pushUndo();
        drag = erase ? Drag.CANVAS_ERASE : Drag.CANVAS;
        lastCx = -1; // der erste Pixel wird in handleMouse gesetzt
    }

    // ---------- Zeichnen ----------

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    private static float easeOut(float t) {
        float u = 1 - clamp01(t);
        return 1 - u * u * u;
    }

    private float anim(String key, boolean on) {
        float t = on ? 1f : 0f;
        float v = anim.getOrDefault(key, 0f);
        v += (t - v) * Math.min(1f, dt * 16f);
        if (Math.abs(t - v) < 0.01f) v = t;
        anim.put(key, v);
        return v;
    }

    private static int a(int color, float f) {
        int al = Math.round((color >>> 24) * clamp01(f));
        return (al << 24) | (color & 0xFFFFFF);
    }

    private static int mix(int c1, int c2, float t) {
        t = clamp01(t);
        int r = 0;
        for (int sh = 24; sh >= 0; sh -= 8) {
            int x = (c1 >>> sh) & 0xFF;
            int y = (c2 >>> sh) & 0xFF;
            r |= (Math.round(x + (y - x) * t) & 0xFF) << sh;
        }
        return r;
    }

    private int c(int color) {
        return a(color, gA);
    }

    private void txt(DrawContext ctx, String s, int x, int y, int color) {
        if ((color >>> 24) < 8) return;
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(s), x, y, color);
    }

    private void ctxt(DrawContext ctx, String s, int cx, int y, int color) {
        if ((color >>> 24) < 8) return;
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(s), cx, y, color);
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void border(DrawContext c, int x, int y, int w, int h, int color) {
        c.fill(x, y, x + w, y + 1, color);
        c.fill(x, y + h - 1, x + w, y + h, color);
        c.fill(x, y + 1, x + 1, y + h - 1, color);
        c.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private static void panel(DrawContext c, int x, int y, int w, int h, int fill, int border) {
        if (w < 4 || h < 4) {
            c.fill(x, y, x + w, y + h, border);
            return;
        }
        c.fill(x + 1, y, x + w - 1, y + 1, border);
        c.fill(x + 1, y + h - 1, x + w - 1, y + h, border);
        c.fill(x, y + 1, x + 1, y + h - 1, border);
        c.fill(x + w - 1, y + 1, x + w, y + h - 1, border);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    private String fit(String s, int maxW) {
        if (this.textRenderer.getWidth(s) <= maxW) return s;
        while (s.length() > 1 && this.textRenderer.getWidth(s + "..") > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "..";
    }

    private void drawCtl(DrawContext ctx, Ctl ct, int mx, int my) {
        boolean en = ct.enabled().getAsBoolean();
        boolean sel = ct.selected().getAsBoolean();
        boolean hov = en && inside(mx, my, ct.x(), ct.y(), ct.w(), ct.h());
        String key = "ctl:" + ct.x() + ":" + ct.y();
        float h = anim(key, hov);
        float s = anim(key + "s", sel);
        int bg = !en ? 0xFF13161B : mix(mix(0xFF1B1F27, 0xFF262C38, h), ACCENT_DARK, s);
        int bd = !en ? PANEL_BORDER : mix(mix(PANEL_BORDER, HOVER_BORDER, h), ACCENT, s);
        panel(ctx, ct.x(), ct.y(), ct.w(), ct.h(), c(bg), c(bd));
        int tc = !en ? 0xFF555B68 : mix(0xFFC8CEDA, 0xFFFFFFFF, Math.max(h, s));
        ctxt(ctx, fit(ct.label().get(), ct.w() - 6), ct.x() + ct.w() / 2, ct.y() + (ct.h() - 8) / 2, c(tc));
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        long nowNs = System.nanoTime();
        dt = Math.min(0.1f, (nowNs - lastFrameNs) / 1e9f);
        lastFrameNs = nowNs;

        handleMouse(mx, my);
        if (focusHexNext && hex != null) {
            focusHexNext = false;
            this.setFocused(hex);
            hex.setFocused(true);
        }

        super.render(ctx, mx, my, delta); // Hintergrund; die Klickflächen sind unsichtbar
        float open = easeOut((nowMs() - openStart) / 200f);
        gA = open;

        // Fenster
        ctx.fill(pxL, pyT, pxR, pyB, c(BG));
        ctx.fill(pxL, pyT, pxR, pyT + 28, c(HEADER));
        ctx.fill(pxL, pyT + 28, pxL + Math.round((pxR - pxL) * open), pyT + 29, c(ACCENT));
        border(ctx, pxL, pyT, pxR - pxL, pyB - pyT, c(PANEL_BORDER));
        txt(ctx, "FADENKREUZ ZEICHNEN", pxL + 12, pyT + 10, c(ACCENT));
        String hint = "Links: malen  ·  Rechts: löschen  ·  Strg+Z: zurück";
        int hw = this.textRenderer.getWidth(hint);
        int titleW = this.textRenderer.getWidth("FADENKREUZ ZEICHNEN");
        if (pxL + 12 + titleW + 16 + hw < pxR - 12) txt(ctx, hint, pxR - 12 - hw, pyT + 10, c(MUTED));

        drawCanvas(ctx, mx, my);
        drawPreview(ctx);
        drawPicker(ctx, mx, my, delta);

        for (Ctl ct : ctls) drawCtl(ctx, ct, mx, my);

        // Größe
        txt(ctx, "Größe", bx, sizeY + 5, c(MUTED));
        ctxt(ctx, "x" + scale, bx + 46 + 18 + 14, sizeY + 5, c(TEXT));
        String sizeTxt = (N * scale) + " px";
        txt(ctx, sizeTxt, bx + 46 + 18 + 28 + 18 + 8, sizeY + 5, c(MUTED));
    }

    private void drawCanvas(DrawContext ctx, int mx, int my) {
        int size = cs * N;
        panel(ctx, canvasX - 2, canvasY - 2, size + 4, size + 4, c(CARD), c(PANEL_BORDER));

        // Schachbrett
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                int col = ((x + y) & 1) == 0 ? 0xFF1B1F27 : 0xFF222733;
                ctx.fill(canvasX + x * cs, canvasY + y * cs, canvasX + (x + 1) * cs, canvasY + (y + 1) * cs, c(col));
            }
        }
        if (grid && cs >= 6) {
            int gl = c(0x28FFFFFF);
            for (int i = 1; i < N; i++) {
                ctx.fill(canvasX + i * cs, canvasY, canvasX + i * cs + 1, canvasY + size, gl);
                ctx.fill(canvasX, canvasY + i * cs, canvasX + size, canvasY + i * cs + 1, gl);
            }
        }

        // Mittelpunkt (hier zielt das Fadenkreuz) und Spiegelachsen
        int m = N / 2;
        border(ctx, canvasX + m * cs, canvasY + m * cs, cs, cs, c(a(ACCENT, 0.55f)));
        if (symX) ctx.fill(canvasX + m * cs + cs / 2, canvasY, canvasX + m * cs + cs / 2 + 1, canvasY + size, c(a(ACCENT, 0.45f)));
        if (symY) ctx.fill(canvasX, canvasY + m * cs + cs / 2, canvasX + size, canvasY + m * cs + cs / 2 + 1, c(a(ACCENT, 0.45f)));

        // Pixel
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                int v = px[y * N + x];
                if ((v >>> 24) == 0) continue;
                ctx.fill(canvasX + x * cs, canvasY + y * cs, canvasX + (x + 1) * cs, canvasY + (y + 1) * cs, c(v));
            }
        }

        // Zelle unter der Maus
        int cell = cellAt(mx, my);
        if (cell >= 0) {
            int x = cell % N, y = cell / N;
            int gx = canvasX + x * cs, gy = canvasY + y * cs;
            if (tool == Tool.PEN || tool == Tool.FILL) {
                ctx.fill(gx, gy, gx + cs, gy + cs, c(a(ink(), 0.4f)));
            }
            border(ctx, gx, gy, cs, cs, c(0xCCFFFFFF));
        }
    }

    private void drawPreview(DrawContext ctx) {
        int size = N * scale;
        for (int i = 0; i < 3; i++) {
            int x = prevX + i * (PREV + 3);
            panel(ctx, x, prevY, PREV, PREV, c(PREVIEW_BG[i]), c(PANEL_BORDER));
            ctx.enableScissor(x + 1, prevY + 1, x + PREV - 1, prevY + PREV - 1);
            CrosshairHud.draw(ctx, x + (PREV - size) / 2, prevY + (PREV - size) / 2, px, scale);
            ctx.disableScissor();
        }
    }

    private void drawPicker(DrawContext ctx, int mx, int my, float delta) {
        // Farbfeld: links Sättigung, oben Helligkeit
        border(ctx, svX - 1, svY - 1, svW + 2, svH + 2, c(PANEL_BORDER));
        int step = 2;
        for (int x = 0; x < svW; x += step) {
            int w = Math.min(step, svW - x);
            float s = x / (float) Math.max(1, svW - 1);
            ctx.fillGradient(svX + x, svY, svX + x + w, svY + svH, c(hsv(hue, s, 1f)), c(0xFF000000));
        }
        int sx = svX + Math.round(sat * (svW - 1));
        int sy = svY + Math.round((1f - val) * (svH - 1));
        border(ctx, sx - 3, sy - 3, 7, 7, c(0xFF000000));
        border(ctx, sx - 2, sy - 2, 5, 5, c(0xFFFFFFFF));

        // Farbton
        border(ctx, hueX - 1, hueY - 1, hueW + 2, hueH + 2, c(PANEL_BORDER));
        for (int x = 0; x < hueW; x++) {
            ctx.fill(hueX + x, hueY, hueX + x + 1, hueY + hueH, c(hsv(x / (float) Math.max(1, hueW - 1), 1f, 1f)));
        }
        int hx = hueX + Math.round(hue * (hueW - 1));
        border(ctx, hx - 2, hueY - 2, 5, hueH + 4, c(0xFF000000));
        border(ctx, hx - 1, hueY - 1, 3, hueH + 2, c(0xFFFFFFFF));

        // Aktuelle Farbe + Hex
        txt(ctx, "Farbe", rx, svY, c(MUTED));
        panel(ctx, rx, svY + 11, rw, 20, c(ink()), c(PANEL_BORDER));
        boolean hf = hex != null && hex.isFocused();
        float ha = anim("hex", hf);
        panel(ctx, rx, svY + 36, rw, 18, c(mix(0xFF12151B, 0xFF1A1F29, ha)), c(mix(PANEL_BORDER, ACCENT, ha)));
        txt(ctx, "#", rx + 5, svY + 36 + 5, c(MUTED));
        if (hex != null) hex.render(ctx, mx, my, delta);

        // Palette
        for (int k = 0; k < PALETTE.length; k++) {
            int x = palX + k * (palW + 2);
            boolean sel = PALETTE[k] == rgb;
            boolean hov = inside(mx, my, x, palY, palW, palH);
            float hv = anim("pal" + k, hov);
            panel(ctx, x, palY, palW, palH, c(0xFF000000 | PALETTE[k]),
                    c(sel ? ACCENT : mix(PANEL_BORDER, 0xFFAAB2C0, hv)));
            if (sel) border(ctx, x + 1, palY + 1, palW - 2, palH - 2, c(0xFF000000));
        }
    }
}
