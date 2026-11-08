package de.texturemixer;

import de.texturemixer.render.ModelRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 3D-Item-Modelle aus Packs: liest items/&lt;id&gt;.json und die Modell-Dateien (models/...json) samt Texturen,
 * rendert sie wie im Inventar und kann alle dafür nötigen Dateien ins Ausgabe-Pack kopieren.
 * Gibt es kein eigenes Modell mit "elements" (also nur ein flaches Icon), liefern alle Methoden null bzw. 0.
 */
public final class ItemModelSupport {
    private ItemModelSupport() {}

    /** Gelesenes Modell plus alle Dateien des Packs, die dazu gehören (Pfade relativ zum Pack, mit "/"). */
    public record Resolved(ModelRenderer.Model model, Set<String> files) {}

    /** Vorschaubild (ARGB, FRAME x FRAME) oder null, wenn das Pack kein eigenes 3D-Modell hat. */
    public static int[] preview(Path root, String itemId, Function<String, byte[]> vanilla) throws IOException {
        Resolved r = resolve(root, itemId, vanilla);
        return r == null ? null : ModelRenderer.render(r.model());
    }

    /** Kopiert Item-Definition, Modelle und Texturen des Modells nach outRoot (Ordner mit assets/). Gibt die Anzahl zurück. */
    public static int copy(Path root, String itemId, Path outRoot, Function<String, byte[]> vanilla) throws IOException {
        Resolved r = resolve(root, itemId, vanilla);
        if (r == null) return 0;
        int n = 0;
        for (String rel : r.files()) {
            Path src = root.resolve(rel);
            if (!Files.isRegularFile(src)) continue;
            Path dst = outRoot.resolve(rel);
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            n++;
        }
        return n;
    }

    // ---------- Auflösen ----------

    private static final class Raw {
        Map<String, Object> json;
        boolean generated;
    }

    @SuppressWarnings("unchecked")
    public static Resolved resolve(Path root, String itemId, Function<String, byte[]> vanilla) throws IOException {
        Set<String> files = new LinkedHashSet<>();

        // 1) welches Modell? (items/<id>.json ab 1.21.4, sonst models/item/<id>.json)
        String modelId = null;
        String defRel = "assets/minecraft/items/" + itemId + ".json";
        Path def = root.resolve(defRel);
        if (Files.isRegularFile(def)) {
            try {
                Object parsed = Json.parse(Files.readString(def));
                modelId = findModelId(parsed, 0);
                files.add(defRel);
            } catch (Exception ignored) {
                // kaputte Definition: wie ohne behandeln
            }
        }
        if (modelId == null) {
            if (!Files.isRegularFile(root.resolve("assets/minecraft/models/item/" + itemId + ".json"))) return null;
            modelId = "minecraft:item/" + itemId;
            files.remove(defRel);
        }

        // 2) Modell samt Eltern zusammensetzen (Kind überschreibt Eltern)
        Map<String, Object> textures = new LinkedHashMap<>();
        List<Object> elements = null;
        Map<String, Object> gui = null;
        String guiLight = null;
        String cur = modelId;
        for (int depth = 0; depth < 12 && cur != null; depth++) {
            String ns = ns(cur);
            String path = path(cur);
            if (ns.equals("minecraft") && (path.equals("builtin/generated") || path.equals("builtin/entity")
                    || path.equals("builtin/missing"))) {
                break;
            }
            Map<String, Object> j = null;
            String rel = "assets/" + ns + "/models/" + path + ".json";
            Path p = root.resolve(rel);
            if (Files.isRegularFile(p)) {
                j = (Map<String, Object>) Json.parse(Files.readString(p));
                files.add(rel);
            } else if (ns.equals("minecraft")) {
                byte[] vb = vanilla.apply("assets/minecraft/models/" + path + ".json");
                if (vb != null) j = (Map<String, Object>) Json.parse(new String(vb, java.nio.charset.StandardCharsets.UTF_8));
            }
            if (j == null) break;

            if (j.get("textures") instanceof Map<?, ?> tm) {
                for (Map.Entry<?, ?> e : tm.entrySet()) {
                    textures.putIfAbsent(String.valueOf(e.getKey()), e.getValue());
                }
            }
            if (elements == null && j.get("elements") instanceof List<?> el) elements = (List<Object>) el;
            if (gui == null && j.get("display") instanceof Map<?, ?> dm && dm.get("gui") instanceof Map<?, ?> g) {
                gui = (Map<String, Object>) g;
            }
            if (guiLight == null && j.get("gui_light") instanceof String s) guiLight = s;
            cur = j.get("parent") instanceof String s ? s : null;
        }
        if (elements == null || elements.isEmpty()) return null; // flaches Icon

        // 3) Elemente lesen, Texturen laden
        Map<String, ModelRenderer.Tex> loaded = new HashMap<>();
        List<ModelRenderer.Element> els = new ArrayList<>();
        for (Object eo : elements) {
            if (!(eo instanceof Map<?, ?> em)) continue;
            double[] from = vec(em.get("from"), null);
            double[] to = vec(em.get("to"), null);
            if (from == null || to == null || !(em.get("faces") instanceof Map<?, ?> fm)) continue;

            double[] origin = {8, 8, 8};
            String axis = null;
            double angle = 0;
            boolean rescale = false;
            if (em.get("rotation") instanceof Map<?, ?> rm) {
                double[] o = vec(rm.get("origin"), origin);
                origin = o;
                axis = rm.get("axis") instanceof String s ? s : null;
                angle = num(rm.get("angle"), 0);
                rescale = Boolean.TRUE.equals(rm.get("rescale"));
            }
            boolean shade = !Boolean.FALSE.equals(em.get("shade"));

            Map<String, ModelRenderer.Face> faces = new LinkedHashMap<>();
            for (Map.Entry<?, ?> fe : fm.entrySet()) {
                if (!(fe.getValue() instanceof Map<?, ?> fd)) continue;
                String texRef = fd.get("texture") instanceof String s ? s : null;
                String texId = resolveTexture(texRef, textures);
                if (texId == null) continue;
                String key = ns(texId) + ":" + path(texId);
                if (!loaded.containsKey(key)) {
                    ModelRenderer.Tex t = loadTexture(root, texId, files, vanilla);
                    if (t == null) continue;
                    loaded.put(key, t);
                }
                double[] uv = vec4(fd.get("uv"));
                int rot = (int) num(fd.get("rotation"), 0);
                faces.put(String.valueOf(fe.getKey()), new ModelRenderer.Face(uv, key, rot));
            }
            if (!faces.isEmpty()) els.add(new ModelRenderer.Element(from, to, origin, axis, angle, rescale, shade, faces));
        }
        if (els.isEmpty()) return null;

        double[] rotation = {0, 0, 0}, translation = {0, 0, 0}, scale = {1, 1, 1};
        if (gui != null) {
            rotation = vec(gui.get("rotation"), rotation);
            translation = vec(gui.get("translation"), translation);
            scale = vec(gui.get("scale"), scale);
        }
        boolean front = "front".equals(guiLight);
        return new Resolved(new ModelRenderer.Model(els, loaded, rotation, translation, scale, front), files);
    }

    /** Sucht in einer Item-Definition die erste Modell-ID (bei Weichen: fallback / on_false / erster Fall). */
    @SuppressWarnings("unchecked")
    private static String findModelId(Object node, int depth) {
        if (depth > 8 || !(node instanceof Map<?, ?>)) return null;
        Map<String, Object> m = (Map<String, Object>) node;
        Object model = m.get("model");
        if (model instanceof String s) {
            Object type = m.get("type");
            if (type == null || "minecraft:model".equals(type) || "model".equals(type)) return s;
        }
        if (model instanceof Map<?, ?>) {
            String r = findModelId(model, depth + 1);
            if (r != null) return r;
        }
        for (String k : new String[]{"fallback", "on_false", "on_true"}) {
            String r = findModelId(m.get(k), depth + 1);
            if (r != null) return r;
        }
        for (String k : new String[]{"models", "cases", "entries"}) {
            if (m.get(k) instanceof List<?> l) {
                for (Object o : l) {
                    String r = findModelId(o, depth + 1);
                    if (r != null) return r;
                }
            }
        }
        return null;
    }

    private static String resolveTexture(String ref, Map<String, Object> textures) {
        String cur = ref;
        for (int i = 0; i < 12 && cur != null; i++) {
            if (!cur.startsWith("#")) return cur;
            Object next = textures.get(cur.substring(1));
            cur = next instanceof String s ? s : null;
        }
        return null;
    }

    private static ModelRenderer.Tex loadTexture(Path root, String texId, Set<String> files,
                                                 Function<String, byte[]> vanilla) throws IOException {
        String ns = ns(texId);
        String path = path(texId);
        String rel = "assets/" + ns + "/textures/" + path + ".png";
        byte[] png = null;
        boolean strip = false;
        Path p = root.resolve(rel);
        if (Files.isRegularFile(p)) {
            png = Files.readAllBytes(p);
            files.add(rel);
            Path meta = root.resolve(rel + ".mcmeta");
            if (Files.isRegularFile(meta)) {
                files.add(rel + ".mcmeta");
                strip = true;
            }
        } else if (ns.equals("minecraft")) {
            png = vanilla.apply("assets/minecraft/textures/" + path + ".png");
        }
        if (png == null) return null;
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(png));
        if (bi == null) return null;
        int w = bi.getWidth(), h = bi.getHeight();
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) argb[y * w + x] = bi.getRGB(x, y);
        }
        return new ModelRenderer.Tex(argb, w, h, strip && h > w && h % w == 0);
    }

    // ---------- Hilfen ----------

    private static String ns(String id) {
        int i = id.indexOf(':');
        return i < 0 ? "minecraft" : id.substring(0, i);
    }

    private static String path(String id) {
        int i = id.indexOf(':');
        return i < 0 ? id : id.substring(i + 1);
    }

    private static double num(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }

    private static double[] vec(Object o, double[] def) {
        if (o instanceof List<?> l && l.size() >= 3) {
            return new double[]{num(l.get(0), 0), num(l.get(1), 0), num(l.get(2), 0)};
        }
        return def;
    }

    private static double[] vec4(Object o) {
        if (o instanceof List<?> l && l.size() >= 4) {
            return new double[]{num(l.get(0), 0), num(l.get(1), 0), num(l.get(2), 16), num(l.get(3), 16)};
        }
        return null;
    }
}
