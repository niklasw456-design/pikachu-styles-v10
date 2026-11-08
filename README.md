# Pikachu Styles (Fabric, Minecraft 1.21.11)

Client-Mod: erkennt Texturepacks im `resourcepacks`-Ordner und lässt dich pro Kategorie
(Schwerter, Äxte, Spitzhacken, Schaufeln, Rüstungen, Obsidian, End Crystal (3D), Schild, Elytra, Bogen, Armbrust, Totem, Totem-Sound, Tränke, XP-Flasche, Raketen, ...) wählen, aus welchem Pack die Textur kommt.

## Bauen
1. Ordner in IntelliJ IDEA öffnen (JDK 21 einstellen), Gradle importieren lassen.
2. Zum Testen: Gradle-Task `Tasks > fabric > runClient`.
3. Fertige Mod: Task `build`, die Datei liegt in `build/libs/texturemixer-1.1.0.jar`.
   Zusammen mit Fabric API und Mod Menu in den `mods`-Ordner legen.

## Benutzen
- Menü öffnen: Mod Menu > "Pikachu Styles" > Konfigurieren-Symbol.
- Oben Reiter pro Kategorie, darunter ein Raster mit Vorschaubild pro Pack (Animationen laufen live). Klick = auswählen, "Standard" = nichts überschreiben.
- Unten: Taste zum Öffnen festlegen (standardmäßig nicht belegt). ESC = Abbruch, Entf = Taste löschen.
- Beim Schließen ("Fertig" oder ESC) wird `resourcepacks/texturemixer_output` erzeugt, ganz oben aktiviert und die Ressourcen neu geladen.
- Auswahl und Taste werden in `config/texturemixer.json` gespeichert.

## Neue Kategorie hinzufügen
In `Category.java` in `build()` eine Zeile ergänzen, z. B.
`l.add(simple("gapple", "Goldener Apfel", "item/golden_apple.png", "item/enchanted_golden_apple.png"));`

## Hinweise
- Es werden nur Texturen kopiert (assets/minecraft/textures). Packs mit eigenen 3D-Modellen
  für Items brauchen zusätzlich deren Modelle, das ist hier nicht enthalten.
- Alte Rüstungstexturen (`models/armor/*_layer_1/2.png`) werden automatisch ins neue Format umgeleitet.
- Falls Gradle eine Version nicht findet: aktuelle Werte auf https://fabricmc.net/develop eintragen
  (`gradle.properties`).

## Tränke
Feuerresistenz, Stärke und Schnelligkeit, jeweils Normal und Splash (Umschalter oben rechts).
Die Mod liest dafür `items/potion.json` und `items/splash_potion.json` der Packs (ab 1.21.4) und baut daraus
eine neue Weiche. Alle anderen Tränke bleiben unverändert. Lange und starke Stufen nutzen denselben Look.

## Rüstung: Item / Body
Diamant- und Netherite-Rüstung haben oben rechts einen Umschalter **Item / Body** (Auswahl wird getrennt gespeichert):
- **Item**: die Icons (Helm, Brustplatte, Hose, Stiefel), Vorschau als 2x2-Bild.
- **Body**: die getragene Rüstung. Vorschau ist dein eigener Skin (mit Hut/Jacke-Ebene) mit der Rüstung des Packs, **drehend** (eine Umdrehung in 16 Sekunden, mit Bodenschatten, auch Rücken und Seiten).
  Schmale Skins (Alex) werden erkannt. Ohne Welt (z. B. im Titelbildschirm) wird Steve gezeigt. Die Karte "Standard" zeigt die Vanilla-Rüstung.
  Technik: jedes Körperteil ist ein Quader, pro Bild werden nur die zugewandten Seiten gezeichnet und waagerecht gestaucht (Matrix-Skalierung).
- Alte Config-Einträge (`diamond_armor`) gelten nach dem Update automatisch für Item und Body.
- Skin-Zugriff: `BodyView.skin()` (ein einziger Aufruf, `getSkin().body().texturePath()`).


## Schild (3D)
Die Vorschau des Schilds ist ein 3D-Modell (Platte + Griff) aus der Entity-Textur `shield_base_nopattern.png` des Packs.
Es dreht sich langsam (eine Umdrehung in etwa 10 Sekunden), Vorderseite, Seiten und Rückseite mit Griff sind zu sehen.
Technik: `render/ShieldRenderer.java` (Software-Renderer wie beim End Crystal).

## End Crystal: In der Hand (3D-Item-Modell)
Hat ein Pack für das Crystal-Item ein eigenes 3D-Modell (`items/end_crystal.json` und/oder `models/item/end_crystal.json`
mit `elements`), zeigt die Vorschau es so, wie es im Inventar aussieht (`display.gui`, Eltern-Modelle, Drehungen, Texturen).
Beim Anwenden werden Item-Definition, Modelle und die benutzten Texturen des Modells ins Ausgabe-Pack kopiert.
Packs mit nur einem flachen Icon (`item/end_crystal.png`) werden wie bisher als Bild gezeigt.
Technik: `ItemModelSupport.java` (Modelle lesen/kopieren) und `render/ModelRenderer.java`.

## Oberfläche (GUI / HUD)
Neue Reiter: **Inventar**, **Herzen**, **Hungerkeulen**, **Hotbar**, **XP-Leiste**, **Fadenkreuz**.
- Inventar: `gui/container/inventory.png`
- Herzen: alle Sprites in `gui/sprites/hud/heart/` (voll, halb, Hardcore, vergiftet, gefroren, ...)
- Hungerkeulen: `gui/sprites/hud/food_*`
- Hotbar: `hotbar.png`, `hotbar_selection.png`, `hotbar_offhand_*`
- XP-Leiste: `experience_bar_*`
- Fadenkreuz: `crosshair*` und die Angriffsanzeige (`hotbar_attack_indicator_*`), dazu **Eigenes** (selbst gezeichnet, siehe unten)
- Nur Packs im neuen Format (ab 1.20.2, Sprites unter `gui/sprites/hud/`). Alte Packs mit `gui/icons.png` werden nicht erkannt.

## Custom Sky (OptiFine-Format)
Reiter **Custom Sky**: erkennt Packs mit `assets/minecraft/optifine/sky/` (auch `mcpatcher/sky/`) und kopiert den kompletten
Sky-Ordner (`.properties` + Würfel-PNGs wie `cloud1.png`, 3 Spalten x 2 Reihen) ins Ausgabe-Pack.
Die Vorschau zeigt die Würfel-Textur verkleinert. Zum Anzeigen im Spiel braucht man OptiFine oder eine Mod, die OptiFine-Skies liest.

## Feuer
Reiter **Feuer**: Vorschau ist ein Netherrack-Block mit Flamme darüber (gekreuzte Ebenen aus `fire_0` und `fire_1`).
Die Flamme läuft mit der Animation aus der `.mcmeta` des Packs. Netherrack stammt aus dem Pack, sonst aus dem Spiel.
Kopiert werden `block/fire_0.png` und `block/fire_1.png` samt Animationsdateien.

## Blöcke
Reiter **Blöcke**: links eine alphabetische Liste, **ein Eintrag pro Block**. Texturen wie `acacia_door_top` und `acacia_door_bottom`
sind zu "Acacia Door" zusammengefasst (`gui/BlockGroups.java`: erkennt Endungen wie top, bottom, side, front, on, off ...).
- Block anklicken: rechts die Packs wählen, die Wahl gilt für **alle Seiten** des Blocks.
- Die **drei Punkte** (⋯) in der Zeile öffnen die Seitenwahl (Alle Seiten / Oben / Seite / Unten ...) für einzelne Texturen.
- Gespeichert wird weiter pro Textur (`block:<name>`), alte Configs bleiben gültig.
- Mausrad scrollt die Liste weich, über dem Raster blättert es die Seiten.

## Suche
Die Suchleiste im Kopf filtert die Reiter (nicht passende werden abgedunkelt, ein passender wird automatisch geöffnet)
und die Blockliste. ESC leert die Suche, ein zweites ESC schließt das Menü.

## Animationen
Menü blendet ein/aus, Karten erscheinen gestaffelt, weiches Hover, gleitender Reiter-Unterstrich und Listen-Markierung,
Aufblitzen bei Auswahl, weiches Scrollen.

## Material-Leiste (Schwert, Axt, Spitzhacke, Schaufel, Rüstung)
Je ein Reiter pro Werkzeug, oben rechts die Materialien: Netherite, Diamant, Eisen, Kupfer, Gold, Stein, Holz.
Bei der **Rüstung** (Netherite, Diamant, Eisen, Kupfer, Gold, Kette) wählst du oben das Material und darunter **Item / Body**.
Config-Schlüssel jetzt z. B. `sword#diamond`, `armor#netherite_body`; alte Einträge (`diamond_sword`, `netherite_armor#item`) werden automatisch umgezogen.
Neue Materialien: Listen `TOOL_MATERIALS` / `ARMOR_MATERIALS` in `Category.java`.

## Hotbar-Auswahl
Eigener Reiter **Hotbar-Auswahl** (`hotbar_selection.png`), der Reiter Hotbar enthält nur noch die Leiste und das Offhand-Feld.

## Profile und Zurücksetzen
- Unten links **Profil: <Name>**: öffnet das Profil-Fenster. Profil anklicken = wechseln (Auswahl wird beim Wechseln gesichert),
  **Neu** = leeres Profil, **Kopie** = Kopie der aktuellen Auswahl, **Umbenennen**, **Löschen** (zweimal klicken, das letzte Profil bleibt).
  Name vorher ins Feld tippen, leer = "Profil N". Maximal 10 Profile. ESC oder Klick daneben schließt das Fenster.
- Unten rechts **Zurücksetzen**: erst erscheinen **Bestätigen** (rot) und **Abbrechen**, erst der zweite Klick setzt die Auswahl
  des aktiven Profils zurück (verfällt nach 6 s). Danach "Anwenden" bzw. "Fertig", damit das Pack neu gebaut wird.
- Gespeichert in `config/texturemixer.json` (`profiles`, `activeProfile`), die bisherige Auswahl wird zum ersten Profil "Profil 1".

## Eigenes Fadenkreuz zeichnen
Im Reiter **Fadenkreuz** gibt es neben den Packs den Eintrag **Eigenes** und oben rechts den Button **Eigenes zeichnen**
(ein zweiter Klick auf die schon gewählte Karte "Eigenes" öffnet den Editor ebenfalls).
- **Zeichenfläche**: 15 x 15 Pixel, der markierte Mittelpunkt ist die Stelle, auf die du zielst. Links = malen (Ziehen geht), Rechts = löschen.
- **Werkzeuge**: Stift, Radierer, Füllen (Rechtsklick füllt mit "leer"), Pipette (Farbe aus der Zeichnung aufnehmen).
- **Farbe**: Farbfeld (Sättigung/Helligkeit), Farbtonleiste, Hex-Feld (`#RRGGBB`) und eine Palette mit 11 Farben.
- **Sym X / Sym Y**: spiegelt alles, was du malst, an der Mittelachse. **Raster** blendet die Linien ein/aus.
- **Zurück** oder Strg+Z (bis zu 40 Schritte), **Leeren**, Vorlagen **Kreuz / Punkt / Plus / Kreis** (in der aktuellen Farbe).
- **Größe** x1 bis x3: so viele Bildschirmpixel pro Zeichenpixel. Die Vorschau zeigt das Fadenkreuz in echter Größe auf dunklem, grünem und hellem Hintergrund.
- **Fertig** übernimmt und wählt "Eigenes" aus (danach "Anwenden"/"Fertig" im Hauptmenü), **Abbrechen** / ESC verwirft.
- Gespeichert in `config/texturemixer.json` (`crosshair`: `pixels`, `scale`, `color`). Die Zeichnung gilt für alle Profile, ob "Eigenes" aktiv ist, wird pro Profil gewählt.

Technik: Minecraft zeichnet sein eigenes Fadenkreuz mit invertierendem Blend, eine gewählte Farbe würde je nach Untergrund anders aussehen.
Deshalb zeichnet `CrosshairHud.java` das eigene Fadenkreuz selbst (Fabric `HudElementRegistry`, direkt nach dem Vanilla-Fadenkreuz) mit echten Farben.
Das Vanilla-Fadenkreuz wird im Ausgabe-Pack durch ein durchsichtiges Bild ersetzt, die Angriffsanzeige bleibt dadurch erhalten.
Das Overlay erscheint nur in der Ego-Perspektive.

## Totem-Sound (neu in 1.1.0)
Reiter **Totem-Sound** (Totem-Symbol): wählt, aus welchem Pack der Sound kommt, wenn das Totem der Unsterblichkeit auslöst (`item.totem.use`).
- Erkannt werden beide Arten, wie Packs den Sound ersetzen: ein Eintrag `item.totem.use` in `assets/minecraft/sounds.json`
  (auch mit eigenen Dateinamen und `volume`/`pitch`/`weight`) **oder** einfach `.ogg`-Dateien unter `assets/minecraft/sounds/item/totem/` (z. B. `use.ogg`).
- **Klick auf eine Karte** wählt sie aus und **spielt den Sound ab** (Karte "Standard" = Vanilla-Sound). Nochmal klicken = nochmal anhören.
  Die Karte zeigt dabei tanzende Klangbalken, ein Pause-Symbol und einen Fortschrittsstreifen. Gespielt wird mit Master- x Spieler-Lautstärke aus den Minecraft-Optionen.
  Hat ein Pack mehrere Sounds für das Totem, wird bei jedem Klick zufällig einer vorgespielt.
- Beim Anwenden werden die benutzten `.ogg`-Dateien ins Ausgabe-Pack kopiert und eine eigene `sounds.json` mit `"replace": true` geschrieben,
  damit nur noch der gewählte Sound läuft. Nur Ogg Vorbis wird unterstützt (wie in Minecraft).
- Technik: `SoundSupport.java` (Erkennen/Kopieren), `SoundPreview.java` (Hörprobe: Dekodieren mit STB Vorbis aus Minecraft, Abspielen über javax.sound in eigenem Thread, das Menü bleibt flüssig).
- Config-Schlüssel: `totem_sound`.

## Animationen und Geschmeidigkeit (neu in 1.1.0)
- Alle Übergänge sind jetzt **bildratenunabhängig** (exponentielle Glättung), dadurch auch bei niedrigen FPS weich.
- Karten **heben sich beim Überfahren an** und werfen einen Schatten, die gewählte Karte "atmet" (pulsierender Rand).
- **Blättern**: neue Karten gleiten von der Seite herein (vorwärts von rechts, zurück von links), auch mit dem Mausrad.
- **Ladeschimmer** statt "..." solange ein Vorschaubild geladen wird, fertige Vorschauen blenden weich ein.
- Die Hover-Zeile (Name unter dem Titel) blendet beim Wechsel über, "Anwenden" und "nicht angewendet" **pulsieren**, solange es ungespeicherte Änderungen gibt.
- Ein Lichtstreifen wandert über die Akzentlinie im Kopf.

## Leistung (neu in 1.1.0)
- Zip-Packs bleiben, solange das Menü offen ist, geöffnet (vorher wurde jedes Vorschaubild mit einem neuen Öffnen der Zip geladen).
- Packs werden **parallel** gescannt, bereits gelesene Zip-Packs (gleiche Größe und Änderungszeit) werden beim erneuten Öffnen des Menüs übernommen. "Neu scannen" liest alles neu.
- Kein Dateizugriff mehr pro Bild (vorher wurde für jede Karte in jedem Bild die Änderungszeit der Datei abgefragt).
- Weniger Speicher-Allokationen pro Bild: Animations-Schlüssel und Listen werden beim Aufbau berechnet statt in jedem Bild.
