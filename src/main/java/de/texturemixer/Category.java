package de.texturemixer;

import java.util.ArrayList;
import java.util.List;

/**
 * Ein Reiter im Menü. Hat er zwei Varianten (z. B. Platziert / In der Hand oder Normal / Splash),
 * erscheint oben rechts der Umschalter.
 *
 * Rule:       kopiert alle Dateien, deren Pfad (relativ zu assets/minecraft/textures/) mit "src" beginnt,
 *             nach "dst" + Rest des Pfads. Ein Prefix wie "item/mace.png" greift auch für die .mcmeta.
 *             Regeln weiter hinten überschreiben frühere (neues Format gewinnt vor altem).
 * HandSpec:   für Blöcke/Netze: In der Hand wird ein eigenes Item-Modell erzeugt, das die gewählte Textur nutzt.
 * PotionSpec: Tränke: der Look steht in items/potion.json bzw. items/splash_potion.json des Packs.
 * render:     "crystal" = die Vorschau wird als drehendes 3D-Modell berechnet,
 *             "shield" = Schild als langsam drehendes 3D-Modell,
 *             "item3d:<id>" = 3D-Item-Modell des Packs (wie im Inventar), sonst flaches Icon,
 *             "armor_items:<material>" = 2x2-Bild aus den vier Rüstungs-Items,
 *             "body:<material>" = Spieler-Skin mit der getragenen Rüstung,
 *             "fire" = Netherrack mit animierter Flamme,
 *             "sound" = Totem-Pop-Sound (Vorschau = Abspielen per Klick, siehe SoundSupport / SoundPreview).
 */
public record Category(String id, String name, String icon, String potionIcon, List<Variant> variants) {

    /** asset = true: Pfad relativ zu assets/minecraft/ (z. B. optifine/sky/), sonst relativ zu assets/minecraft/textures/. */
    public record Rule(String src, String dst, boolean asset) {
        public Rule(String src, String dst) {
            this(src, dst, false);
        }
    }

    public record HandSpec(String itemId, boolean cube, List<String> sources) {}

    public record PotionSpec(String itemDef, List<String> ids, int color) {}

    /**
     * group: optionale obere Ebene (z. B. das Material bei der Rüstung); label ist dann die untere Ebene (Item / Body).
     * Ohne group sind die Varianten eine einzige Reihe (z. B. Material bei Schwertern oder Platziert / In der Hand).
     */
    public record Variant(String key, String label, List<Rule> rules, List<String> previews,
                          HandSpec hand, String render, PotionSpec potion, String group) {
        public Variant(String key, String label, List<Rule> rules, List<String> previews,
                       HandSpec hand, String render, PotionSpec potion) {
            this(key, label, rules, previews, hand, render, potion, null);
        }

        public boolean matches(String rel) {
            return matches(rel, false);
        }

        /** asset = true: rel ist relativ zu assets/minecraft/ (Sky), sonst relativ zu textures/. */
        public boolean matches(String rel, boolean asset) {
            for (Rule r : rules) {
                if (r.asset() == asset && rel.startsWith(r.src())) return true;
            }
            return false;
        }

        public boolean hasAssetRules() {
            for (Rule r : rules) {
                if (r.asset()) return true;
            }
            return false;
        }

        /** HUD/GUI-Reiter: Vorschau = übereinandergelegte Bilder, render = "layers:<Vanilla-Breite>:<Zuschnitt-Breite>:<pfad;pfad>". */
        public boolean isLayers() {
            return render != null && render.startsWith("layers:");
        }

        /** Custom Sky (OptiFine-Format): Vorschau = die Würfel-Textur (3x2) verkleinert. */
        public boolean isSky() {
            return "sky".equals(render);
        }

        /** Rüstung, Reiter "Item": Vorschau = Helm, Brustplatte, Hose, Stiefel als 2x2-Bild. */
        public boolean isArmorItems() {
            return render != null && render.startsWith("armor_items:");
        }

        /** 3D-Item-Modell des Packs (items/ + models/), render = "item3d:<item-id>"; ohne eigenes Modell das flache Icon. */
        public boolean isItem3d() {
            return render != null && render.startsWith("item3d:");
        }

        /** Schild: Vorschau = langsam drehendes 3D-Modell aus der Entity-Textur. */
        public boolean isShield() {
            return "shield".equals(render);
        }

        /** Feuer: Vorschau = Netherrack mit animierter Flamme (berechnet). */
        public boolean isFire() {
            return "fire".equals(render);
        }

        /** Totem-Sound: Karte zeigt ein Totem mit Klangbalken, ein Klick wählt aus und spielt den Sound ab. */
        public boolean isSound() {
            return "sound".equals(render);
        }

        /** Rüstung, Reiter "Body": Vorschau = Spieler-Skin mit angezogener Rüstung. */
        public boolean isBody() {
            return render != null && render.startsWith("body:");
        }

        /** Material hinter dem Doppelpunkt im render-Feld (z. B. "diamond"), sonst null. */
        public String material() {
            int i = render == null ? -1 : render.indexOf(':');
            return i < 0 ? null : render.substring(i + 1);
        }
    }

    /** Material-Leiste: {Schlüssel, Anzeigename, Item-Präfix}. */
    private static final String[][] TOOL_MATERIALS = {
            {"netherite", "Netherite", "netherite"}, {"diamond", "Diamant", "diamond"},
            {"iron", "Eisen", "iron"}, {"copper", "Kupfer", "copper"},
            {"golden", "Gold", "golden"}, {"stone", "Stein", "stone"}, {"wooden", "Holz", "wooden"}};

    /** Material-Leiste: {Schlüssel, Anzeigename, Item-Präfix, Name der getragenen Rüstung}. */
    private static final String[][] ARMOR_MATERIALS = {
            {"netherite", "Netherite", "netherite", "netherite"}, {"diamond", "Diamant", "diamond", "diamond"},
            {"iron", "Eisen", "iron", "iron"}, {"copper", "Kupfer", "copper", "copper"},
            {"golden", "Gold", "golden", "gold"}, {"chainmail", "Kette", "chainmail", "chainmail"}};

    public static final List<Category> ALL = build();

    public boolean dual() {
        return variants.size() > 1;
    }

    /** Obere Ebene (z. B. Materialien der Rüstung) in Reihenfolge, leer wenn es keine gibt. */
    public List<String> groups() {
        List<String> g = new ArrayList<>();
        for (Variant v : variants) {
            if (v.group() != null && !g.contains(v.group())) g.add(v.group());
        }
        return g;
    }

    /** Ohne Listen zu bauen (wird pro Bild vom Menü gefragt). */
    public boolean hasGroups() {
        for (Variant v : variants) {
            if (v.group() != null) return true;
        }
        return false;
    }

    /** Eindeutiger Schlüssel für Auswahl und Config. */
    public String fullId(Variant v) {
        if (id.equals("blocks")) return "block:" + v.key();
        return dual() ? id + "#" + v.key() : id;
    }

    /** Dynamische Variante für einen einzelnen Block (Textur block/<name>.png). */
    public static Variant blockVariant(String name) {
        String path = "block/" + name + ".png";
        return new Variant(name, "", List.of(same(path)), List.of(path), null, null, null);
    }

    // ---------- Aufbau ----------

    private static Rule same(String path) {
        return new Rule(path, path);
    }

    private static Variant plain(List<Rule> rules, String... previews) {
        return new Variant("main", "", List.copyOf(rules), List.of(previews), null, null, null);
    }

    private static Category single(String id, String name, String icon, Variant v) {
        return new Category(id, name, icon, null, List.of(v));
    }

    private static Category tool(String id, String name, String item) {
        return single(id, name, item, plain(List.of(same("item/" + item + ".png")), "item/" + item + ".png"));
    }

    /** Werkzeug (Schwert, Axt, ...) mit Materialleiste oben: ein Reiter, die Materialien sind die Varianten. */
    private static Category tools(String id, String name, String type) {
        List<Variant> vs = new ArrayList<>();
        for (String[] m : TOOL_MATERIALS) {
            String path = "item/" + m[2] + "_" + type + ".png";
            vs.add(new Variant(m[0], m[1], List.of(same(path)), List.of(path), null, null, null));
        }
        return new Category(id, name, "diamond_" + type, null, List.copyOf(vs));
    }

    /**
     * Rüstung: oben das Material, darunter der Umschalter "Item" = die Item-Icons (Helm, Brustplatte, Hose, Stiefel),
     * "Body" = die getragene Rüstung (Entity-Texturen), Vorschau am Spieler-Skin.
     */
    private static Category armor(String id, String name) {
        List<Variant> vs = new ArrayList<>();
        for (String[] m : ARMOR_MATERIALS) {
            String material = m[2];
            String equip = m[3];
            List<Rule> items = new ArrayList<>();
            for (String part : new String[]{"helmet", "chestplate", "leggings", "boots"}) {
                items.add(same("item/" + material + "_" + part));
            }
            vs.add(new Variant(m[0] + "_item", "Item", List.copyOf(items), List.of(), null,
                    "armor_items:" + material, null, m[1]));

            List<Rule> body = new ArrayList<>();
            // Altes Format (vor 1.21.2) wird automatisch ins neue Format umgeleitet
            body.add(new Rule("models/armor/" + equip + "_layer_1", "entity/equipment/humanoid/" + equip));
            body.add(new Rule("models/armor/" + equip + "_layer_2", "entity/equipment/humanoid_leggings/" + equip));
            body.add(same("entity/equipment/humanoid/" + equip));
            body.add(same("entity/equipment/humanoid_leggings/" + equip));
            vs.add(new Variant(m[0] + "_body", "Body", List.copyOf(body), List.of(), null,
                    "body:" + equip, null, m[1]));
        }
        return new Category(id, name, "diamond_chestplate", null, List.copyOf(vs));
    }

    /** HUD/GUI-Reiter mit zusammengesetzter Vorschau (siehe Variant.isLayers). */
    private static Category hud(String id, String name, String icon, String render, List<Rule> rules) {
        Variant v = new Variant("main", "", List.copyOf(rules), List.of(), null, render, null);
        return single(id, name, icon, v);
    }

    /** Block bzw. Netz: platziert = Blocktextur, in der Hand = eigenes Item-Modell mit frei wählbarer Textur. */
    private static Category blockLike(String id, String name, String item, boolean cube) {
        String block = "block/" + item + ".png";
        Variant placed = new Variant("placed", "Platziert", List.of(same(block)), List.of(block), null, null, null);
        List<String> sources = List.of("item/" + item + ".png", block);
        Variant hand = new Variant("hand", "In der Hand", List.of(), sources,
                new HandSpec(item, cube, sources), null, null);
        return new Category(id, name, item, null, List.of(placed, hand));
    }

    /** Trank: Normal / Splash, jeweils mit den Stufen "lang" und "stark" (gleicher Look). */
    private static Category potion(String id, String name, String potion, int color) {
        List<String> ids = List.of("minecraft:" + potion, "minecraft:long_" + potion, "minecraft:strong_" + potion);
        Variant normal = new Variant("normal", "Normal", List.of(), List.of(), null, null,
                new PotionSpec("potion", ids, color));
        Variant splash = new Variant("splash", "Splash", List.of(), List.of(), null, null,
                new PotionSpec("splash_potion", ids, color));
        return new Category(id, name, "potion", potion, List.of(normal, splash));
    }

    private static List<Category> build() {
        List<Category> l = new ArrayList<>();

        l.add(tools("sword", "Schwert", "sword"));
        l.add(tools("axe", "Axt", "axe"));
        l.add(tools("pickaxe", "Spitzhacke", "pickaxe"));
        l.add(tools("shovel", "Schaufel", "shovel"));
        l.add(armor("armor", "Rüstung"));

        l.add(blockLike("obsidian", "Obsidian", "obsidian", true));
        l.add(blockLike("crying_obsidian", "Crying Obsidian", "crying_obsidian", true));

        // End Crystal: platziert = Entity-Textur (als drehendes 3D-Modell), in der Hand = Item-Icon
        Variant crystalPlaced = new Variant("placed", "Platziert",
                List.of(same("entity/end_crystal/")), List.of("entity/end_crystal/end_crystal.png"),
                null, "crystal", null);
        Variant crystalHand = new Variant("hand", "In der Hand",
                List.of(same("item/end_crystal.png")), List.of("item/end_crystal.png"), null, "item3d:end_crystal", null);
        l.add(new Category("end_crystal", "End Crystal", "end_crystal", null, List.of(crystalPlaced, crystalHand)));

        // Feuer: Netherrack mit Flamme, animiert (fire_0 / fire_1)
        l.add(single("fire", "Feuer", "flint_and_steel", new Variant("main", "",
                List.of(same("block/fire_0.png"), same("block/fire_1.png")),
                List.of("block/fire_0.png", "block/fire_1.png"), null, "fire", null)));

        l.add(single("shield", "Schild", "shield", new Variant("main", "",
                List.of(same("entity/shield"), same("item/shield")),
                List.of("entity/shield_base_nopattern.png", "entity/shield/shield_base_nopattern.png",
                        "entity/shield_base.png", "entity/shield/shield_base.png"),
                null, "shield", null)));

        l.add(blockLike("cobweb", "Spinnennetz", "cobweb", false));

        l.add(tool("mace", "Streitkolben", "mace"));

        l.add(single("trident", "Dreizack", "trident", plain(
                List.of(same("item/trident"), same("entity/trident")), "item/trident.png")));

        l.add(single("elytra", "Elytra", "elytra", plain(List.of(
                new Rule("entity/elytra", "entity/equipment/wings/elytra"),
                same("entity/equipment/wings/elytra"),
                same("item/elytra.png"), same("item/broken_elytra.png")), "item/elytra.png")));

        l.add(single("bow", "Bogen", "bow", plain(
                List.of(same("item/bow.png"), same("item/bow_pulling_")), "item/bow.png")));
        l.add(single("crossbow", "Armbrust", "crossbow", plain(
                List.of(same("item/crossbow")), "item/crossbow_standby.png")));

        l.add(tool("totem", "Totem", "totem_of_undying"));
        // Totem-Pop-Sound: Regeln stehen in SoundSupport (sounds.json oder sounds/item/totem/*.ogg)
        l.add(single("totem_sound", "Totem-Sound", "totem_of_undying", new Variant("main", "",
                List.of(), List.of(), null, "sound", null)));
        l.add(tool("ender_pearl", "Enderperle", "ender_pearl"));

        l.add(single("golden_apple", "Goldener Apfel", "golden_apple", plain(
                List.of(same("item/golden_apple.png"), same("item/enchanted_golden_apple.png")),
                "item/golden_apple.png")));

        l.add(single("wind_charge", "Windkugel", "wind_charge", plain(
                List.of(same("item/wind_charge.png"), same("entity/projectiles/wind_charge")),
                "item/wind_charge.png")));

        l.add(tool("xp_bottle", "XP-Flasche", "experience_bottle"));
        l.add(tool("firework_rocket", "Rakete", "firework_rocket"));

        l.add(potion("potion_fire_resistance", "Feuerresistenz-Trank", "fire_resistance", 0xE49A3A));
        l.add(potion("potion_strength", "Stärke-Trank", "strength", 0x932423));
        l.add(potion("potion_swiftness", "Schnelligkeits-Trank", "swiftness", 0x33EBFF));

        // Alle übrigen Blöcke (dynamische, alphabetische Liste je nach Packs)
        l.add(single("blocks", "Blöcke", "grass_block", blockVariant("")));

        // ---------- Oberfläche (GUI / HUD) ----------
        // Alle Sprites liegen ab 1.20.2 unter textures/gui/sprites/hud/. Alte Packs mit gui/icons.png werden nicht erkannt.
        l.add(single("inventory", "Inventar", "chest", plain(
                List.of(same("gui/container/inventory.png")), "gui/container/inventory.png")));

        l.add(hud("hearts", "Herzen", "heart_pottery_sherd",
                "layers:9:9:gui/sprites/hud/heart/container.png;gui/sprites/hud/heart/full.png",
                List.of(same("gui/sprites/hud/heart/"))));

        l.add(hud("hunger", "Hungerkeulen", "cooked_beef",
                "layers:9:9:gui/sprites/hud/food_empty.png;gui/sprites/hud/food_full.png",
                List.of(same("gui/sprites/hud/food_"))));

        l.add(hud("hotbar", "Hotbar", "item_frame",
                "layers:182:44:gui/sprites/hud/hotbar.png",
                List.of(same("gui/sprites/hud/hotbar.png"), same("gui/sprites/hud/hotbar_offhand_"))));

        l.add(hud("hotbar_selection", "Hotbar-Auswahl", "spectral_arrow",
                "layers:24:24:gui/sprites/hud/hotbar_selection.png",
                List.of(same("gui/sprites/hud/hotbar_selection.png"))));

        l.add(hud("xp_bar", "XP-Leiste", "enchanted_book",
                "layers:182:18:gui/sprites/hud/experience_bar_background.png;gui/sprites/hud/experience_bar_progress.png",
                List.of(same("gui/sprites/hud/experience_bar_"))));

        l.add(hud("crosshair", "Fadenkreuz", "spyglass",
                "layers:15:15:gui/sprites/hud/crosshair.png",
                List.of(same("gui/sprites/hud/crosshair"), same("gui/sprites/hud/hotbar_attack_indicator_"))));

        // Custom Sky im OptiFine-Format (assets/minecraft/optifine/sky/... mit .properties und Würfel-PNG wie cloud1.png).
        // Wird nur kopiert, funktioniert also nur mit OptiFine bzw. einer Mod, die OptiFine-Skies liest.
        Variant sky = new Variant("main", "", List.of(
                new Rule("optifine/sky/", "optifine/sky/", true),
                new Rule("mcpatcher/sky/", "mcpatcher/sky/", true)),
                List.of(), null, "sky", null);
        l.add(single("custom_sky", "Custom Sky", "clock", sky));

        return List.copyOf(l);
    }
}
