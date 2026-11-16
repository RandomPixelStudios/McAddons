package dev.mcaddons.fabric.client;

import dev.mcaddons.core.PluginManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vanilla-style screen built from a Lua table (mc.gui.open). Widgets:
 * label, button, input, checkbox, slider, slot (item in an inventory slot, clickable), item, separator.
 * Layout: by default coordinates are relative to a centered area of width x height;
 * with {@code fullscreen = true} they are absolute and negative x/y count from the right/bottom edge.
 * Callbacks receive {value = ..., inputs = {id = text}}.
 */
public final class PluginScreen extends Screen {
    private static final Identifier SLOT = Identifier.withDefaultNamespace("container/slot");
    private final String plugin;
    private final Map<String, Object> spec;
    private final Map<String, EditBox> inputs = new LinkedHashMap<>();
    private int left, top, w, h;
    private boolean full;

    PluginScreen(String plugin, Map<String, Object> spec) {
        super(Component.literal(str(spec.get("title"), plugin)));
        this.plugin = plugin;
        this.spec = spec;
    }

    private int x(Map<String, Object> wd) { int v = num(wd.get("x"), 0); return full ? (v < 0 ? width + v : v) : left + v; }
    private int y(Map<String, Object> wd) { int v = num(wd.get("y"), 0); return full ? (v < 0 ? height + v : v) : top + v; }

    @Override
    protected void init() {
        full = Boolean.TRUE.equals(spec.get("fullscreen"));
        w = num(spec.get("width"), 220);
        h = num(spec.get("height"), 160);
        left = (width - w) / 2;
        top = Math.max(32, (height - h) / 2);
        Map<String, String> keep = values();
        inputs.clear();
        for (Map<String, Object> wd : widgets()) {
            int x = x(wd), y = y(wd), ww = num(wd.get("w"), 150), hh = num(wd.get("h"), 20);
            switch (str(wd.get("type"), "")) {
                case "button" -> {
                    int cb = callback(wd.get("on_click"));
                    Button b = Button.builder(Component.literal(str(wd.get("label"), "?")), btn -> fire(cb, null))
                            .bounds(x, y, num(wd.get("w"), 98), hh).build();
                    b.active = !Boolean.FALSE.equals(wd.get("enabled"));
                    addRenderableWidget(b);
                }
                case "input" -> {
                    String id = str(wd.get("id"), "input" + inputs.size());
                    EditBox box = new EditBox(font, x, y, ww, hh, Component.literal(id));
                    box.setMaxLength(256);
                    box.setValue(keep.getOrDefault(id, str(wd.get("value"), "")));
                    if (wd.get("hint") != null) box.setHint(Component.literal(str(wd.get("hint"), "")));
                    inputs.put(id, box);
                    addRenderableWidget(box);
                }
                case "checkbox" -> {
                    int cb = callback(wd.get("on_change"));
                    addRenderableWidget(Checkbox.builder(Component.literal(str(wd.get("label"), "")), font).pos(x, y)
                            .selected(Boolean.TRUE.equals(wd.get("checked")))
                            .onValueChange((c, sel) -> fire(cb, sel)).build());
                }
                case "slider" -> {
                    int cb = callback(wd.get("on_change"));
                    int min = num(wd.get("min"), 0), max = Math.max(min + 1, num(wd.get("max"), 100));
                    addRenderableWidget(new Slider(x, y, ww, hh, str(wd.get("label"), ""), min, max, num(wd.get("value"), min), v -> fire(cb, v)));
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

    private void fire(int cb, Object value) {
        PluginManager m = McAddonsClient.manager();
        if (m == null || cb <= 0) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inputs", values());
        if (value != null) data.put("value", value);
        m.callback(plugin, cb, data);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        super.extractRenderState(g, mx, my, pt);
        if (!Boolean.FALSE.equals(spec.get("show_title"))) g.centeredText(font, title, width / 2, full ? 10 : top - 20, 0xFFFFFFFF);
        ItemStack hover = null;
        for (Map<String, Object> wd : widgets()) {
            int x = x(wd), y = y(wd);
            switch (str(wd.get("type"), "")) {
                case "label" -> g.text(font, str(wd.get("text"), ""), x, y, (int) lnum(wd.get("color"), 0xFFFFFFFFL));
                case "separator" -> g.fill(x, y, x + num(wd.get("w"), 100), y + 1, 0x50FFFFFF);
                case "item" -> { ItemStack st = stack(wd); if (st != null) g.item(st, x, y); }
                case "slot" -> {
                    g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, x, y, 18, 18);
                    ItemStack st = stack(wd);
                    if (st != null) g.item(st, x + 1, y + 1);
                    if (mx >= x && mx < x + 18 && my >= y && my < y + 18) {
                        g.fill(x + 1, y + 1, x + 17, y + 17, 0x80FFFFFF);
                        if (st != null) hover = st;
                    }
                }
                default -> {}
            }
        }
        if (hover != null) g.setTooltipForNextFrame(font, hover, mx, my);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (super.mouseClicked(e, dbl)) return true;
        for (Map<String, Object> wd : widgets()) {
            if (!"slot".equals(wd.get("type"))) continue;
            int x = x(wd), y = y(wd);
            if (e.x() >= x && e.x() < x + 18 && e.y() >= y && e.y() < y + 18) {
                fire(callback(wd.get("on_click")), e.button());
                return true;
            }
        }
        return false;
    }

    @Override
    public void onClose() {
        int cb = callback(spec.get("on_close"));
        Minecraft.getInstance().gui.setScreen(null);
        fire(cb, null);
    }

    @Override public boolean isPauseScreen() { return Boolean.TRUE.equals(spec.get("pause")); }

    private static ItemStack stack(Map<String, Object> wd) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(str(wd.get("item"), "minecraft:air")))
                .map(ItemStack::new).filter(s -> !s.isEmpty()).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> widgets() {
        Object o = spec.get("widgets");
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().filter(x -> x instanceof Map).map(x -> (Map<String, Object>) x).limit(512).toList();
    }

    private static int callback(Object o) { return o instanceof Map<?, ?> m && m.get("$cb") instanceof Number n ? n.intValue() : 0; }
    private static String str(Object o, String def) { return o == null ? def : String.valueOf(o); }
    private static int num(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }
    private static long lnum(Object o, long def) { return o instanceof Number n ? n.longValue() : def; }

    /** Integer slider like the vanilla option sliders: "Label: value". */
    private static final class Slider extends AbstractSliderButton {
        private final String label;
        private final int min, max;
        private final java.util.function.IntConsumer onChange;
        private int last;

        Slider(int x, int y, int w, int h, String label, int min, int max, int value, java.util.function.IntConsumer onChange) {
            super(x, y, w, h, Component.empty(), (double) (Math.max(min, Math.min(max, value)) - min) / (max - min));
            this.label = label; this.min = min; this.max = max; this.onChange = onChange;
            this.last = current();
            updateMessage();
        }

        private int current() { return min + (int) Math.round(value * (max - min)); }
        @Override protected void updateMessage() { setMessage(Component.literal(label + ": " + current())); }
        @Override protected void applyValue() {
            int v = current();
            if (v != last) { last = v; onChange.accept(v); }
        }
    }
}
