package dev.mcaddons.fabric.client;

import dev.mcaddons.core.PluginManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Screen built from a plain Lua table:
 * <pre>
 * mc.gui.open{ title = "Shop", width = 220, height = 140, on_close = fn,
 *   widgets = {
 *     { type = "label",  x = 10, y = 10, text = "Hello", color = 0xFFFFFFFF },
 *     { type = "button", x = 10, y = 30, w = 80, h = 20, label = "Buy", on_click = function(e) ... e.inputs.name ... end },
 *     { type = "input",  id = "name", x = 10, y = 60, w = 120, value = "", hint = "Name" },
 *     { type = "item",   x = 100, y = 10, item = "minecraft:diamond" },
 *     { type = "rect",   x = 0, y = 0, w = 10, h = 10, color = 0x80FF0000 },
 *   } }
 * </pre>
 * Coordinates are relative to the centered panel.
 */
public final class PluginScreen extends Screen {
    private final String plugin;
    private final Map<String, Object> spec;
    private final Map<String, EditBox> inputs = new LinkedHashMap<>();
    private int left, top, w, h;

    PluginScreen(String plugin, Map<String, Object> spec) {
        super(Component.literal(str(spec.get("title"), plugin)));
        this.plugin = plugin;
        this.spec = spec;
    }

    @Override
    protected void init() {
        w = num(spec.get("width"), 220);
        h = num(spec.get("height"), 160);
        left = (width - w) / 2;
        top = (height - h) / 2;
        Map<String, String> keep = values();
        inputs.clear();
        for (Map<String, Object> wd : widgets()) {
            int x = left + num(wd.get("x"), 0), y = top + num(wd.get("y"), 0);
            switch (str(wd.get("type"), "")) {
                case "button" -> {
                    int cb = callback(wd.get("on_click"));
                    addRenderableWidget(Button.builder(Component.literal(str(wd.get("label"), "?")), b -> fire(cb))
                            .bounds(x, y, num(wd.get("w"), 80), num(wd.get("h"), 20)).build());
                }
                case "input" -> {
                    String id = str(wd.get("id"), "input" + inputs.size());
                    EditBox box = new EditBox(font, x, y, num(wd.get("w"), 120), num(wd.get("h"), 18), Component.literal(id));
                    box.setMaxLength(256);
                    box.setValue(keep.getOrDefault(id, str(wd.get("value"), "")));
                    if (wd.get("hint") != null) box.setHint(Component.literal(str(wd.get("hint"), "")));
                    inputs.put(id, box);
                    addRenderableWidget(box);
                }
                default -> {}
            }
        }
    }

    private Map<String, String> values() {
        Map<String, String> v = new LinkedHashMap<>();
        inputs.forEach((k, box) -> v.put(k, box.getValue()));
        return v;
    }

    private void fire(int cb) {
        PluginManager m = McAddonsClient.manager();
        if (m != null && cb > 0) m.callback(plugin, cb, Map.of("inputs", values()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(left - 4, top - 18, left + w + 4, top + h + 4, 0xE0101018);
        g.fill(left - 4, top - 18, left + w + 4, top - 4, 0xFF2A2A40);
        g.text(font, title.getString(), left, top - 14, 0xFFFFFFFF);
        for (Map<String, Object> wd : widgets()) {
            int x = left + num(wd.get("x"), 0), y = top + num(wd.get("y"), 0);
            switch (str(wd.get("type"), "")) {
                case "label" -> g.text(font, str(wd.get("text"), ""), x, y, (int) lnum(wd.get("color"), 0xFFFFFFFFL));
                case "rect" -> g.fill(x, y, x + num(wd.get("w"), 10), y + num(wd.get("h"), 10), (int) lnum(wd.get("color"), 0x80FFFFFFL));
                case "item" -> BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(str(wd.get("item"), "minecraft:air")))
                        .ifPresent(i -> g.item(new ItemStack(i), x, y));
                default -> {}
            }
        }
        super.extractRenderState(g, mx, my, pt);
    }

    @Override
    public void onClose() {
        int cb = callback(spec.get("on_close"));
        Minecraft.getInstance().gui.setScreen(null);
        fire(cb);
    }

    @Override public boolean isPauseScreen() { return false; }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> widgets() {
        Object o = spec.get("widgets");
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().filter(x -> x instanceof Map).map(x -> (Map<String, Object>) x).limit(128).toList();
    }

    private static int callback(Object o) {
        return o instanceof Map<?, ?> m && m.get("$cb") instanceof Number n ? n.intValue() : 0;
    }

    private static String str(Object o, String def) { return o == null ? def : String.valueOf(o); }
    private static int num(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }
    private static long lnum(Object o, long def) { return o instanceof Number n ? n.longValue() : def; }
}
