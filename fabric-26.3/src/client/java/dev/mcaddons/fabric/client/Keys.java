package dev.mcaddons.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Key mappings must exist before the options screen loads, so we register a fixed pool of slots
 * and hand them to plugins at runtime. Hot reload just changes who owns a slot.
 */
public final class Keys {
    private Keys() {}

    static final List<KeyMapping> SLOTS = new ArrayList<>();
    static final String[] LABELS = new String[dev.mcaddons.core.PluginManager.KEY_SLOTS + 1];
    static KeyMapping browser;
    static KeyMapping hiding;

    static void register() {
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mcaddons", "main"));
        browser = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcaddons.browser", InputConstants.Type.KEYBOARD, InputConstants.KEY_K, cat));
        hiding = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcaddons.hiding", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), cat));
        for (int i = 1; i <= dev.mcaddons.core.PluginManager.KEY_SLOTS; i++)
            SLOTS.add(KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcaddons.slot." + i, InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), cat)));
    }

    static void bind(int slot, String label, String defaultKey) {
        LABELS[slot] = label;
        if (label == null || defaultKey == null || defaultKey.isBlank()) return;
        KeyMapping k = SLOTS.get(slot - 1);
        if (k.isUnbound()) {
            try {
                k.setKey(InputConstants.getKey("key.keyboard." + defaultKey.toLowerCase()));
                KeyMapping.resetMapping();
            } catch (Exception ignored) {}
        }
    }

    static void tick(Minecraft mc) {
        while (browser.consumeClick()) if (mc.gui.screen() == null) mc.gui.setScreen(new PluginBrowserScreen(null));
        while (hiding.consumeClick()) BlockHiding.toggle();
        BlockHiding.tick(mc);
        var m = McAddonsClient.manager();
        for (int i = 0; i < SLOTS.size(); i++) while (SLOTS.get(i).consumeClick()) if (m != null) m.pressKey(i + 1);
    }
}
