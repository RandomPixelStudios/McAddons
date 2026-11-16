package dev.mcaddons.fabric.client;

import dev.mcaddons.core.Host;
import dev.mcaddons.core.Side;
import dev.mcaddons.fabric.Mc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Host for client plugins (HUD, keys, client-side info). Only does what the player could do. */
public final class ClientHost implements Host {
    private final Minecraft mc = Minecraft.getInstance();

    @Override public String minecraftVersion() { return Mc.version(); }
    @Override public Side side() { return Side.CLIENT; }

    @Override public List<PlayerInfo> players() {
        LocalPlayer p = mc.player;
        if (p == null) return List.of();
        return List.of(new PlayerInfo(p.getName().getString(), p.getUUID().toString(), p.level().dimension().identifier().toString(),
                p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.getHealth(), p.getMaxHealth(),
                p.getFoodData().getFoodLevel(), p.isCreative() ? "creative" : p.isSpectator() ? "spectator" : "survival",
                Mc.itemId(p.getMainHandItem()), false));
    }

    @Override public void tell(String player, String m) { if (mc.player != null) mc.player.sendSystemMessage(Component.literal(m)); }
    @Override public void broadcast(String m) { tell(null, m); }
    @Override public void actionbar(String player, String m) { if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(m)); }
    @Override public void toast(String title, String m) {
        SystemToast.addOrUpdate(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal(title), Component.literal(m));
    }

    @Override public String block(String dim, int x, int y, int z) {
        if (mc.level == null) throw new IllegalStateException("no world loaded");
        return BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
    }
    @Override public long time(String dim) { return mc.level == null ? 0 : mc.level.getOverworldClockTime(); }

    @Override public boolean runCommand(String command) {
        if (mc.player == null) return false;
        mc.player.connection.sendCommand(command.startsWith("/") ? command.substring(1) : command);
        return true;
    }

    @Override public void openScreen(String plugin, java.util.Map<String, Object> spec) {
        mc.execute(() -> mc.gui.setScreen(new PluginScreen(plugin, spec)));
    }
    @Override public void closeScreen() { mc.execute(() -> { if (mc.gui.screen() instanceof PluginScreen) mc.gui.setScreen(null); }); }
    @Override public void sendMessage(String plugin, String player, String channel, String json) {
        if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.mcaddons.fabric.McaMessage.TYPE))
            throw new IllegalStateException("not connected to a server with MC Addons");
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.mcaddons.fabric.McaMessage(plugin, channel, json));
    }

    @Override public int screenWidth() { return mc.getWindow().getGuiScaledWidth(); }
    @Override public int screenHeight() { return mc.getWindow().getGuiScaledHeight(); }
    @Override public void bindKey(int slot, String label, String defaultKey) { Keys.bind(slot, label, defaultKey); }
}
