package dev.mcaddons.fabric.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mcaddons.fabric.McAddons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Loads plugin icon.png files (local, or from the registry URL) into textures. Never blocks the GUI. */
public final class PluginIcons {
    private PluginIcons() {}

    public static final Identifier UNKNOWN = Identifier.withDefaultNamespace("textures/misc/unknown_pack.png");
    private static final Map<String, Identifier> LOADED = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> PENDING = new ConcurrentHashMap<>();

    /** Texture for a plugin; falls back to the vanilla "unknown pack" icon until loaded. */
    public static Identifier get(String pluginId, String remoteUrl) {
        Identifier id = LOADED.get(pluginId);
        if (id != null) return id;
        if (PENDING.putIfAbsent(pluginId, true) == null) {
            CompletableFuture.supplyAsync(() -> {
                try {
                    Path local = McAddons.ROOT.resolve("plugins").resolve(pluginId).resolve("icon.png");
                    if (Files.isRegularFile(local) && Files.size(local) < 1 << 20) return Files.readAllBytes(local);
                    if (remoteUrl != null) return McAddons.REGISTRY.get(remoteUrl);
                } catch (Exception ignored) {}
                return null;
            }).thenAccept(bytes -> {
                if (bytes == null) return;
                Minecraft.getInstance().execute(() -> {
                    try {
                        NativeImage img = NativeImage.read(bytes);
                        Identifier tex = Identifier.fromNamespaceAndPath("mcaddons", "icon/" + pluginId);
                        Minecraft.getInstance().getTextureManager().register(tex, new DynamicTexture(() -> "mcaddons icon " + pluginId, img));
                        LOADED.put(pluginId, tex);
                    } catch (Exception ignored) {}
                });
            });
        }
        return UNKNOWN;
    }

    /** Forget an icon (after install/update/remove). */
    public static void invalidate(String pluginId) {
        LOADED.remove(pluginId);
        PENDING.remove(pluginId);
    }
}
