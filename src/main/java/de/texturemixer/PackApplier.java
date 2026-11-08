package de.texturemixer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Erzeugt das Ausgabe-Pack aus der aktuellen Auswahl, aktiviert es ganz oben und lädt die Ressourcen neu. */
public final class PackApplier {
    private PackApplier() {}

    public static void apply() {
        MinecraftClient client = MinecraftClient.getInstance();
        int copied;
        try {
            copied = PackScanner.buildOutput(MixerConfig.get().selections, PackScanner.scan());
        } catch (IOException e) {
            TextureMixerClient.LOGGER.error("Ausgabe-Pack konnte nicht erstellt werden", e);
            return;
        } finally {
            PackScanner.closeCaches(); // geöffnete Zip-Dateien freigeben, das Spiel lädt sie gleich selbst
        }

        ResourcePackManager manager = client.getResourcePackManager();
        manager.scanPacks();
        String id = "file/" + PackScanner.OUTPUT_NAME;

        List<String> before = new ArrayList<>(client.options.resourcePacks);
        manager.disable(id);
        if (copied > 0) manager.enable(id);

        // schreibt options.txt und lädt neu, falls sich die Liste geändert hat
        client.options.refreshResourcePacks(manager);
        if (before.equals(client.options.resourcePacks)) {
            client.reloadResources();
        }
    }
}
