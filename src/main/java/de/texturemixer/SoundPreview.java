package de.texturemixer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spielt Sound-Vorschauen im Menü ab. Die .ogg-Datei wird mit STB Vorbis (steckt schon in Minecraft)
 * dekodiert und über javax.sound abgespielt, ohne dass das Pack aktiv sein muss.
 * Lautstärke = Master x Spieler-Lautstärke aus den Minecraft-Optionen.
 * Alles Schwere (Dekodieren, Abspielen) läuft in einem eigenen Thread, das Menü bleibt flüssig.
 */
public final class SoundPreview {
    private record Pcm(byte[] data, AudioFormat format, long millis) {}

    private static final Map<String, Pcm> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger GEN = new AtomicInteger();
    private static final Object LOCK = new Object();
    private static final int MAX_SECONDS = 12;

    private static Clip clip;
    private static volatile String playing = null;
    private static volatile long startedAt = 0;
    private static volatile long length = 0;

    private SoundPreview() {}

    /** Schlüssel der Karte: null = Vanilla-Sound ("Standard"). */
    private static String key(PackScanner.PackInfo pack) {
        return pack == null ? "vanilla" : pack.name() + "|" + pack.stamp();
    }

    /** Spielt den Totem-Sound des Packs (pack == null: der Vanilla-Sound). Muss im Render-Thread aufgerufen werden. */
    public static void play(PackScanner.PackInfo pack) {
        MinecraftClient mc = MinecraftClient.getInstance();
        float vol = volume(mc);
        String key = key(pack);
        int gen = GEN.incrementAndGet();
        stopClip();
        if (vol <= 0.001f) return;

        Pcm cached = CACHE.get(key);
        byte[] raw = null;
        ResourceManager rm = null;
        if (cached == null) {
            // Datei lesen (klein) im Render-Thread, damit der Pack-Zugriff nicht mit dem Schließen kollidiert
            if (pack == null) {
                rm = mc.getResourceManager();
            } else {
                try {
                    raw = PackScanner.readTotemSound(pack);
                } catch (Exception e) {
                    TextureMixerClient.LOGGER.warn("Totem-Sound von {} nicht lesbar", pack.name(), e);
                }
                if (raw == null) return;
            }
        }

        final byte[] rawF = raw;
        final ResourceManager rmF = rm;
        Thread t = new Thread(() -> run(key, cached, rawF, rmF, vol, gen), "TextureMixer-Sound");
        t.setDaemon(true);
        t.start();
    }

    private static void run(String key, Pcm cached, byte[] raw, ResourceManager rm, float vol, int gen) {
        try {
            Pcm pcm = cached;
            if (pcm == null) {
                byte[] ogg = raw != null ? raw : vanillaBytes(rm);
                if (ogg == null) return;
                pcm = decode(ogg);
                if (pcm == null) return;
                CACHE.put(key, pcm);
            }
            if (gen != GEN.get()) return; // inzwischen wurde etwas anderes angeklickt

            synchronized (LOCK) {
                if (gen != GEN.get()) return;
                stopClipLocked();
                Clip c = AudioSystem.getClip();
                c.open(pcm.format(), pcm.data(), 0, pcm.data().length);
                if (c.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gain = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
                    float db = (float) (20.0 * Math.log10(Math.max(0.0001f, Math.min(1f, vol))));
                    gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
                }
                c.addLineListener(ev -> {
                    if (ev.getType() == LineEvent.Type.STOP || ev.getType() == LineEvent.Type.CLOSE) {
                        synchronized (LOCK) {
                            if (clip == c) {
                                playing = null;
                                clip = null;
                            }
                        }
                        new Thread(c::close, "TextureMixer-SoundClose").start();
                    }
                });
                clip = c;
                playing = key;
                startedAt = System.currentTimeMillis();
                length = pcm.millis();
                c.start();
            }
        } catch (Throwable e) {
            TextureMixerClient.LOGGER.warn("Sound-Vorschau konnte nicht abgespielt werden", e);
            playing = null;
        }
    }

    /** Läuft gerade der Sound dieser Karte? */
    public static boolean isPlaying(PackScanner.PackInfo pack) {
        String p = playing;
        return p != null && p.equals(key(pack));
    }

    /** Fortschritt 0..1 des laufenden Sounds dieser Karte (0, wenn er nicht läuft). */
    public static float progress(PackScanner.PackInfo pack) {
        if (!isPlaying(pack) || length <= 0) return 0f;
        return Math.max(0f, Math.min(1f, (System.currentTimeMillis() - startedAt) / (float) length));
    }

    /** Stoppt die Wiedergabe (beim Schließen des Menüs). */
    public static void stop() {
        GEN.incrementAndGet();
        stopClip();
    }

    private static void stopClip() {
        synchronized (LOCK) {
            stopClipLocked();
        }
    }

    private static void stopClipLocked() {
        Clip c = clip;
        clip = null;
        playing = null;
        if (c != null) {
            try {
                c.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    private static float volume(MinecraftClient mc) {
        try {
            return mc.options.getSoundVolume(SoundCategory.MASTER) * mc.options.getSoundVolume(SoundCategory.PLAYERS);
        } catch (Throwable e) {
            return 0.6f;
        }
    }

    /** Vanilla-Sound: die älteste (= unterste) Fassung der Datei, also die des Spiels selbst, nicht die eines Packs. */
    private static byte[] vanillaBytes(ResourceManager rm) {
        try {
            List<Resource> all = rm.getAllResources(Identifier.of("minecraft", "sounds/item/totem/use.ogg"));
            if (all.isEmpty()) return null;
            try (InputStream in = all.get(0).getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Throwable e) {
            TextureMixerClient.LOGGER.warn("Vanilla-Totem-Sound nicht lesbar", e);
            return null;
        }
    }

    private static Pcm decode(byte[] ogg) {
        ByteBuffer buf = MemoryUtil.memAlloc(ogg.length);
        try {
            buf.put(ogg);
            buf.flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer ch = stack.mallocInt(1);
                IntBuffer sr = stack.mallocInt(1);
                ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(buf, ch, sr);
                if (pcm == null) return null;
                try {
                    int channels = ch.get(0);
                    int rate = sr.get(0);
                    if (channels < 1 || channels > 2 || rate < 8000) return null;
                    int samples = Math.min(pcm.remaining(), rate * channels * MAX_SECONDS);
                    byte[] out = new byte[samples * 2];
                    for (int i = 0; i < samples; i++) {
                        short s = pcm.get(i);
                        out[i * 2] = (byte) s;
                        out[i * 2 + 1] = (byte) (s >> 8);
                    }
                    AudioFormat fmt = new AudioFormat(rate, 16, channels, true, false);
                    long millis = samples * 1000L / ((long) rate * channels);
                    return new Pcm(out, fmt, millis);
                } finally {
                    LibCStdlib.free(pcm);
                }
            }
        } finally {
            MemoryUtil.memFree(buf);
        }
    }
}
