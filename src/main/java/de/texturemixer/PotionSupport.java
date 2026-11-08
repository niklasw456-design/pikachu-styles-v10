package de.texturemixer;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trank-Texturen. Ab 1.21.4 stehen die Looks der Tränke in "items/potion.json" bzw. "items/splash_potion.json"
 * (eine select-Weiche über die Komponente potion_contents). Diese Klasse liest die Weiche eines Packs,
 * holt den Look für einen bestimmten Trank heraus, kopiert alle benötigten Modelle und Texturen
 * unter einen eigenen Pfad (damit sich Packs nicht gegenseitig überschreiben) und baut daraus
 * eine neue Weiche, die verschiedene Tränke aus verschiedenen Packs mischen kann.
 */
public final class PotionSupport {
    private PotionSupport() {}

    /** Ein Eintrag der neuen Weiche: diese Trank-IDs sollen dieses Modell (Knoten) nutzen. */
    public record Case(List<String> ids, Object node) {}

    /** Ein Bild als ARGB-Pixel. */
    public record Image(int w, int h, int[] argb) {}

    // ---------- Hilfen ----------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    private static String norm(String id) {
        if (id == null) return "";
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static String full(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    private static Map<String, Object> emptyModel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "minecraft:empty");
        return m;
    }

    // ---------- Look eines Tranks aus einem Pack lesen ----------

    /** Liefert den aufgelösten Modellknoten für den ersten passenden Trank aus ids, sonst null. */
    public static Map<String, Object> resolveFor(Path root, String itemDef, List<String> ids) throws IOException {
        Path f = root.resolve("assets").resolve("minecraft").resolve("items").resolve(itemDef + ".json");
        if (!Files.isRegularFile(f)) return null;
        Map<String, Object> top;
        try {
            top = obj(Json.parse(Files.readString(f)));
        } catch (RuntimeException e) {
            return null;
        }
        if (top == null || obj(top.get("model")) == null) return null;
        for (String id : ids) {
            boolean[] matched = {false};
            Object r = resolveNode(top.get("model"), id, matched);
            if (matched[0]) return obj(r);
        }
        return null;
    }

    private static Object resolveNode(Object node, String id, boolean[] matched) {
        Map<String, Object> src = obj(node);
        if (src == null) return node;
        Map<String, Object> n = new LinkedHashMap<>(src);
        String type = norm(str(n.get("type")));

        if (type.equals("select") && norm(str(n.get("property"))).equals("component")
                && norm(str(n.get("component"))).equals("potion_contents")) {
            for (Object c : arr(n.get("cases"))) {
                Map<String, Object> cm = obj(c);
                if (cm != null && whenMatches(cm.get("when"), id)) {
                    matched[0] = true;
                    return resolveNode(cm.get("model"), id, matched);
                }
            }
            Object fb = n.get("fallback");
            return fb != null ? resolveNode(fb, id, matched) : emptyModel();
        }

        for (String k : new String[]{"on_true", "on_false", "fallback"}) {
            if (obj(n.get(k)) != null) n.put(k, resolveNode(n.get(k), id, matched));
        }
        for (String k : new String[]{"cases", "entries"}) {
            if (n.get(k) instanceof List<?>) {
                List<Object> nl = new ArrayList<>();
                for (Object c : arr(n.get(k))) {
                    Map<String, Object> cm = obj(c);
                    if (cm != null && obj(cm.get("model")) != null) {
                        Map<String, Object> copy = new LinkedHashMap<>(cm);
                        copy.put("model", resolveNode(cm.get("model"), id, matched));
                        nl.add(copy);
                    } else {
                        nl.add(c);
                    }
                }
                n.put(k, nl);
            }
        }
        if (n.get("models") instanceof List<?>) {
            List<Object> nl = new ArrayList<>();
            for (Object c : arr(n.get("models"))) nl.add(resolveNode(c, id, matched));
            n.put("models", nl);
        }
        return n;
    }

    private static boolean whenMatches(Object when, String id) {
        if (when instanceof List<?> l) {
            for (Object o : l) if (whenMatches(o, id)) return true;
            return false;
        }
        if (when instanceof String s) return full(s).equals(full(id));
        Map<String, Object> m = obj(when);
        if (m != null && m.get("potion") != null) return whenMatches(m.get("potion"), id);
        return false;
    }

    // ---------- Exportieren (kopieren + Pfade umschreiben) ----------

    /** Kopiert alle Modelle/Texturen des Knotens nach outMc und gibt den umgeschriebenen Knoten zurück. */
    public static Object export(Path root, Object node, Path outMc, String key) throws IOException {
        return rewrite(root, node, outMc, key, new HashSet<>());
    }

    private static Object rewrite(Path root, Object o, Path outMc, String key, Set<String> done) throws IOException {
        if (o instanceof Map<?, ?>) {
            Map<String, Object> src = obj(o);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : src.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                if ((k.equals("model") || k.equals("base")) && v instanceof String s) {
                    out.put(k, rewriteModel(root, s, outMc, key, done));
                } else {
                    out.put(k, rewrite(root, v, outMc, key, done));
                }
            }
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) out.add(rewrite(root, x, outMc, key, done));
            return out;
        }
        return o;
    }

    private static String[] split(String id) {
        String f = full(id);
        int c = f.indexOf(':');
        return new String[]{f.substring(0, c), f.substring(c + 1)};
    }

    private static String rewriteModel(Path root, String id, Path outMc, String key, Set<String> done) throws IOException {
        String[] p = split(id);
        Path mf = root.resolve("assets").resolve(p[0]).resolve("models").resolve(p[1] + ".json");
        if (!Files.isRegularFile(mf)) return id; // Vanilla-Modell: unverändert lassen
        String newPath = "item/texturemixer/" + key + "/" + p[0] + "/" + p[1];
        if (done.add(full(id))) {
            Map<String, Object> m;
            try {
                m = obj(Json.parse(Files.readString(mf)));
            } catch (RuntimeException e) {
                m = null;
            }
            if (m == null) m = new LinkedHashMap<>();
            if (m.get("parent") instanceof String parent) {
                m.put("parent", rewriteModel(root, parent, outMc, key, done));
            }
            Map<String, Object> tex = obj(m.get("textures"));
            if (tex != null) {
                Map<String, Object> nt = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : tex.entrySet()) {
                    if (e.getValue() instanceof String t && !t.startsWith("#")) {
                        nt.put(e.getKey(), rewriteTexture(root, t, outMc, key));
                    } else {
                        nt.put(e.getKey(), e.getValue());
                    }
                }
                m.put("textures", nt);
            }
            Path out = outMc.resolve("models").resolve(newPath + ".json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, Json.write(m));
        }
        return "minecraft:" + newPath;
    }

    private static String rewriteTexture(Path root, String id, Path outMc, String key) throws IOException {
        String[] p = split(id);
        Path tf = root.resolve("assets").resolve(p[0]).resolve("textures").resolve(p[1] + ".png");
        if (!Files.isRegularFile(tf)) return id; // Vanilla-Textur
        String newPath = "item/texturemixer/" + key + "/" + p[0] + "/" + p[1];
        Path out = outMc.resolve("textures").resolve(newPath + ".png");
        Files.createDirectories(out.getParent());
        Files.copy(tf, out, StandardCopyOption.REPLACE_EXISTING);
        Path meta = tf.resolveSibling(tf.getFileName().toString() + ".mcmeta");
        if (Files.isRegularFile(meta)) {
            Files.copy(meta, outMc.resolve("textures").resolve(newPath + ".png.mcmeta"), StandardCopyOption.REPLACE_EXISTING);
        }
        return "minecraft:" + newPath;
    }

    // ---------- Vorschaubild ----------

    /** Setzt die Ebenen des ersten Modells zusammen (mit Tränke-Färbung). Gibt null zurück, wenn nichts da ist. */
    public static Image preview(Path root, Map<String, Object> node, int potionColor) throws IOException {
        Map<String, Object> leaf = firstModelLeaf(node);
        if (leaf == null || str(leaf.get("model")) == null) return null;

        // Texturen des Modells einsammeln (Kind überschreibt Elternteil)
        Map<String, String> textures = new LinkedHashMap<>();
        collectTextures(root, str(leaf.get("model")), textures, 0);

        List<String> layerKeys = new ArrayList<>();
        for (String k : textures.keySet()) if (k.startsWith("layer")) layerKeys.add(k);
        layerKeys.sort(String::compareTo);
        List<String> chosen = new ArrayList<>();
        if (!layerKeys.isEmpty()) {
            for (String k : layerKeys) chosen.add(textures.get(k));
        } else {
            for (String t : textures.values()) {
                if (!t.startsWith("#")) {
                    chosen.add(t);
                    break;
                }
            }
        }

        List<Object> tints = arr(leaf.get("tints"));
        Image canvas = null;
        for (int i = 0; i < chosen.size(); i++) {
            String[] p = split(chosen.get(i));
            Path tf = root.resolve("assets").resolve(p[0]).resolve("textures").resolve(p[1] + ".png");
            if (!Files.isRegularFile(tf)) continue;
            Image layer;
            try (InputStream in = Files.newInputStream(tf)) {
                layer = decode(in);
            }
            if (layer == null) continue;
            // animierte Streifen: nur das erste Quadrat
            if (layer.h() > layer.w()) layer = crop(layer, layer.w(), layer.w());

            Integer tint = null;
            Map<String, Object> tm = i < tints.size() ? obj(tints.get(i)) : null;
            if (tm != null) {
                String tt = norm(str(tm.get("type")));
                if (tt.equals("potion")) tint = potionColor;
                else if (tt.equals("constant") && tm.get("value") instanceof Number num) tint = num.intValue();
            }
            if (canvas == null) canvas = new Image(layer.w(), layer.h(), new int[layer.w() * layer.h()]);
            blend(canvas, layer, tint);
        }
        return canvas;
    }

    private static Map<String, Object> firstModelLeaf(Object node) {
        Map<String, Object> n = obj(node);
        if (n == null) return null;
        if (norm(str(n.get("type"))).equals("model") && n.get("model") instanceof String) return n;
        for (String k : new String[]{"on_true", "fallback", "on_false"}) {
            Map<String, Object> r = firstModelLeaf(n.get(k));
            if (r != null) return r;
        }
        for (String k : new String[]{"cases", "entries"}) {
            for (Object c : arr(n.get(k))) {
                Map<String, Object> cm = obj(c);
                if (cm != null) {
                    Map<String, Object> r = firstModelLeaf(cm.get("model"));
                    if (r != null) return r;
                }
            }
        }
        for (Object c : arr(n.get("models"))) {
            Map<String, Object> r = firstModelLeaf(c);
            if (r != null) return r;
        }
        return null;
    }

    private static void collectTextures(Path root, String modelId, Map<String, String> out, int depth) throws IOException {
        if (depth > 8) return;
        String[] p = split(modelId);
        Path mf = root.resolve("assets").resolve(p[0]).resolve("models").resolve(p[1] + ".json");
        if (!Files.isRegularFile(mf)) return;
        Map<String, Object> m;
        try {
            m = obj(Json.parse(Files.readString(mf)));
        } catch (RuntimeException e) {
            return;
        }
        if (m == null) return;
        if (m.get("parent") instanceof String parent) collectTextures(root, parent, out, depth + 1);
        Map<String, Object> tex = obj(m.get("textures"));
        if (tex != null) {
            for (Map.Entry<String, Object> e : tex.entrySet()) {
                if (e.getValue() instanceof String t) out.put(e.getKey(), t);
            }
        }
    }

    private static Image decode(InputStream in) throws IOException {
        java.awt.image.BufferedImage bi = ImageIO.read(in);
        if (bi == null) return null;
        int w = bi.getWidth(), h = bi.getHeight();
        int[] px = new int[w * h];
        bi.getRGB(0, 0, w, h, px, 0, w);
        return new Image(w, h, px);
    }

    private static Image crop(Image img, int w, int h) {
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(img.argb(), y * img.w(), px, y * w, w);
        return new Image(w, h, px);
    }

    private static void blend(Image canvas, Image layer, Integer tint) {
        int tr = 255, tg = 255, tb = 255;
        if (tint != null) {
            tr = (tint >> 16) & 0xFF;
            tg = (tint >> 8) & 0xFF;
            tb = tint & 0xFF;
        }
        for (int y = 0; y < canvas.h(); y++) {
            for (int x = 0; x < canvas.w(); x++) {
                int sx = x * layer.w() / canvas.w();
                int sy = y * layer.h() / canvas.h();
                int c = layer.argb()[sy * layer.w() + sx];
                int a = c >>> 24;
                if (a == 0) continue;
                int r = ((c >> 16) & 0xFF) * tr / 255;
                int g = ((c >> 8) & 0xFF) * tg / 255;
                int b = (c & 0xFF) * tb / 255;
                int d = canvas.argb()[y * canvas.w() + x];
                int da = d >>> 24;
                int oa = a + da * (255 - a) / 255;
                if (oa == 0) continue;
                int or = (r * a + ((d >> 16) & 0xFF) * da * (255 - a) / 255) / oa;
                int og = (g * a + ((d >> 8) & 0xFF) * da * (255 - a) / 255) / oa;
                int ob = (b * a + (d & 0xFF) * da * (255 - a) / 255) / oa;
                canvas.argb()[y * canvas.w() + x] = (oa << 24) | (or << 16) | (og << 8) | ob;
            }
        }
    }
}
