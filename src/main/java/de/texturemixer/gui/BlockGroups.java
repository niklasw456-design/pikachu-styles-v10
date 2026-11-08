package de.texturemixer.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Fasst Block-Texturen zu einem Block zusammen: acacia_door_top + acacia_door_bottom = "Acacia Door".
 * Die einzelnen Seiten (Oben, Seite, Unten ...) erscheinen nur im Seiten-Menü des Blocks, nicht in der Liste.
 * Gespeichert wird weiterhin pro Textur ("block:<textur>"), die Config bleibt also kompatibel.
 */
public final class BlockGroups {
    private BlockGroups() {}

    /** Eine Textur eines Blocks. rank: je kleiner, desto eher als Vorschaubild geeignet. */
    public record Face(String tex, String label, int rank) {}

    public record Group(String base, String title, List<Face> faces) {
        public boolean multi() {
            return faces.size() > 1;
        }

        /** Beste Textur für die Vorschau. have = Texturen, die das Pack hat (null = egal). */
        public String rep(Set<String> have) {
            for (Face f : faces) { // faces ist nach rank sortiert
                if (have == null || have.contains(f.tex())) return f.tex();
            }
            return faces.get(0).tex();
        }

        public boolean matches(String q) {
            if (q.isEmpty()) return true;
            String spaced = q.replace('_', ' ');
            String underscored = q.replace(' ', '_');
            if (title.toLowerCase(Locale.ROOT).contains(spaced)) return true;
            for (Face f : faces) {
                if (f.tex().contains(underscored)) return true;
            }
            return false;
        }
    }

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("top", "Oben"), Map.entry("bottom", "Unten"), Map.entry("side", "Seite"),
            Map.entry("sides", "Seiten"), Map.entry("front", "Vorne"), Map.entry("back", "Hinten"),
            Map.entry("end", "Ende"), Map.entry("inner", "Innen"), Map.entry("inside", "Innen"),
            Map.entry("on", "An"), Map.entry("off", "Aus"), Map.entry("lit", "Leuchtend"),
            Map.entry("e", "E"));

    private static int rankOf(String token) {
        return switch (token) {
            case "front" -> 1;
            case "side", "sides" -> 2;
            case "top" -> 3;
            case "bottom" -> 4;
            default -> 5;
        };
    }

    public static String pretty(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /** Baut die alphabetische Gruppenliste aus allen Textur-Namen. */
    public static List<Group> build(Collection<String> names) {
        Map<String, List<Face>> byBase = new TreeMap<>();
        for (String name : names) {
            String[] t = name.split("_");
            int n = t.length;
            while (n > 1 && LABELS.containsKey(t[n - 1])) n--;
            StringBuilder base = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) base.append('_');
                base.append(t[i]);
            }
            StringBuilder label = new StringBuilder();
            int rank = n == t.length ? 0 : 99;
            for (int i = n; i < t.length; i++) {
                if (label.length() > 0) label.append(' ');
                label.append(LABELS.get(t[i]));
                rank = Math.min(rank, rankOf(t[i]));
            }
            if (n == t.length) label.append("Basis");
            byBase.computeIfAbsent(base.toString(), k -> new ArrayList<>())
                    .add(new Face(name, label.toString(), rank));
        }

        List<Group> out = new ArrayList<>();
        for (Map.Entry<String, List<Face>> e : byBase.entrySet()) {
            List<Face> faces = e.getValue();
            faces.sort((a, b) -> a.rank() != b.rank() ? Integer.compare(a.rank(), b.rank()) : a.tex().compareTo(b.tex()));
            String title = faces.size() == 1 ? pretty(faces.get(0).tex()) : pretty(e.getKey());
            out.add(new Group(e.getKey(), title, List.copyOf(faces)));
        }
        out.sort((a, b) -> a.title().compareToIgnoreCase(b.title()));
        return out;
    }
}
