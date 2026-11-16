package dev.mcaddons.fabric.client;

import dev.mcaddons.core.Manifest;
import dev.mcaddons.core.Permission;
import dev.mcaddons.core.PluginManager;
import dev.mcaddons.core.Registry;
import dev.mcaddons.core.Side;
import dev.mcaddons.fabric.McAddons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Plugin store in the style of the vanilla world / resource pack selection screens. */
public final class PluginBrowserScreen extends Screen {
    private static final int ROW = 36, LIST_W = 300, TOP = 48;
    private final Screen parent;
    private boolean browseTab = true;
    private String status = "";
    private List<Row> rows = new ArrayList<>();
    private int selected = -1, scroll;
    private boolean loading;
    private Button install, toggle, reload, remove;

    record Row(String id, String name, String version, String desc, String authors, List<String> perms,
               String api, String icon, Registry.Entry remote, PluginManager.Info local) {}

    public PluginBrowserScreen(Screen parent) {
        super(Component.literal("Plugins"));
        this.parent = parent;
    }

    private PluginManager mgr() { return McAddonsClient.manager(); }
    private int listLeft() { return (width - LIST_W) / 2; }
    private int listBottom() { return height - 64; }

    @Override
    protected void init() {
        if (rows.isEmpty() && !loading) refresh(McAddons.REGISTRY.cached() == null);
        int cx = width / 2;
        Button browse = addRenderableWidget(Button.builder(Component.literal("Browse"), b -> { browseTab = true; selected = -1; refresh(false); }).bounds(cx - 102, 22, 100, 20).build());
        Button inst = addRenderableWidget(Button.builder(Component.literal("Installed"), b -> { browseTab = false; selected = -1; refresh(false); }).bounds(cx + 2, 22, 100, 20).build());
        browse.active = !browseTab;
        inst.active = browseTab;

        int y1 = height - 52, y2 = height - 28;
        install = addRenderableWidget(Button.builder(Component.literal("Install"), b -> installSelected()).bounds(cx - 154, y1, 100, 20).build());
        toggle = addRenderableWidget(Button.builder(Component.literal("Disable"), b -> toggleSelected()).bounds(cx - 50, y1, 100, 20).build());
        reload = addRenderableWidget(Button.builder(Component.literal("Reload"), b -> { Row r = sel(); if (r != null) both(m -> m.reload(r.id())); }).bounds(cx + 54, y1, 100, 20).build());
        remove = addRenderableWidget(Button.builder(Component.literal("Remove"), b -> removeSelected()).bounds(cx - 154, y2, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> refresh(true)).bounds(cx - 50, y2, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(cx + 54, y2, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Block Hiding"), b -> Minecraft.getInstance().gui.setScreen(new BlockHidingScreen(this)))
                .bounds(width - 106, 6, 100, 20).build());
        updateButtons();
    }

    private void updateButtons() {
        Row r = sel();
        boolean installed = r != null && r.local() != null;
        install.active = r != null && r.remote() != null && r.remote().compatible() && (!installed || newer(r));
        install.setMessage(Component.literal(installed && r.remote() != null && newer(r) ? "Update" : "Install"));
        toggle.active = installed;
        toggle.setMessage(Component.literal(installed && !r.local().enabled() ? "Enable" : "Disable"));
        reload.active = installed && r.local().enabled();
        remove.active = installed;
    }

    private static boolean newer(Row r) {
        return r.local().manifest() != null && !r.local().manifest().version.equals(r.remote().version);
    }

    private Row sel() { return selected >= 0 && selected < rows.size() ? rows.get(selected) : null; }

    private void refresh(boolean network) {
        if (network || McAddons.REGISTRY.cached() == null) {
            loading = true;
            status = "Loading plugins from GitHub...";
            McAddons.REGISTRY.fetch().whenComplete((idx, err) -> Minecraft.getInstance().execute(() -> {
                loading = false;
                status = err != null ? "Could not load the plugin list" : "";
                buildRows();
                if (minecraft != null) rebuildWidgets();
            }));
        }
        buildRows();
        if (minecraft != null) rebuildWidgets();
    }

    private void buildRows() {
        String keep = sel() == null ? null : sel().id();
        List<Row> out = new ArrayList<>();
        Map<String, PluginManager.Info> local = mgr() == null ? Map.of()
                : mgr().list().stream().collect(Collectors.toMap(PluginManager.Info::id, i -> i, (a, b) -> a));
        Registry.Index idx = McAddons.REGISTRY.cached();
        if (browseTab) {
            if (idx != null) for (Registry.Entry e : idx.plugins)
                out.add(new Row(e.id, e.name, e.version, e.description, String.join(", ", e.authors), e.permissions, e.api, e.icon, e, local.get(e.id)));
        } else {
            for (PluginManager.Info i : local.values()) {
                Manifest m = i.manifest();
                Registry.Entry remote = idx == null ? null : idx.plugins.stream().filter(e -> e.id.equals(i.id())).findFirst().orElse(null);
                out.add(m == null
                        ? new Row(i.id(), i.id(), "?", i.error() == null ? "" : i.error(), "", List.of(), "?", null, remote, i)
                        : new Row(i.id(), m.name, m.version, m.description, String.join(", ", m.authors), m.permissions, m.api,
                                remote == null ? null : remote.icon, remote, i));
            }
        }
        rows = out;
        selected = -1;
        for (int k = 0; k < rows.size(); k++) if (rows.get(k).id().equals(keep)) selected = k;
    }

    private void installSelected() {
        Row r = sel();
        if (r == null || r.remote() == null) return;
        if (r.remote().permissions.isEmpty()) { install(r.remote()); return; }
        String perms = r.remote().permissions.stream().map(p -> "• " + Permission.describe(p)).collect(Collectors.joining("\n"));
        minecraft.gui.setScreen(new ConfirmScreen(ok -> {
            minecraft.gui.setScreen(this);
            if (ok) install(r.remote());
        }, Component.literal("Install " + r.name() + "?"),
                Component.literal("This plugin is allowed to:\n" + perms + "\n\nOnly install plugins you trust."),
                Component.literal("Install"), Component.literal("Cancel")));
    }

    private void install(Registry.Entry e) {
        status = "Installing " + e.name + "...";
        McAddons.REGISTRY.install(e, McAddons.ROOT.resolve("plugins")).whenComplete((msg, err) -> Minecraft.getInstance().execute(() -> {
            if (err != null) { status = "Install failed: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()); return; }
            PluginIcons.invalidate(e.id);
            both(m -> m.reload(e.id));
            status = msg;
        }));
    }

    private void toggleSelected() {
        Row r = sel();
        if (r == null || r.local() == null) return;
        boolean on = r.local().enabled();
        both(m -> on ? m.disable(r.id()) : m.enable(r.id()));
    }

    private void removeSelected() {
        Row r = sel();
        if (r == null || r.local() == null) return;
        minecraft.gui.setScreen(new ConfirmScreen(ok -> {
            minecraft.gui.setScreen(this);
            if (ok) { PluginIcons.invalidate(r.id()); both(m -> { try { return m.remove(r.id()); } catch (Exception e) { return e.getMessage(); } }); }
        }, Component.literal("Remove " + r.name() + "?"), Component.literal("The plugin and its files will be deleted."),
                Component.literal("Remove"), Component.literal("Cancel")));
    }

    /** Applies an action to the client manager and (in singleplayer) the integrated server's manager. */
    private void both(Function<PluginManager, String> action) {
        if (mgr() != null) status = action.apply(mgr());
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) server.execute(() -> {
            PluginManager sm = McAddons.server();
            if (sm != null) {
                String r = action.apply(sm);
                Minecraft.getInstance().execute(() -> { status = r; buildRows(); rebuildWidgets(); });
            }
        });
        buildRows();
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        super.extractRenderState(g, mx, my, pt);
        g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);

        int left = listLeft(), bottom = listBottom();
        int visible = (bottom - TOP) / ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        if (rows.isEmpty())
            g.centeredText(font, loading ? "Loading..." : browseTab ? "No plugins found" : "No plugins installed", width / 2, TOP + 20, 0xFFA0A0A0);
        for (int k = 0; k < visible && k + scroll < rows.size(); k++) {
            int i = k + scroll, y = TOP + k * ROW;
            Row r = rows.get(i);
            if (i == selected) {
                g.fill(left - 2, y - 2, left + LIST_W + 2, y + ROW - 2, 0xFF808080);
                g.fill(left - 1, y - 1, left + LIST_W + 1, y + ROW - 3, 0xFF000000);
            } else if (mx >= left && mx < left + LIST_W && my >= y && my < y + ROW - 4) {
                g.fill(left - 2, y - 2, left + LIST_W + 2, y + ROW - 2, 0x30FFFFFF);
            }
            g.blit(RenderPipelines.GUI_TEXTURED, PluginIcons.get(r.id(), r.icon()), left, y, 0f, 0f, 32, 32, 32, 32);
            String name = r.name() + " §7" + r.version();
            String state = r.local() == null ? "" : !r.local().enabled() ? " §8(disabled)"
                    : r.local().running() ? " §2(installed)" : r.local().forSide(Side.CLIENT) ? " §c(error)" : " §2(installed)";
            if (r.remote() != null && r.local() != null && newer(r)) state = " §e(update available)";
            g.text(font, name + state, left + 36, y + 1, 0xFFFFFFFF);
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(r.desc() == null ? "" : r.desc()), LIST_W - 38);
            for (int l = 0; l < Math.min(2, lines.size()); l++) g.text(font, lines.get(l), left + 36, y + 12 + l * 10, 0xFF808080);
            if (mx >= left && mx < left + 32 && my >= y && my < y + 32 && !r.perms().isEmpty())
                g.setTooltipForNextFrame(Component.literal("Permissions:\n" + String.join("\n", r.perms().stream().map(Permission::describe).toList())), mx, my);
        }
        if (!status.isEmpty()) g.centeredText(font, status, width / 2, bottom + 2, 0xFFA0A0A0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (super.mouseClicked(e, dbl)) return true;
        int left = listLeft();
        if (e.x() >= left && e.x() < left + LIST_W && e.y() >= TOP && e.y() < listBottom()) {
            int i = (int) ((e.y() - TOP) / ROW) + scroll;
            if (i < rows.size()) { selected = i; updateButtons(); return true; }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        scroll -= (int) Math.signum(sy);
        return true;
    }

    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return true; }
}
