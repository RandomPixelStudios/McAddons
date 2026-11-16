package dev.mcaddons.fabric.client;

import dev.mcaddons.fabric.Mc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Settings for the built-in block hiding. */
public final class BlockHidingScreen extends Screen {
    private static final int PER_PAGE = 8;
    private final Screen parent;
    private EditBox input;
    private int page;
    private String status = "";

    public BlockHidingScreen(Screen parent) {
        super(Component.literal("Block hiding"));
        this.parent = parent;
    }

    private List<String> list() {
        var c = BlockHiding.config();
        return "hide".equals(c.mode) ? c.hide : c.xray;
    }

    @Override
    protected void init() {
        var c = BlockHiding.config();
        int l = width / 2 - 150, t = 40;
        addRenderableWidget(Button.builder(Component.literal(BlockHiding.active() ? "§aActive (X)" : "§7Inactive (X)"), b -> { BlockHiding.toggle(); rebuildWidgets(); })
                .bounds(l, t, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Mode: " + ("hide".equals(c.mode) ? "hide listed" : "X-ray (show listed)")), b -> {
            c.mode = "hide".equals(c.mode) ? "xray" : "hide"; page = 0; BlockHiding.save(); rebuildWidgets();
        }).bounds(l + 100, t, 140, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
            c.xray = new ArrayList<>(BlockHiding.XRAY_DEFAULT); BlockHiding.save(); rebuildWidgets();
        }).bounds(l + 244, t, 56, 20).build());

        input = new EditBox(font, l, t + 26, 236, 20, Component.literal("block id"));
        input.setHint(Component.literal("block id, e.g. minecraft:diamond_ore"));
        addRenderableWidget(input);
        addRenderableWidget(Button.builder(Component.literal("Add"), b -> add()).bounds(l + 240, t + 26, 60, 20).build());

        List<String> ids = list();
        int pages = Math.max(1, (ids.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= ids.size()) break;
            String id = ids.get(idx);
            addRenderableWidget(Button.builder(Component.literal("Remove"), b -> { ids.remove(id); BlockHiding.save(); rebuildWidgets(); })
                    .bounds(l + 240, t + 54 + i * 20, 60, 18).build());
        }
        int by = t + 54 + PER_PAGE * 20 + 4;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(l, by, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(l + 90, by, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(l + 240, by, 60, 20).build());
    }

    private void add() {
        String id = input.getValue().trim();
        if (id.isEmpty()) return;
        try {
            var key = Mc.id(id);
            if (BuiltInRegistries.BLOCK.getOptional(key).isEmpty()) { status = "§cUnknown block " + id; return; }
            if (!list().contains(key.toString())) list().add(key.toString());
            BlockHiding.save();
            status = "§aAdded " + key;
            input.setValue("");
            rebuildWidgets();
        } catch (Exception e) {
            status = "§cInvalid id";
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        super.extractRenderState(g, mx, my, pt);
        int l = width / 2 - 150, t = 40;
        g.centeredText(font, "§lBlock hiding§r  §7(" + (BlockHiding.allowed() ? "§aallowed here" : "§cnot allowed on this server") + "§7)", width / 2, 14, 0xFFFFFFFF);
        g.centeredText(font, status, width / 2, 26, 0xFFFFFFFF);
        List<String> ids = list();
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= ids.size()) break;
            int y = t + 54 + i * 20;
            String id = ids.get(idx);
            BuiltInRegistries.ITEM.getOptional(Mc.id(id)).ifPresent(it -> g.item(new ItemStack(it), l, y));
            g.text(font, id.replace("minecraft:", ""), l + 20, y + 5, 0xFFFFFFFF);
        }
        int pages = Math.max(1, (ids.size() + PER_PAGE - 1) / PER_PAGE);
        g.text(font, (page + 1) + "/" + pages, l + 44, t + 54 + PER_PAGE * 20 + 10, 0xFFFFFFFF);
    }

    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
