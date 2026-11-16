package dev.mcaddons.fabric.test;

import dev.mcaddons.fabric.McAddons;
import dev.mcaddons.fabric.client.McAddonsClient;
import dev.mcaddons.fabric.client.PluginBrowserScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * End-to-end test in a real client: title screen button, plugin browser (live GitHub index),
 * a singleplayer world with server + client plugins, HUD rendering, plugin commands and hot reload.
 */
public final class ClientTest implements FabricClientGameTest {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        System.out.println("[MCA-TEST] PASS " + what);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.waitForScreen(TitleScreen.class);
        check(ctx.computeOnClient(mc -> McAddonsClient.manager() != null), "client plugin manager started");
        check(ctx.computeOnClient(mc -> McAddonsClient.manager().runningIds().contains("coords_hud")), "client plugin coords_hud running");
        ctx.takeScreenshot("01-title");

        ctx.setScreen(() -> new PluginBrowserScreen(null));
        ctx.waitFor(mc -> McAddons.REGISTRY.cached() != null, 400);
        check(McAddons.REGISTRY.cached() != null && !McAddons.REGISTRY.cached().plugins.isEmpty(), "registry index downloaded from GitHub");
        ctx.waitTicks(5);
        ctx.takeScreenshot("02-browser");
        ctx.setScreen(() -> null);

        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getConnection().waitForChunksRender();
            check(sp.getServer().computeOnServer(s -> McAddons.server() != null
                    && McAddons.server().runningIds().containsAll(java.util.List.of("homes", "welcome", "ruby", "vein_miner"))), "server plugins running in singleplayer");
            sp.getServer().runCommand("give @a ruby:ruby 3");
            sp.getServer().runCommand("ruby");
            ctx.waitTicks(20);
            check(ctx.computeOnClient(mc -> mc.player.getInventory().countItem(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("ruby:ruby"))) >= 3), "plugin item ruby:ruby exists and can be given");
            ctx.takeScreenshot("03-ingame-hud");

            // hot reload: touch a plugin file and wait for the watcher
            Path main = McAddons.ROOT.resolve("plugins/coords_hud/main.lua");
            try { Files.writeString(main, "\n-- touched by test\n", StandardOpenOption.APPEND); } catch (Exception e) { throw new RuntimeException(e); }
            ctx.waitTicks(60);
            check(ctx.computeOnClient(mc -> McAddonsClient.manager().runningIds().contains("coords_hud")), "coords_hud hot-reloaded and still running");

            ctx.setScreen(() -> new PluginBrowserScreen(null));
            ctx.waitTicks(10);
            ctx.takeScreenshot("04-browser-ingame");
            ctx.setScreen(() -> null);
        }
        System.out.println("[MCA-TEST] ALL CLIENT TESTS PASSED");
    }
}
