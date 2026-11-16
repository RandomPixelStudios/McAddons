package dev.mcaddons.fabric.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.mcaddons.core.HudOp;
import dev.mcaddons.core.PluginManager;
import dev.mcaddons.fabric.Mc;
import dev.mcaddons.fabric.McAddons;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

public final class McAddonsClient implements ClientModInitializer {
    private static PluginManager client;

    public static PluginManager manager() { return client; }

    @Override
    public void onInitializeClient() {
        Keys.register();
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            client = new PluginManager(new ClientHost(), Mc.LOG, McAddons.ROOT);
            client.loadAll();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> { if (client != null) client.shutdown(); });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (client != null) client.tick();
            Keys.tick(mc);
        });
        ClientPlayConnectionEvents.JOIN.register((h, s, mc) -> emit("world_load", Map.of("player", mc.getUser().getName())));
        ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> emit("world_unload", Map.of()));
        ClientPlayerBlockBreakEvents.AFTER.register((level, p, pos, state) -> emit("block_break", Map.of(
                "player", p.getName().getString(), "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(), "dimension", level.dimension().identifier().toString())));

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("mcaddons", "plugins"), (g, delta) -> {
            if (client == null) return;
            Minecraft mc = Minecraft.getInstance();
            for (HudOp op : client.hud(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight())) {
                switch (op) {
                    case HudOp.Text t -> g.text(mc.font, t.text(), t.x(), t.y(), t.color(), t.shadow());
                    case HudOp.Rect r -> { if (r.w() > 0 && r.h() > 0) g.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), r.color()); }
                    case HudOp.Item i -> BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(i.item()))
                            .ifPresent(item -> g.item(new ItemStack(item), i.x(), i.y()));
                }
            }
        });

        // /pc <command> [args] runs commands registered by client-side plugins
        ClientCommandRegistrationCallback.EVENT.register((d, ctx) -> d.register(ClientCommands.literal("pc")
                .then(ClientCommands.argument("command", StringArgumentType.word())
                        .suggests((c, b) -> { if (client != null) client.commandNames().forEach(b::suggest); return b.buildFuture(); })
                        .executes(c -> pc(c.getSource(), StringArgumentType.getString(c, "command"), ""))
                        .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                                .executes(c -> pc(c.getSource(), StringArgumentType.getString(c, "command"), StringArgumentType.getString(c, "args")))))));

        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof TitleScreen) && !(screen instanceof PauseScreen)) return;
            Screens.getWidgets(screen).add(Button.builder(Component.literal("Plugins"),
                    b -> mc.gui.setScreen(new PluginBrowserScreen(screen))).bounds(w - 84, 6, 78, 20).build());
        });
    }

    private static int pc(FabricClientCommandSource src, String name, String args) {
        boolean ok = client != null && client.runCommand(name, new PluginManager.Sender() {
            public String name() { return src.getPlayer().getName().getString(); }
            public boolean isPlayer() { return true; }
            public void reply(String m) { src.sendFeedback(Component.literal(m)); }
        }, args);
        if (!ok) src.sendError(Component.literal("No client plugin provides '" + name + "'"));
        return ok ? 1 : 0;
    }

    private static void emit(String ev, Map<String, Object> data) {
        if (client != null) client.emit(ev, data);
    }
}
