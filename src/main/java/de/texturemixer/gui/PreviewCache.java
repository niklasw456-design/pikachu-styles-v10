package de.texturemixer.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.texturemixer.Category;
import de.texturemixer.PackScanner;
import de.texturemixer.TextureMixerClient;
import de.texturemixer.render.CrystalRenderer;
import de.texturemixer.render.FireRenderer;
import de.texturemixer.render.Raster;
import de.texturemixer.render.ShieldRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lädt Vorschaubilder aus Packs als GUI-Texturen und merkt sie sich.
 * Unterstützt .mcmeta-Animationen, fertig berechnete Bilder (Tränke) und den drehenden 3D-End-Crystal.
 */
public final class PreviewCache {
    /** Kantenlänge des Vorschaubereichs in GUI-Pixeln. */
    public static final int SIZE = 44;

    private static final Map<String, Preview> CACHE = new HashMap<>();
    private static int counter = 0;

    /** id == null bedeutet: in diesem Pack gibt es kein Vorschaubild. */
    public record Preview(Identifier id, int texW, int texH, int frameW, int frameH,
                          int dw, int dh, int[] frameIdx, int[] frameTicks, int total) {
        public int currentFrame() {
            if (frameIdx.length == 0) return 0;
            long t = (System.currentTimeMillis() / 50L) % Math.max(1, total);
            int acc = 0;
            for (int i = 0; i < frameIdx.length; i++) {
                acc += frameTicks[i];
                if (t < acc) return frameIdx[i];
            }
            return frameIdx[0];
        }
    }

    private static final Preview NONE = new Preview(null, 0, 0, 0, 0, 0, 0, new int[0], new int[0], 1);

    /**
     * Eine geladene Rüstungstextur. scale = ganzzahlige Vergrößerung beim Laden (scharfe Pixel),
     * grid() = Breite der Originaltextur in Pixeln (64 bei Vanilla, 128/256 bei HD-Packs).
     */
    public record Tex(Identifier id, int texW, int texH, int scale) {
        public int grid() {
            return texW / scale;
        }
    }

    /** Getragene Rüstung: outer = Helm/Brustplatte/Stiefel, legs = Hose (je null, falls das Pack sie nicht hat). */
    public record Body(Tex outer, Tex legs) {
        public boolean isEmpty() {
            return outer == null && legs == null;
        }
    }

    private static final Body NO_BODY = new Body(null, null);
    private static final Map<String, Body> BODY_CACHE = new HashMap<>();

    private PreviewCache() {}

    private static String key(PackScanner.PackInfo pack, Category c, Category.Variant v) {
        return pack.name() + "|" + pack.stamp() + "|" + c.fullId(v);
    }

    /** Gibt das Bild zurück, falls es schon geladen wurde, sonst null. */
    public static Preview peek(PackScanner.PackInfo pack, Category c, Category.Variant v) {
        return CACHE.get(key(pack, c, v));
    }

    /** Lädt das Bild sofort (muss im Render-Thread aufgerufen werden). */
    public static Preview load(PackScanner.PackInfo pack, Category c, Category.Variant v) {
        String k = key(pack, c, v);
        Preview existing = CACHE.get(k);
        if (existing != null) return existing;

        Preview result = NONE;
        try {
            if (v.isFire()) {
                PackScanner.FireData fire = PackScanner.readFire(pack);
                if (fire != null) result = createFire(fire);
            } else {
                PackScanner.PreviewData data = PackScanner.readPreview(pack, v);
                if (data != null) result = create(data, v);
            }
        } catch (Throwable e) {
            TextureMixerClient.LOGGER.warn("Vorschau für {} / {} konnte nicht geladen werden", pack.name(), c.fullId(v), e);
        }
        CACHE.put(k, result);
        return result;
    }

    private static String bodyKey(PackScanner.PackInfo pack, Category c, Category.Variant v) {
        String prefix = pack == null ? "vanilla|" : pack.name() + "|" + pack.stamp() + "|";
        return prefix + c.fullId(v);
    }

    /** Gibt die Rüstung zurück, falls sie schon geladen wurde, sonst null. pack == null = Vanilla ("Standard"). */
    public static Body peekBody(PackScanner.PackInfo pack, Category c, Category.Variant v) {
        return BODY_CACHE.get(bodyKey(pack, c, v));
    }

    /** Lädt die getragene Rüstung sofort (Render-Thread). pack == null = Vanilla ("Standard"). */
    public static Body loadBody(PackScanner.PackInfo pack, Category c, Category.Variant v) {
        String k = bodyKey(pack, c, v);
        Body existing = BODY_CACHE.get(k);
        if (existing != null) return existing;

        Body result = NO_BODY;
        try {
            String material = v.material();
            PackScanner.BodyData data = pack == null
                    ? PackScanner.readVanillaBody(material)
                    : PackScanner.readBody(pack, material);
            if (data != null) {
                Tex outer = data.outer() == null ? null : registerArmor(data.outer());
                Tex legs = data.legs() == null ? null : registerArmor(data.legs());
                result = new Body(outer, legs);
            }
        } catch (Throwable e) {
            TextureMixerClient.LOGGER.warn("Rüstungs-Vorschau für {} / {} konnte nicht geladen werden",
                    pack == null ? "Standard" : pack.name(), c.fullId(v), e);
        }
        BODY_CACHE.put(k, result);
        return result;
    }

    /** Lädt eine Rüstungstextur als GUI-Textur, ganzzahlig vergrößert (Vanilla 64 px -> Faktor 2). */
    private static Tex registerArmor(byte[] png) throws Exception {
        NativeImage src = NativeImage.read(new ByteArrayInputStream(png));
        int w = src.getWidth();
        int h = src.getHeight();
        int k = Math.max(1, w / 64);
        int s = Math.max(1, BodyView.Z / k);
        NativeImage dst = new NativeImage(NativeImage.Format.RGBA, w * s, h * s, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = src.getColorArgb(x, y);
                for (int dy = 0; dy < s; dy++) {
                    for (int dx = 0; dx < s; dx++) {
                        dst.setColorArgb(x * s + dx, y * s + dy, c);
                    }
                }
            }
        }
        src.close();

        NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "texturemixer_armor", dst);
        Identifier id = Identifier.of(TextureMixerClient.MOD_ID, "preview/" + (counter++));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
        return new Tex(id, w * s, h * s, s);
    }

    /** Dekodierte Bilddaten. */
    private record Raw(int[] argb, int w, int h) {}

    private static Raw decodePng(byte[] png) throws Exception {
        if (png == null) return null;
        NativeImage src = NativeImage.read(new ByteArrayInputStream(png));
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) argb[y * w + x] = src.getColorArgb(x, y);
        }
        src.close();
        return new Raw(argb, w, h);
    }

    /** Netherrack mit animierter Flamme aus fire_0 / fire_1 des Packs (Animation aus der .mcmeta von fire_0). */
    private static Preview createFire(PackScanner.FireData data) throws Exception {
        Raw f0 = decodePng(data.fire0());
        if (f0 == null) return NONE;
        Raw f1 = decodePng(data.fire1());
        Raw nether = decodePng(data.nether());
        if (nether == null) nether = decodePng(PackScanner.vanillaBytes("assets/minecraft/textures/block/netherrack.png"));
        if (nether == null) {
            int[] px = new int[16 * 16];
            java.util.Arrays.fill(px, 0xFF7A3B3B);
            nether = new Raw(px, 16, 16);
        }

        // Reihenfolge und Dauer der Animationsbilder aus der .mcmeta lesen
        int fw = Math.max(1, f0.w());
        int count = Math.max(1, f0.h() / fw);
        int frametime = 2;
        List<int[]> seq = new ArrayList<>();
        if (data.mcmeta() != null) {
            try {
                JsonObject root = JsonParser.parseString(new String(data.mcmeta(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("animation") && root.get("animation").isJsonObject()) {
                    JsonObject a = root.getAsJsonObject("animation");
                    if (a.has("frametime")) frametime = Math.max(1, a.get("frametime").getAsInt());
                    if (a.has("frames") && a.get("frames").isJsonArray()) {
                        for (JsonElement e : a.getAsJsonArray("frames")) {
                            int idx;
                            int time = frametime;
                            if (e.isJsonPrimitive()) {
                                idx = e.getAsInt();
                            } else if (e.isJsonObject()) {
                                JsonObject o = e.getAsJsonObject();
                                idx = o.has("index") ? o.get("index").getAsInt() : 0;
                                if (o.has("time")) time = Math.max(1, o.get("time").getAsInt());
                            } else {
                                continue;
                            }
                            seq.add(new int[]{idx, time});
                        }
                    }
                }
            } catch (Exception ignored) {
                seq.clear();
            }
        }
        final int cnt = count;
        seq.removeIf(f -> f[0] < 0 || f[0] >= cnt);
        if (seq.isEmpty()) {
            for (int i = 0; i < count; i++) seq.add(new int[]{i, frametime});
        }

        // höchstens 48 Bilder berechnen (bei sehr langen Animationen gleichmäßig auslassen)
        int step = Math.max(1, (int) Math.ceil(seq.size() / 48.0));
        List<int[]> used = new ArrayList<>();
        for (int i = 0; i < seq.size(); i += step) used.add(new int[]{seq.get(i)[0], seq.get(i)[1] * step});
        int[] idx = new int[used.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = used.get(i)[0];

        int[] strip = FireRenderer.render(
                Raster.Tex.plain(f0.argb(), f0.w(), f0.h()),
                f1 == null ? null : Raster.Tex.plain(f1.argb(), f1.w(), f1.h()),
                Raster.Tex.plain(nether.argb(), nether.w(), nether.h()), idx);
        List<int[]> frames = new ArrayList<>();
        for (int i = 0; i < used.size(); i++) frames.add(new int[]{i, Math.max(1, used.get(i)[1])});
        return build(strip, FireRenderer.FRAME, FireRenderer.FRAME * used.size(),
                FireRenderer.FRAME, FireRenderer.FRAME, frames);
    }

    private static Preview create(PackScanner.PreviewData data, Category.Variant variant) throws Exception {
        int[] argb;
        int w;
        int h;
        if (data.argb() != null) {
            argb = data.argb();
            w = data.w();
            h = data.h();
        } else {
            NativeImage src = NativeImage.read(new ByteArrayInputStream(data.png()));
            w = src.getWidth();
            h = src.getHeight();
            argb = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) argb[y * w + x] = src.getColorArgb(x, y);
            }
            src.close();
        }

        // End Crystal: drehendes 3D-Modell aus der Entity-Textur berechnen
        if ("crystal".equals(variant.render())) {
            int[] strip = CrystalRenderer.render(argb, w, h);
            List<int[]> frames = new ArrayList<>();
            for (int i = 0; i < CrystalRenderer.FRAMES; i++) frames.add(new int[]{i, 3});
            return build(strip, CrystalRenderer.FRAME, CrystalRenderer.FRAME * CrystalRenderer.FRAMES,
                    CrystalRenderer.FRAME, CrystalRenderer.FRAME, frames);
        }

        // Schild: langsam drehendes 3D-Modell (nur bei der Entity-Textur im Format 64x64 bzw. Vielfachem davon)
        if (variant.isShield() && w == h && w >= 64 && w % 64 == 0) {
            int[] strip = ShieldRenderer.render(argb, w, h);
            List<int[]> frames = new ArrayList<>();
            for (int i = 0; i < ShieldRenderer.FRAMES; i++) frames.add(new int[]{i, ShieldRenderer.TICKS_PER_FRAME});
            return build(strip, ShieldRenderer.FRAME, ShieldRenderer.FRAME * ShieldRenderer.FRAMES,
                    ShieldRenderer.FRAME, ShieldRenderer.FRAME, frames);
        }

        int fw = w;
        int fh = h;
        int frametime = 1;
        boolean animated = false;
        List<int[]> frames = new ArrayList<>();

        if (data.mcmeta() != null) {
            try {
                JsonObject root = JsonParser.parseString(new String(data.mcmeta(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("animation") && root.get("animation").isJsonObject()) {
                    JsonObject a = root.getAsJsonObject("animation");
                    animated = true;
                    fw = a.has("width") ? a.get("width").getAsInt() : w;
                    fh = a.has("height") ? a.get("height").getAsInt() : fw;
                    if (fw <= 0 || fh <= 0 || fw > w || fh > h) {
                        fw = w;
                        fh = Math.min(h, w);
                    }
                    if (a.has("frametime")) frametime = Math.max(1, a.get("frametime").getAsInt());
                    if (a.has("frames") && a.get("frames").isJsonArray()) {
                        for (JsonElement e : a.getAsJsonArray("frames")) {
                            int idx;
                            int time = frametime;
                            if (e.isJsonPrimitive()) {
                                idx = e.getAsInt();
                            } else if (e.isJsonObject()) {
                                JsonObject o = e.getAsJsonObject();
                                idx = o.has("index") ? o.get("index").getAsInt() : 0;
                                if (o.has("time")) time = Math.max(1, o.get("time").getAsInt());
                            } else {
                                continue;
                            }
                            frames.add(new int[]{idx, time});
                        }
                    }
                }
            } catch (Exception ignored) {
                animated = false;
            }
        }

        if (!animated) {
            fw = w;
            fh = h;
            frames.clear();
        }

        int count = Math.max(1, h / fh);
        final int cnt = count;
        frames.removeIf(f -> f[0] < 0 || f[0] >= cnt);
        if (frames.isEmpty()) {
            if (animated) {
                for (int i = 0; i < count; i++) frames.add(new int[]{i, frametime});
            } else {
                frames.add(new int[]{0, 1});
            }
        }
        return build(argb, w, h, fw, fh, frames);
    }

    /** Vergrößert das Bild ganzzahlig (scharfe Pixel), lädt es als Textur und legt die Animation fest. */
    private static Preview build(int[] argb, int w, int h, int fw, int fh, List<int[]> frames) {
        int s = (int) Math.ceil((double) SIZE / Math.max(fw, fh));
        s = Math.max(1, Math.min(8, s));
        int nw = w * s;
        int nh = h * s;
        NativeImage dst = new NativeImage(NativeImage.Format.RGBA, nw, nh, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x];
                for (int dy = 0; dy < s; dy++) {
                    for (int dx = 0; dx < s; dx++) {
                        dst.setColorArgb(x * s + dx, y * s + dy, c);
                    }
                }
            }
        }

        NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "texturemixer_preview", dst);
        Identifier id = Identifier.of(TextureMixerClient.MOD_ID, "preview/" + (counter++));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);

        int sfw = fw * s;
        int sfh = fh * s;
        float f = Math.min((float) SIZE / sfw, (float) SIZE / sfh);
        int dw = Math.max(1, Math.round(sfw * f));
        int dh = Math.max(1, Math.round(sfh * f));

        int[] idx = new int[frames.size()];
        int[] ticks = new int[frames.size()];
        int total = 0;
        for (int i = 0; i < frames.size(); i++) {
            idx[i] = frames.get(i)[0];
            ticks[i] = frames.get(i)[1];
            total += ticks[i];
        }
        return new Preview(id, nw, nh, sfw, sfh, dw, dh, idx, ticks, Math.max(1, total));
    }
}
