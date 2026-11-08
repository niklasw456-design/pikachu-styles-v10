package de.texturemixer.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import de.texturemixer.gui.MixerScreen;

/** Der Konfigurieren-Button in der Mod-Liste öffnet direkt das Auswahlmenü. */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MixerScreen::new;
    }
}
