package de.texturemixer;

import de.texturemixer.gui.MixerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TextureMixerClient implements ClientModInitializer {
    public static final String MOD_ID = "texturemixer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private boolean wasDown = false;

    @Override
    public void onInitializeClient() {
        MixerConfig.load();
        CrosshairHud.register();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        int key = MixerConfig.get().openKey;
        if (key < 0 || client.getWindow() == null) {
            wasDown = false;
            return;
        }
        boolean down = GLFW.glfwGetKey(client.getWindow().getHandle(), key) == GLFW.GLFW_PRESS;
        if (down && !wasDown && client.currentScreen == null && client.player != null) {
            client.setScreen(new MixerScreen(null));
        }
        wasDown = down;
    }
}
