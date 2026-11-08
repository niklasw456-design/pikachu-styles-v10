package de.texturemixer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Totem-Pop-Sound (Ereignis "item.totem.use") aus einem Pack.
 *
 * Zwei Arten, wie Packs den Sound ersetzen, werden erkannt:
 * 1. Eintrag "item.totem.use" in assets/minecraft/sounds.json (mit beliebigen Dateinamen),
 * 2. einfach .ogg-Dateien unter assets/minecraft/sounds/item/totem/ (z. B. use.ogg).
 *
 * Beim Anwenden werden die benutzten .ogg-Dateien kopiert und eine eigene sounds.json mit
 * "replace": true geschrieben, damit nur noch der gewählte Sound spielt.
 */
public final class SoundSupport {
    public static final String EVENT = "item.totem.use";

    private static final Pattern NS = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    /** files: {Namespace, Pfad ohne Endung} relativ zu assets/&lt;ns&gt;/sounds/. sounds: Einträge für die sounds.json. */
    public record Plan(List<String[]> files, List<Object> sounds) {}

    private SoundSupport() {}

    /** Findet den Totem-Sound in einem Pack (Wurzel des Packs), sonst null. */
    public static Plan plan(Path root) throws IOException {
        Path assets = root.resolve("assets");
        Path mc = assets.resolve("minecraft");

        // 1) sounds.json mit eigenem Eintrag
        Path sj = mc.resolve("sounds.json");
        if (Files.isRegularFile(sj)) {
            try {
                Object parsed = Json.parse(Files.readString(sj));
                if (parsed instanceof Map<?, ?> m && m.get(EVENT) instanceof Map<?, ?> ev
                        && ev.get("sounds") instanceof List<?> list) {
                    List<String[]> files = new ArrayList<>();
                    List<Object> sounds = new ArrayList<>();
                    for (Object o : list) {
                        String name;
                        Object keep;
                        if (o instanceof String s) {
                            name = s;
                            keep = s;
                        } else if (o instanceof Map<?, ?> om && om.get("name") instanceof String s) {
                            if ("event".equals(om.get("type"))) continue; // Verweis auf ein anderes Ereignis
                            name = s;
                            Map<String, Object> copy = new LinkedHashMap<>();
                            for (Map.Entry<?, ?> e : om.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
                            keep = copy;
                        } else {
                            continue;
                        }
                        String[] nf = split(name);
                        if (nf == null) continue;
                        if (!Files.isRegularFile(oggPath(assets, nf))) continue;
                        files.add(nf);
                        sounds.add(keep);
                    }
                    if (!files.isEmpty()) return new Plan(files, sounds);
                }
            } catch (Exception ignored) {
                // kaputte sounds.json: weiter mit den Dateien
            }
        }

        // 2) einfach .ogg-Dateien im Totem-Ordner
        Path dir = mc.resolve("sounds").resolve("item").resolve("totem");
        if (Files.isDirectory(dir)) {
            List<Path> oggs;
            try (Stream<Path> s = Files.walk(dir)) {
                oggs = s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".ogg"))
                        .sorted().toList();
            }
            List<String[]> files = new ArrayList<>();
            List<Object> sounds = new ArrayList<>();
            for (Path p : oggs) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                String name = "item/totem/" + rel.substring(0, rel.length() - 4);
                String[] nf = split(name);
                if (nf == null) continue;
                files.add(nf);
                sounds.add(name);
            }
            if (!files.isEmpty()) return new Plan(files, sounds);
        }
        return null;
    }

    /** Kopiert die Sounds ins Ausgabe-Pack und schreibt die sounds.json. mc = assets/minecraft im Ausgabe-Pack. */
    public static int export(Path root, Path mc) throws IOException {
        Plan p = plan(root);
        if (p == null) return 0;
        Path assetsIn = root.resolve("assets");
        Path assetsOut = mc.getParent();
        int n = 0;
        for (String[] f : p.files()) {
            Path src = oggPath(assetsIn, f);
            Path dst = oggPath(assetsOut, f);
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            n++;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("replace", Boolean.TRUE);
        event.put("sounds", p.sounds());
        Map<String, Object> top = new LinkedHashMap<>();
        top.put(EVENT, event);
        Files.createDirectories(mc);
        Files.writeString(mc.resolve("sounds.json"), Json.write(top));
        return n + 1;
    }

    /** Eine (zufällig gewählte) .ogg-Datei des Packs als Bytes, für die Vorschau. */
    public static byte[] sample(Path root) throws IOException {
        Plan p = plan(root);
        if (p == null) return null;
        String[] f = p.files().get(ThreadLocalRandom.current().nextInt(p.files().size()));
        return Files.readAllBytes(oggPath(root.resolve("assets"), f));
    }

    private static Path oggPath(Path assets, String[] nf) {
        Path p = assets.resolve(nf[0]).resolve("sounds");
        for (String part : nf[1].split("/")) p = p.resolve(part);
        return p.resolveSibling(p.getFileName().toString() + ".ogg");
    }

    /** "ns:pfad" oder "pfad" in {Namespace, Pfad}; null bei unzulässigen Zeichen (Schutz vor "..", absoluten Pfaden). */
    private static String[] split(String name) {
        String ns = "minecraft";
        String path = name.trim();
        int i = path.indexOf(':');
        if (i >= 0) {
            ns = path.substring(0, i);
            path = path.substring(i + 1);
        }
        if (!NS.matcher(ns).matches() || !PATH.matcher(path).matches()) return null;
        if (path.startsWith("/") || path.endsWith("/") || path.contains("..") || path.contains("//")) return null;
        return new String[]{ns, path};
    }
}
