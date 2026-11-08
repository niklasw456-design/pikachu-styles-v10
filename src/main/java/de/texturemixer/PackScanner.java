package de.texturemixer;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Findet Texturepacks im resourcepacks-Ordner und baut daraus das Ausgabe-Pack. */
public final class PackScanner {
    public static final String OUTPUT_NAME = "texturemixer_output";

    private static final String MCMETA = """
            {
              "pack": {
                "description": "Pikachu Styles (automatisch erzeugt)",
                "pack_format": 75,
                "supported_formats": { "min_inclusive": 34, "max_inclusive": 75 }
              }
            }
            """;

    /**
     * categories: Variant-IDs (siehe Category.fullId), die das Pack unterstützt.
     * blocks: Namen aller Block-Texturen (block/<name>.png) des Packs, ohne die Blöcke mit eigenem Reiter.
     */
    public record PackInfo(String name, Path path, Set<String> categories, Set<String> blocks, long stamp) {
        public PackInfo(String name, Path path, Set<String> categories, Set<String> blocks) {
            this(name, path, categories, blocks, 0L);
        }
    }

    private record Detected(Set<String> categories, Set<String> blocks) {}

    /** Blöcke, die schon einen eigenen Reiter haben (und das Feuer), kommen nicht in die Blockliste. */
    private static final Set<String> BLOCK_EXCLUDE = Set.of("obsidian", "crying_obsidian", "cobweb", "fire_0", "fire_1");

    /** Rohdaten der Feuer-Vorschau: fire_0, fire_1, Netherrack (je PNG oder null) und die Animation (.mcmeta von fire_0). */
    public record FireData(byte[] fire0, byte[] fire1, byte[] nether, byte[] mcmeta) {}

    /**
     * Rohdaten für das Vorschaubild: entweder PNG (+ optional .mcmeta für Animation)
     * oder fertige ARGB-Pixel (argb != null), z. B. bei Tränken.
     */
    public record PreviewData(byte[] png, byte[] mcmeta, int[] argb, int w, int h) {}

    /** Rohdaten der getragenen Rüstung: outer = Helm/Brustplatte/Stiefel, legs = Hose (je PNG oder null). */
    public record BodyData(byte[] outer, byte[] legs) {}

    /** Ordner unter assets/minecraft/, die nicht zu den Texturen gehören, aber kopiert werden (Custom Sky). */
    private static final String[] ASSET_DIRS = {"optifine/sky", "mcpatcher/sky"};

    private interface RootAction<T> {
        T run(Path root) throws IOException;
    }

    private PackScanner() {}

    public static Path packsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
    }

    public static List<PackInfo> scan() {
        return scan(false);
    }

    /** Bereits gelesene Zip-Packs (Name|Änderungszeit|Größe), damit das Menü beim erneuten Öffnen sofort da ist. */
    private static final Map<String, PackInfo> SCAN_CACHE = new ConcurrentHashMap<>();

    /** force = true: nichts aus dem Zwischenspeicher nehmen (Knopf "Neu scannen"). */
    public static List<PackInfo> scan(boolean force) {
        Path dir = packsDir();
        if (!Files.isDirectory(dir)) return new ArrayList<>();

        List<Path> entries;
        try (Stream<Path> s = Files.list(dir)) {
            entries = s.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase())).toList();
        } catch (IOException e) {
            TextureMixerClient.LOGGER.warn("resourcepacks-Ordner nicht lesbar", e);
            return new ArrayList<>();
        }
        if (force) {
            SCAN_CACHE.clear();
            closeCaches();
        }

        // Packs parallel lesen (die Reihenfolge bleibt erhalten)
        return new ArrayList<>(entries.parallelStream()
                .map(PackScanner::scanOne)
                .filter(Objects::nonNull)
                .toList());
    }

    private static PackInfo scanOne(Path p) {
        String name = p.getFileName().toString();
        if (name.equals(OUTPUT_NAME)) return null;
        boolean zip = Files.isRegularFile(p) && name.toLowerCase().endsWith(".zip");
        boolean folder = Files.isDirectory(p);
        if (!zip && !folder) return null;
        try {
            long stamp = p.toFile().lastModified();
            String ck = null;
            if (zip) {
                ck = name + "|" + stamp + "|" + Files.size(p);
                PackInfo hit = SCAN_CACHE.get(ck);
                if (hit != null) return hit;
            }
            Detected d = withRoot(p, PackScanner::detect);
            PackInfo info = new PackInfo(name, p, d.categories(), d.blocks(), stamp);
            if (ck != null) SCAN_CACHE.put(ck, info);
            return info;
        } catch (Exception e) {
            TextureMixerClient.LOGGER.warn("Pack {} konnte nicht gelesen werden", name, e);
            return null;
        }
    }

    /** Baut das Ausgabe-Pack. Gibt die Anzahl erzeugter Einträge zurück (0 = Pack wurde entfernt). */
    public static int buildOutput(Map<String, String> selections, List<PackInfo> packs) throws IOException {
        Path out = packsDir().resolve(OUTPUT_NAME);
        deleteOutput(out);

        Path mc = out.resolve("assets").resolve("minecraft");
        int count = 0;
        Map<String, List<PotionSupport.Case>> potionCases = new LinkedHashMap<>();

        for (Category c : Category.ALL) {
            for (Category.Variant v : c.variants()) {
                String sel = selections.get(c.fullId(v));
                if (sel == null) continue;
                PackInfo info = packs.stream().filter(p -> p.name().equals(sel)).findFirst().orElse(null);
                if (info == null) continue;
                Files.createDirectories(mc);

                if (v.isSound()) {
                    count += withRoot(info.path(), root -> SoundSupport.export(root, mc));
                    continue;
                }

                if (v.potion() != null) {
                    Category.PotionSpec ps = v.potion();
                    String key = (c.id() + "_" + v.key()).replaceAll("[^a-z0-9_]", "_");
                    PotionSupport.Case cs = withRoot(info.path(), root -> {
                        Map<String, Object> node = PotionSupport.resolveFor(root, ps.itemDef(), ps.ids());
                        if (node == null) return null;
                        return new PotionSupport.Case(ps.ids(), PotionSupport.export(root, node, mc, key));
                    });
                    if (cs != null) potionCases.computeIfAbsent(ps.itemDef(), k -> new ArrayList<>()).add(cs);
                    continue;
                }
                count += withRoot(info.path(), root -> copyVariant(root, v, mc));
            }
        }

        // Eigenes Fadenkreuz: Vanilla-Fadenkreuz durchsichtig machen, gezeichnet wird es von CrosshairHud
        if (CustomCrosshair.ID.equals(selections.get(CustomCrosshair.KEY))) {
            Files.createDirectories(mc);
            CustomCrosshair.writeBlankSprite(mc.resolve("textures").resolve("gui").resolve("sprites")
                    .resolve("hud").resolve("crosshair.png"));
            count++;
        }

        // einzelne Blöcke (Reiter "Blöcke")
        for (Map.Entry<String, String> e : selections.entrySet()) {
            if (!e.getKey().startsWith("block:")) continue;
            String name = e.getKey().substring("block:".length());
            if (!name.matches("[a-z0-9_]+")) continue;
            PackInfo info = packs.stream().filter(p -> p.name().equals(e.getValue())).findFirst().orElse(null);
            if (info == null) continue;
            Files.createDirectories(mc);
            Category.Variant bv = Category.blockVariant(name);
            count += withRoot(info.path(), root -> copyVariant(root, bv, mc));
        }

        // Tränke: eine gemeinsame Weiche pro Item (potion / splash_potion), alle anderen Tränke bleiben Vanilla
        for (Map.Entry<String, List<PotionSupport.Case>> e : potionCases.entrySet()) {
            Object vanilla = vanillaModel(e.getKey());
            if (vanilla == null) {
                TextureMixerClient.LOGGER.warn("Vanilla-Definition für {} nicht gefunden, Trank-Texturen übersprungen", e.getKey());
                continue;
            }
            List<Object> cases = new ArrayList<>();
            for (PotionSupport.Case cs : e.getValue()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("when", new ArrayList<Object>(cs.ids()));
                cm.put("model", cs.node());
                cases.add(cm);
            }
            Map<String, Object> sel = new LinkedHashMap<>();
            sel.put("type", "minecraft:select");
            sel.put("property", "minecraft:component");
            sel.put("component", "minecraft:potion_contents");
            sel.put("cases", cases);
            sel.put("fallback", vanilla);
            Map<String, Object> top = new LinkedHashMap<>();
            top.put("model", sel);
            Path items = mc.resolve("items").resolve(e.getKey() + ".json");
            Files.createDirectories(items.getParent());
            Files.writeString(items, Json.write(top));
            count++;
        }

        if (count == 0) {
            deleteOutput(out);
            return 0;
        }
        Files.writeString(out.resolve("pack.mcmeta"), MCMETA);
        return count;
    }

    /** Liest die originale Item-Definition aus dem Spiel selbst (unabhängig von Resource Packs). */
    @SuppressWarnings("unchecked")
    private static Object vanillaModel(String itemDef) {
        try {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer("minecraft");
            if (container.isEmpty()) return null;
            Optional<Path> p = container.get().findPath("assets/minecraft/items/" + itemDef + ".json");
            if (p.isEmpty()) return null;
            Object parsed = Json.parse(Files.readString(p.get()));
            return parsed instanceof Map<?, ?> m ? ((Map<String, Object>) m).get("model") : null;
        } catch (Exception e) {
            TextureMixerClient.LOGGER.warn("Vanilla-Definition konnte nicht gelesen werden", e);
            return null;
        }
    }

    /** Liest fire_0, fire_1 und (falls vorhanden) das Netherrack eines Packs. */
    public static FireData readFire(PackInfo pack) throws IOException {
        return withRoot(pack.path(), root -> {
            Path tex = textures(root);
            byte[] f0 = firstBytes(tex, "block/fire_0.png");
            byte[] f1 = firstBytes(tex, "block/fire_1.png");
            if (f0 == null && f1 == null) return null;
            byte[] meta = firstBytes(tex, f0 != null ? "block/fire_0.png.mcmeta" : "block/fire_1.png.mcmeta");
            byte[] nether = firstBytes(tex, "block/netherrack.png");
            return new FireData(f0 != null ? f0 : f1, f0 != null ? f1 : null, nether, meta);
        });
    }

    /** Liest eine Datei aus dem Spiel selbst, z. B. "assets/minecraft/textures/block/netherrack.png". */
    public static byte[] vanillaBytes(String path) {
        try {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer("minecraft");
            return container.isEmpty() ? null : readContainer(container.get(), path);
        } catch (Exception e) {
            return null;
        }
    }

    /** Liest eine .ogg-Datei des Totem-Sounds aus einem Pack (für die Hörprobe im Menü), null = keine. */
    public static byte[] readTotemSound(PackInfo pack) throws IOException {
        return withRoot(pack.path(), SoundSupport::sample);
    }

    /** Liest das Vorschaubild einer Variante aus einem Pack. */
    public static PreviewData readPreview(PackInfo pack, Category.Variant v) throws IOException {
        if (v.isItem3d()) {
            // Eigenes 3D-Modell des Packs (z. B. End Crystal in der Hand); ohne Modell weiter mit dem flachen Icon
            int[] img = withRoot(pack.path(), root -> ItemModelSupport.preview(root, v.material(), PackScanner::vanillaBytes));
            if (img != null) {
                return new PreviewData(null, null, img, de.texturemixer.render.ModelRenderer.FRAME,
                        de.texturemixer.render.ModelRenderer.FRAME);
            }
        }
        if (v.isArmorItems()) {
            return armorItemsPreview(pack, v.material());
        }
        if (v.isLayers()) {
            return layersPreview(pack, v);
        }
        if (v.isSky()) {
            return skyPreview(pack);
        }
        if (v.potion() != null) {
            Category.PotionSpec ps = v.potion();
            return withRoot(pack.path(), root -> {
                Map<String, Object> node = PotionSupport.resolveFor(root, ps.itemDef(), ps.ids());
                if (node == null) return null;
                PotionSupport.Image img = PotionSupport.preview(root, node, ps.color());
                if (img == null) return null;
                return new PreviewData(null, null, img.argb(), img.w(), img.h());
            });
        }
        return withRoot(pack.path(), root -> {
            Path tex = textures(root);
            if (!Files.isDirectory(tex)) return null;
            Path file = null;
            for (String cand : v.previews()) {
                Path p = tex.resolve(cand);
                if (Files.isRegularFile(p)) {
                    file = p;
                    break;
                }
            }
            if (file == null && v.hand() == null) {
                List<Path> files;
                try (Stream<Path> s = Files.walk(tex)) {
                    files = s.filter(Files::isRegularFile).sorted().toList();
                }
                for (Path f : files) {
                    String rel = rel(tex, f);
                    if (rel.endsWith(".png") && v.matches(rel)) {
                        file = f;
                        break;
                    }
                }
            }
            if (file == null) return null;
            byte[] png = Files.readAllBytes(file);
            Path meta = file.resolveSibling(file.getFileName().toString() + ".mcmeta");
            byte[] mcmeta = Files.isRegularFile(meta) ? Files.readAllBytes(meta) : null;
            return new PreviewData(png, mcmeta, null, 0, 0);
        });
    }

    /** HUD-Vorschau: Bilder (z. B. Herz-Hintergrund + Herz) übereinandergelegt, links auf die Breite zugeschnitten. */
    private static PreviewData layersPreview(PackInfo pack, Category.Variant v) throws IOException {
        String[] parts = v.render().split(":", 4);
        int refW = Integer.parseInt(parts[1]);
        int cropW = Integer.parseInt(parts[2]);
        String[] paths = parts[3].split(";");
        return withRoot(pack.path(), root -> {
            Path tex = textures(root);
            List<BufferedImage> imgs = new ArrayList<>();
            BufferedImage first = null;
            for (String p : paths) {
                Path f = tex.resolve(p);
                BufferedImage bi = null;
                if (Files.isRegularFile(f)) {
                    try (InputStream in = Files.newInputStream(f)) {
                        bi = ImageIO.read(in);
                    } catch (Exception ignored) {
                    }
                }
                imgs.add(bi);
                if (first == null) first = bi;
            }
            if (first == null) return null;

            int w0 = first.getWidth();
            int h0 = Math.min(first.getHeight(), w0); // animierte Texturen: nur das erste Bild
            int cw = Math.max(1, (int) Math.round(w0 * (double) cropW / refW));
            int[] out = new int[cw * h0];
            for (BufferedImage bi : imgs) {
                if (bi == null) continue;
                int wi = bi.getWidth();
                int hi = Math.min(bi.getHeight(), wi);
                for (int y = 0; y < h0; y++) {
                    int sy = Math.min(hi - 1, y * hi / h0);
                    for (int x = 0; x < cw; x++) {
                        int sx = Math.min(wi - 1, x * wi / w0);
                        out[y * cw + x] = over(bi.getRGB(sx, sy), out[y * cw + x]);
                    }
                }
            }
            return new PreviewData(null, null, out, cw, h0);
        });
    }

    private static int over(int src, int dst) {
        int sa = src >>> 24;
        int da = dst >>> 24;
        if (sa == 255 || da == 0) return src;
        if (sa == 0) return dst;
        int oa = sa + da * (255 - sa) / 255;
        int r = (((src >> 16) & 255) * sa + ((dst >> 16) & 255) * da * (255 - sa) / 255) / oa;
        int g = (((src >> 8) & 255) * sa + ((dst >> 8) & 255) * da * (255 - sa) / 255) / oa;
        int b = ((src & 255) * sa + (dst & 255) * da * (255 - sa) / 255) / oa;
        return (oa << 24) | (r << 16) | (g << 8) | b;
    }

    /** Sky-Vorschau: die Würfel-Textur (3 Spalten x 2 Reihen, z. B. cloud1.png) verkleinert auf 132x88. */
    private static PreviewData skyPreview(PackInfo pack) throws IOException {
        return withRoot(pack.path(), root -> {
            Path mcRoot = root.resolve("assets").resolve("minecraft");
            List<Path> files = listFiles(mcRoot, true).stream()
                    .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted().toList();
            BufferedImage best = null;
            for (Path f : files) {
                BufferedImage bi;
                try (InputStream in = Files.newInputStream(f)) {
                    bi = ImageIO.read(in);
                } catch (Exception e) {
                    continue;
                }
                if (bi == null) continue;
                if (bi.getWidth() * 2 == bi.getHeight() * 3) { // 3:2 = Würfel-Textur
                    best = bi;
                    break;
                }
                if (best == null) best = bi;
            }
            if (best == null) return null;

            int tw = 132;
            int th = 88;
            int sw = best.getWidth();
            int sh = best.getHeight();
            int[] out = new int[tw * th];
            for (int ty = 0; ty < th; ty++) {
                int y0 = ty * sh / th;
                int y1 = Math.max(y0 + 1, (ty + 1) * sh / th);
                int stepY = Math.max(1, (y1 - y0) / 4);
                for (int tx = 0; tx < tw; tx++) {
                    int x0 = tx * sw / tw;
                    int x1 = Math.max(x0 + 1, (tx + 1) * sw / tw);
                    int stepX = Math.max(1, (x1 - x0) / 4);
                    long r = 0, g = 0, b = 0;
                    int n = 0;
                    for (int y = y0; y < y1 && y < sh; y += stepY) {
                        for (int x = x0; x < x1 && x < sw; x += stepX) {
                            int c = best.getRGB(x, y);
                            r += (c >> 16) & 255;
                            g += (c >> 8) & 255;
                            b += c & 255;
                            n++;
                        }
                    }
                    if (n == 0) n = 1;
                    out[ty * tw + tx] = 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
                }
            }
            return new PreviewData(null, null, out, tw, th);
        });
    }

    /** Alle Dateien unter base: bei asset = true nur in den ASSET_DIRS (Sky), sonst alles (Texturen). */
    private static List<Path> listFiles(Path base, boolean asset) throws IOException {
        List<Path> out = new ArrayList<>();
        List<Path> dirs = new ArrayList<>();
        if (asset) {
            for (String d : ASSET_DIRS) dirs.add(base.resolve(d));
        } else {
            dirs.add(base);
        }
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir)) {
                out.addAll(s.filter(Files::isRegularFile).toList());
            }
        }
        return out;
    }

    /**
     * Helm, Brustplatte, Hose und Stiefel (Item-Icons) als 2x2-Bild: oben Helm/Brustplatte, unten Hose/Stiefel.
     * Teile, die das Pack nicht liefert, werden aus dem Spiel ergänzt (so bleiben sie auch im Spiel unverändert).
     */
    private static PreviewData armorItemsPreview(PackInfo pack, String material) throws IOException {
        return withRoot(pack.path(), root -> {
            Path tex = textures(root);
            String[] parts = {"helmet", "chestplate", "leggings", "boots"};
            BufferedImage[] imgs = new BufferedImage[4];
            int tile = 0;
            for (int i = 0; i < 4; i++) {
                Path f = tex.resolve("item/" + material + "_" + parts[i] + ".png");
                if (!Files.isRegularFile(f)) continue;
                try (InputStream in = Files.newInputStream(f)) {
                    BufferedImage bi = ImageIO.read(in);
                    if (bi == null) continue;
                    imgs[i] = bi;
                    tile = Math.max(tile, bi.getWidth());
                }
            }
            if (tile <= 0) return null;

            // Fehlende Teile aus dem Spiel nachladen
            for (int i = 0; i < 4; i++) {
                if (imgs[i] != null) continue;
                byte[] vb = vanillaBytes("assets/minecraft/textures/item/" + material + "_" + parts[i] + ".png");
                if (vb == null) continue;
                try (InputStream in = new java.io.ByteArrayInputStream(vb)) {
                    BufferedImage bi = ImageIO.read(in);
                    if (bi == null) continue;
                    imgs[i] = bi;
                    tile = Math.max(tile, bi.getWidth());
                }
            }

            int size = tile * 2;
            int[] out = new int[size * size];
            for (int i = 0; i < 4; i++) {
                BufferedImage bi = imgs[i];
                if (bi == null) continue;
                int src = bi.getWidth(); // animierte Texturen: nur das erste (quadratische) Bild
                int ox = (i % 2) * tile;
                int oy = (i / 2) * tile;
                for (int y = 0; y < tile; y++) {
                    for (int x = 0; x < tile; x++) {
                        int sx = x * src / tile;
                        int sy = y * src / tile;
                        if (sy >= bi.getHeight()) continue;
                        out[(oy + y) * size + ox + x] = bi.getRGB(sx, sy);
                    }
                }
            }
            return new PreviewData(null, null, out, size, size);
        });
    }

    /** Liest die getragene Rüstung eines Packs (neues Format zuerst, sonst altes Format vor 1.21.2). */
    public static BodyData readBody(PackInfo pack, String material) throws IOException {
        return withRoot(pack.path(), root -> {
            Path tex = textures(root);
            byte[] outer = firstBytes(tex,
                    "entity/equipment/humanoid/" + material + ".png",
                    "models/armor/" + material + "_layer_1.png");
            byte[] legs = firstBytes(tex,
                    "entity/equipment/humanoid_leggings/" + material + ".png",
                    "models/armor/" + material + "_layer_2.png");
            if (outer == null && legs == null) return null;
            return new BodyData(outer, legs);
        });
    }

    /** Die originale Rüstung aus dem Spiel selbst (für die Karte "Standard"). */
    public static BodyData readVanillaBody(String material) {
        try {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer("minecraft");
            if (container.isEmpty()) return null;
            String base = "assets/minecraft/textures/entity/equipment/";
            byte[] outer = readContainer(container.get(), base + "humanoid/" + material + ".png");
            byte[] legs = readContainer(container.get(), base + "humanoid_leggings/" + material + ".png");
            if (outer == null && legs == null) return null;
            return new BodyData(outer, legs);
        } catch (Exception e) {
            TextureMixerClient.LOGGER.warn("Vanilla-Rüstung konnte nicht gelesen werden", e);
            return null;
        }
    }

    private static byte[] readContainer(ModContainer container, String path) throws IOException {
        Optional<Path> p = container.findPath(path);
        return p.isPresent() && Files.isRegularFile(p.get()) ? Files.readAllBytes(p.get()) : null;
    }

    private static byte[] firstBytes(Path tex, String... candidates) throws IOException {
        for (String c : candidates) {
            Path p = tex.resolve(c);
            if (Files.isRegularFile(p)) return Files.readAllBytes(p);
        }
        return null;
    }

    private static Detected detect(Path root) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        Set<String> blocks = new java.util.TreeSet<>();
        Path tex = textures(root);
        List<String> rels = new ArrayList<>();
        if (Files.isDirectory(tex)) {
            try (Stream<Path> s = Files.walk(tex)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) rels.add(rel(tex, p));
            }
        }
        Set<String> relSet = new HashSet<>(rels);
        for (String rel : rels) {
            if (rel.startsWith("block/") && rel.indexOf('/', 6) < 0 && rel.endsWith(".png")) {
                String name = rel.substring(6, rel.length() - 4);
                if (name.matches("[a-z0-9_]+") && !BLOCK_EXCLUDE.contains(name)) blocks.add(name);
            }
        }

        Path mcRoot = root.resolve("assets").resolve("minecraft");
        List<String> assetRels = new ArrayList<>();
        for (Path p : listFiles(mcRoot, true)) assetRels.add(rel(mcRoot, p));

        for (Category c : Category.ALL) {
            for (Category.Variant v : c.variants()) {
                boolean ok = false;
                if (v.isSound()) {
                    ok = SoundSupport.plan(root) != null;
                } else if (v.potion() != null) {
                    ok = PotionSupport.resolveFor(root, v.potion().itemDef(), v.potion().ids()) != null;
                } else if (v.hand() != null) {
                    for (String s : v.hand().sources()) {
                        if (relSet.contains(s)) {
                            ok = true;
                            break;
                        }
                    }
                } else {
                    for (String rel : rels) {
                        if (v.matches(rel)) {
                            ok = true;
                            break;
                        }
                    }
                    if (!ok && v.hasAssetRules()) {
                        for (String rel : assetRels) {
                            if (v.matches(rel, true)) {
                                ok = true;
                                break;
                            }
                        }
                    }
                }
                if (ok) found.add(c.fullId(v));
            }
        }
        return new Detected(found, blocks);
    }

    private static int copyVariant(Path root, Category.Variant v, Path mc) throws IOException {
        Path tex = textures(root);
        Path outTex = mc.resolve("textures");

        // In der Hand bei Blöcken/Netzen: Textur + eigenes Item-Modell + Item-Definition erzeugen
        if (v.hand() != null) {
            Category.HandSpec h = v.hand();
            for (String s : h.sources()) {
                Path src = tex.resolve(s);
                if (!Files.isRegularFile(src)) continue;

                String texName = "item/texturemixer/" + h.itemId() + "_hand";
                Path dstPng = outTex.resolve(texName + ".png");
                Files.createDirectories(dstPng.getParent());
                Files.copy(src, dstPng, StandardCopyOption.REPLACE_EXISTING);
                Path meta = src.resolveSibling(src.getFileName().toString() + ".mcmeta");
                if (Files.isRegularFile(meta)) {
                    Files.copy(meta, outTex.resolve(texName + ".png.mcmeta"), StandardCopyOption.REPLACE_EXISTING);
                }

                String modelId = "minecraft:item/texturemixer/" + h.itemId() + "_hand";
                String parent = h.cube() ? "minecraft:block/cube_all" : "minecraft:item/generated";
                String key = h.cube() ? "all" : "layer0";
                Path model = mc.resolve("models").resolve("item").resolve("texturemixer")
                        .resolve(h.itemId() + "_hand.json");
                Files.createDirectories(model.getParent());
                Files.writeString(model, "{\"parent\":\"" + parent + "\",\"textures\":{\"" + key + "\":\"" + modelId + "\"}}");

                Path items = mc.resolve("items").resolve(h.itemId() + ".json");
                Files.createDirectories(items.getParent());
                Files.writeString(items, "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + modelId + "\"}}");
                return 1;
            }
            return 0;
        }

        // 3D-Item-Modell: Item-Definition, Modelle und Texturen des Modells mitnehmen
        int n = v.isItem3d()
                ? ItemModelSupport.copy(root, v.material(), mc.getParent().getParent(), PackScanner::vanillaBytes)
                : 0;
        Path mcRoot = root.resolve("assets").resolve("minecraft");
        for (boolean asset : new boolean[]{false, true}) {
            boolean any = false;
            for (Category.Rule r : v.rules()) {
                if (r.asset() == asset) any = true;
            }
            Path base = asset ? mcRoot : tex;
            if (!any || !Files.isDirectory(base)) continue;
            Path outBase = asset ? mc : outTex;
            List<Path> files = listFiles(base, asset);
            for (Category.Rule r : v.rules()) {
                if (r.asset() != asset) continue;
                for (Path p : files) {
                    String rel = rel(base, p);
                    if (!rel.startsWith(r.src())) continue;
                    Path target = outBase.resolve(r.dst() + rel.substring(r.src().length()));
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    n++;
                }
            }
        }
        return n;
    }

    private static Path textures(Path root) {
        return root.resolve("assets").resolve("minecraft").resolve("textures");
    }

    private static String rel(Path base, Path p) {
        return base.relativize(p).toString().replace('\\', '/');
    }

    /** Geöffnete Zip-Dateien: wird ein Pack mehrfach gebraucht (Vorschauen), bleibt es offen statt jedes Mal neu geöffnet zu werden. */
    private static final Map<Path, FileSystem> OPEN = new java.util.HashMap<>();

    private static <T> T withRoot(Path pack, RootAction<T> action) throws IOException {
        if (Files.isDirectory(pack)) return action.run(pack);
        FileSystem fs;
        synchronized (OPEN) {
            fs = OPEN.get(pack);
        }
        if (fs == null || !fs.isOpen()) {
            FileSystem fresh = FileSystems.newFileSystem(pack);
            synchronized (OPEN) {
                FileSystem other = OPEN.get(pack);
                if (other != null && other.isOpen()) {
                    fresh.close();
                    fs = other;
                } else {
                    OPEN.put(pack, fresh);
                    fs = fresh;
                }
            }
        }
        return action.run(fs.getPath("/"));
    }

    /** Schließt alle offenen Zip-Dateien (beim Schließen des Menüs und vor dem Neubau des Ausgabe-Packs). */
    public static void closeCaches() {
        List<FileSystem> list;
        synchronized (OPEN) {
            list = new ArrayList<>(OPEN.values());
            OPEN.clear();
        }
        for (FileSystem fs : list) {
            try {
                fs.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void deleteOutput(Path out) throws IOException {
        if (!Files.exists(out) || !out.getFileName().toString().equals(OUTPUT_NAME)) return;
        List<Path> all;
        try (Stream<Path> s = Files.walk(out)) {
            all = s.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : all) Files.deleteIfExists(p);
    }
}
