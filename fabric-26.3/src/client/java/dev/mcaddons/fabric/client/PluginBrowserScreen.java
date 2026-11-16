package dev.mcaddons.fabric.client;

import dev.mcaddons.core.Manifest;
import dev.mcaddons.core.Permission;
import dev.mcaddons.core.PluginManager;
import dev.mcaddons.core.Registry;
import dev.mcaddons.fabric.McAddons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * In-game plugin store. "Browse" shows the GitHub registry, "Installed" shows local plugins.
 * Install asks for permission consent first; everything else is one click and hot-reloads.
 */
public final class PluginBrowserScreen extends Screen {
    private static final int ROW = 26;
    private final Screen parent;
    private boolean browseTab = true;
    private String status = "";
    private List<Row> rows = new ArrayList<>();
    private int selected = -1;
    private int scroll;
    private boolean loading;
    private Registry.Entry pendingConsent;

    record Row(String id, String name, String version, String desc, String authors, List<String> perms,
               String side, String api, Registry.Entry remote, PluginManager.Info local) {}

    public PluginBrowserScreen(Screen parent) {
        super(Component.literal("MC Addons - Plugins"));
        this.parent = parent;
    }

    private PluginManager mgr() { return McAddonsClient.manager(); }

    @Override
    protected void init() {
        if (rows.isEmpty() && !loading) refresh(McAddons.REGISTRY.cached() == null);
        int x = 10;
        addRenderableWidget(Button.builder(Component.literal((browseTab ? "§e" : "") + "Browse"), b -> { browseTab = true; selected = -1; refresh(false); }).bounds(x, 26, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal((!browseTab ? "§e" : "") + "Installed"), b -> { browseTab = false; selected = -1; refresh(false); }).bounds(x + 74, 26, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> refresh(true)).bounds(x + 148, 26, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width - 70, height - 26, 60, 20).build());

        if (pendingConsent != null) {
            addRenderableWidget(Button.builder(Component.literal("§aAllow & install"), b -> { Registry.Entry e = pendingConsent; pendingConsent = null; install(e); rebuildWidgets(); })
                    .bounds(width / 2 - 104, height - 60, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> { pendingConsent = null; rebuildWidgets(); })
                    .bounds(width / 2 + 4, height - 60, 100, 20).build());
            return;
        }

        Row r = sel();
        if (r == null) return;
        int bx = listRight() + 10, by = height - 52;
        List<Button> buttons = new ArrayList<>();
        boolean installed = r.local() != null;
        if (r.remote() != null && (!installed || newer(r))) {
            buttons.add(Button.builder(Component.literal(installed ? "Update" : "Install"), b -> {
                if (r.remote().permissions.isEmpty()) install(r.remote()); else { pendingConsent = r.remote(); rebuildWidgets(); }
            }).width(70).build());
            buttons.get(buttons.size() - 1).active = r.remote().compatible();
        }
        if (installed) {
            if (r.local().enabled()) buttons.add(Button.builder(Component.literal("Disable"), b -> both(m -> m.disable(r.id()))).width(60).build());
            else buttons.add(Button.builder(Component.literal("Enable"), b -> both(m -> m.enable(r.id()))).width(60).build());
            buttons.add(Button.builder(Component.literal("Reload"), b -> both(m -> m.reload(r.id()))).width(60).build());
            buttons.add(Button.builder(Component.literal("§cRemove"), b -> both(m -> { try { return m.remove(r.id()); } catch (Exception e) { return e.getMessage(); } })).width(60).build());
        }
        for (Button b : buttons) { b.setPosition(bx, by); bx += b.getWidth() + 4; addRenderableWidget(b); }
    }

    private static boolean newer(Row r) {
        return r.local().manifest() != null && !r.local().manifest().version.equals(r.remote().version);
    }

    private int listRight() { return Math.max(170, width * 2 / 5); }
    private Row sel() { return selected >= 0 && selected < rows.size() ? rows.get(selected) : null; }

    private void refresh(boolean network) {
        if (network || McAddons.REGISTRY.cached() == null) {
            loading = true;
            status = "Loading plugin index from GitHub...";
            McAddons.REGISTRY.fetch().whenComplete((idx, err) -> Minecraft.getInstance().execute(() -> {
                loading = false;
                status = err != null ? "§c" + err.getMessage() : "§a" + idx.plugins.size() + " plugins in the registry";
                buildRows();
                rebuildWidgets();
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
                out.add(new Row(e.id, e.name, e.version, e.description, String.join(", ", e.authors), e.permissions, e.side, e.api, e, local.get(e.id)));
        } else {
            for (PluginManager.Info i : local.values()) {
                Manifest m = i.manifest();
                Registry.Entry remote = idx == null ? null : idx.plugins.stream().filter(e -> e.id.equals(i.id())).findFirst().orElse(null);
                out.add(m == null
                        ? new Row(i.id(), i.id(), "?", "", "", List.of(), "?", "?", remote, i)
                        : new Row(i.id(), m.name, m.version, m.description, String.join(", ", m.authors), m.permissions, m.side, m.api, remote, i));
            }
        }
        rows = out;
        selected = -1;
        for (int k = 0; k < rows.size(); k++) if (rows.get(k).id().equals(keep)) selected = k;
    }

    private void install(Registry.Entry e) {
        status = "Installing " + e.name + "...";
        McAddons.REGISTRY.install(e, McAddons.ROOT.resolve("plugins")).whenComplete((msg, err) -> Minecraft.getInstance().execute(() -> {
            if (err != null) { status = "§cInstall failed: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()); return; }
            both(m -> m.reload(e.id));
            status = "§a" + msg;
        }));
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
        g.text(font, "§lMC Addons§r  Plugins  §7(API " + dev.mcaddons.core.ApiVersion.STRING + ", MC " + dev.mcaddons.fabric.Mc.version() + ")", 10, 10, 0xFFFFFFFF);
        g.text(font, status, 220, 32, 0xFFC0C0C0);

        int top = 52, bottom = height - 32, right = listRight();
        g.fill(8, top, right, bottom, 0x90000000);
        if (rows.isEmpty()) g.text(font, loading ? "Loading..." : browseTab ? "No plugins found." : "Nothing installed yet.", 14, top + 6, 0xFFA0A0A0);
        int visible = (bottom - top) / ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        for (int k = 0; k < visible && k + scroll < rows.size(); k++) {
            int i = k + scroll, y = top + k * ROW;
            Row r = rows.get(i);
            if (i == selected) g.fill(8, y, right, y + ROW, 0x60FFFFFF);
            else if (mx >= 8 && mx < right && my >= y && my < y + ROW) g.fill(8, y, right, y + ROW, 0x30FFFFFF);
            g.text(font, r.name() + " §7" + r.version(), 14, y + 4, 0xFFFFFFFF);
            String badge = r.local() == null ? "" : !r.local().enabled() ? "§7● disabled" : r.local().running() ? "§a● running"
                    : !r.local().forSide(dev.mcaddons.core.Side.CLIENT) ? "§b● server plugin" : "§c● error";
            if (r.remote() != null && r.local() != null && newer(r)) badge += " §e↑ update";
            g.text(font, "§7" + trim(r.authors(), 24) + "  " + badge, 14, y + 14, 0xFFFFFFFF);
        }

        int px = right + 10, pw = width - px - 10;
        if (pendingConsent != null) {
            Registry.Entry e = pendingConsent;
            g.fill(width / 2 - 160, 60, width / 2 + 160, height - 30, 0xE0101010);
            g.centeredText(font, "§l" + e.name + "§r wants permission to:", width / 2, 70, 0xFFFFFFFF);
            int y = 90;
            for (String p : e.permissions) {
                String c = p.equals("commands.run") ? "§c" : "§e";
                g.text(font, c + "• " + p + " §7- " + Permission.describe(p), width / 2 - 150, y, 0xFFFFFFFF);
                y += 12;
            }
            g.centeredText(font, "§7Files are verified with SHA-256 from the reviewed registry.", width / 2, y + 10, 0xFFFFFFFF);
            return;
        }
        Row r = sel();
        if (r == null) {
            g.textWithWordWrap(font, Component.literal("Select a plugin.\n\nPlugins are Lua scripts that run in a sandbox and hot-reload instantly. "
                    + "Make your own with /mca new <id> and edit it in " + McAddons.ROOT.resolve("plugins") + "\n\nPublish it by opening a pull request to github.com/RandomPixelStudios/McAddons-Plugins"), px, top + 4, pw, 0xFFC0C0C0);
            return;
        }
        int y = top + 4;
        g.text(font, "§l" + r.name(), px, y, 0xFFFFFFFF); y += 12;
        g.text(font, "§7" + r.id() + " · v" + r.version() + " · " + r.side() + " · API " + r.api(), px, y, 0xFFFFFFFF); y += 12;
        if (!r.authors().isEmpty()) { g.text(font, "§7by " + r.authors(), px, y, 0xFFFFFFFF); y += 12; }
        y += 4;
        y += g.textWithWordWrap(font, Component.literal(r.desc() == null ? "" : r.desc()), px, y, pw, 0xFFDDDDDD) + 8;
        g.text(font, "§6Permissions", px, y, 0xFFFFFFFF); y += 12;
        if (r.perms().isEmpty()) { g.text(font, "§7none", px, y, 0xFFFFFFFF); y += 11; }
        for (String p : r.perms()) { g.text(font, "§e" + p + " §7" + Permission.describe(p), px, y, 0xFFFFFFFF); y += 11; }
        if (r.local() != null && r.local().error() != null) {
            y += 6;
            g.textWithWordWrap(font, Component.literal("§cError: " + r.local().error()), px, y, pw, 0xFFFFFFFF);
        }
        if (r.remote() != null && !r.remote().compatible())
            g.text(font, "§cNeeds API " + r.remote().api + " - update MC Addons", px, height - 64, 0xFFFFFFFF);
    }

    private static String trim(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) + "…" : s; }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (super.mouseClicked(e, dbl)) return true;
        if (pendingConsent != null) return false;
        int top = 52, bottom = height - 32;
        if (e.x() >= 8 && e.x() < listRight() && e.y() >= top && e.y() < bottom) {
            int i = (int) ((e.y() - top) / ROW) + scroll;
            if (i < rows.size()) { selected = i; rebuildWidgets(); return true; }
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
