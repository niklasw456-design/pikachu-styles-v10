package de.texturemixer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MixerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MixerConfig instance = new MixerConfig();

    /** Kategorie-ID -> Dateiname des gewählten Packs. Fehlt ein Eintrag, wird nichts überschrieben. */
    public Map<String, String> selections = new LinkedHashMap<>();

    /**
     * Profile: Name -> gespeicherte Auswahl. "selections" ist immer die Auswahl des aktiven Profils
     * (beim Speichern und Wechseln wird sie in "profiles" abgelegt).
     */
    public Map<String, Map<String, String>> profiles = new LinkedHashMap<>();
    public String activeProfile = "Profil 1";

    /** GLFW-Keycode zum Öffnen des Menüs, -1 = nicht belegt (Standard). */
    public int openKey = -1;

    /** Das selbst gezeichnete Fadenkreuz (gilt für alle Profile, ausgewählt wird es pro Profil im Reiter Fadenkreuz). */
    public CustomCrosshair crosshair = new CustomCrosshair();

    public static MixerConfig get() {
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("texturemixer.json");
    }

    public static void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            MixerConfig loaded = GSON.fromJson(Files.readString(f), MixerConfig.class);
            if (loaded != null) {
                if (loaded.selections == null) loaded.selections = new LinkedHashMap<>();
                loaded.migrate();
                loaded.normalize();
                instance = loaded;
            }
        } catch (Exception e) {
            TextureMixerClient.LOGGER.warn("Konnte Config nicht lesen, nutze Standardwerte", e);
        }
    }

    /**
     * Alte Configs: Die Rüstung hatte früher nur einen Eintrag (z. B. "diamond_armor"). Jetzt gibt es
     * "diamond_armor#item" und "diamond_armor#body" - der alte Eintrag gilt dann für beide.
     */
    private void migrate() {
        migrateLegacyIds();
        for (Category c : Category.ALL) {
            if (!c.dual() || !selections.containsKey(c.id())) continue;
            String pack = selections.remove(c.id());
            for (Category.Variant v : c.variants()) {
                selections.putIfAbsent(c.fullId(v), pack);
            }
        }
    }

    /**
     * Netherite- und Diamant-Werkzeuge/-Rüstung hatten früher eigene Reiter ("diamond_sword", "netherite_armor#body").
     * Jetzt gibt es einen Reiter pro Werkzeug mit Materialleiste ("sword#diamond", "armor#netherite_body").
     */
    private void migrateLegacyIds() {
        for (String mat : new String[]{"netherite", "diamond"}) {
            for (String t : new String[]{"sword", "axe", "pickaxe", "shovel"}) {
                move(mat + "_" + t, t + "#" + mat);
            }
            move(mat + "_armor#item", "armor#" + mat + "_item");
            move(mat + "_armor#body", "armor#" + mat + "_body");
            String both = selections.remove(mat + "_armor");
            if (both != null) {
                selections.putIfAbsent("armor#" + mat + "_item", both);
                selections.putIfAbsent("armor#" + mat + "_body", both);
            }
        }
    }

    private void move(String from, String to) {
        String v = selections.remove(from);
        if (v != null) selections.putIfAbsent(to, v);
    }

    // ---------- Profile ----------

    public static final int MAX_PROFILES = 10;
    public static final int MAX_NAME = 20;

    private void normalize() {
        if (profiles == null) profiles = new LinkedHashMap<>();
        if (selections == null) selections = new LinkedHashMap<>();
        if (crosshair == null) crosshair = new CustomCrosshair();
        crosshair.normalize();
        if (activeProfile == null || activeProfile.isBlank()) activeProfile = "Profil 1";
        if (!profiles.containsKey(activeProfile)) profiles.put(activeProfile, new LinkedHashMap<>());
    }

    public List<String> profileNames() {
        normalize();
        return new ArrayList<>(profiles.keySet());
    }

    /** Anzahl geänderter Einträge eines Profils. */
    public int profileSize(String name) {
        normalize();
        if (name.equals(activeProfile)) return selections.size();
        Map<String, String> m = profiles.get(name);
        return m == null ? 0 : m.size();
    }

    private void snapshot() {
        profiles.put(activeProfile, new LinkedHashMap<>(selections));
    }

    /** Gibt einen gültigen, noch freien Namen zurück (leer = "Profil N"). */
    public String uniqueName(String wanted) {
        normalize();
        String base = wanted == null ? "" : wanted.trim();
        if (base.length() > MAX_NAME) base = base.substring(0, MAX_NAME).trim();
        if (base.isEmpty()) base = "Profil";
        String name = base;
        int n = 2;
        while (profiles.containsKey(name)) {
            String suffix = " " + n++;
            String head = base.length() + suffix.length() > MAX_NAME
                    ? base.substring(0, MAX_NAME - suffix.length()) : base;
            name = head + suffix;
        }
        return name;
    }

    public boolean canAddProfile() {
        normalize();
        return profiles.size() < MAX_PROFILES;
    }

    public void switchProfile(String name) {
        normalize();
        if (!profiles.containsKey(name) || name.equals(activeProfile)) return;
        snapshot();
        Map<String, String> next = new LinkedHashMap<>(profiles.get(name));
        selections.clear();
        selections.putAll(next);
        activeProfile = name;
        save();
    }

    /** Legt ein Profil an und wechselt dorthin. copy = aktuelle Auswahl übernehmen, sonst leer. */
    public String createProfile(String wanted, boolean copy) {
        normalize();
        if (!canAddProfile()) return null;
        String name = uniqueName(wanted);
        snapshot();
        profiles.put(name, copy ? new LinkedHashMap<>(selections) : new LinkedHashMap<>());
        switchProfile(name);
        return name;
    }

    public void renameActive(String wanted) {
        normalize();
        String trimmed = wanted == null ? "" : wanted.trim();
        if (trimmed.isEmpty() || trimmed.equals(activeProfile)) return;
        snapshot();
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        String name = uniqueName(trimmed);
        for (Map.Entry<String, Map<String, String>> e : profiles.entrySet()) {
            out.put(e.getKey().equals(activeProfile) ? name : e.getKey(), e.getValue());
        }
        profiles = out;
        activeProfile = name;
        save();
    }

    /** Löscht ein Profil (das letzte bleibt immer). Ist es das aktive, wird zum ersten anderen gewechselt. */
    public void deleteProfile(String name) {
        normalize();
        if (profiles.size() <= 1 || !profiles.containsKey(name)) return;
        if (name.equals(activeProfile)) {
            String other = null;
            for (String n : profiles.keySet()) {
                if (!n.equals(name)) {
                    other = n;
                    break;
                }
            }
            switchProfile(other);
        }
        profiles.remove(name);
        save();
    }

    /** Setzt die Auswahl des aktiven Profils zurück (alles wieder Standard). */
    public void resetActive() {
        normalize();
        selections.clear();
        save();
    }

    public void save() {
        normalize();
        snapshot();
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(this));
        } catch (IOException e) {
            TextureMixerClient.LOGGER.warn("Konnte Config nicht speichern", e);
        }
    }
}
