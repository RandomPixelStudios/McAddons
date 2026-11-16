package dev.mcaddons.fabric;

import dev.mcaddons.core.Host;
import dev.mcaddons.core.Side;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;

/** Host implementation for the logical server of Minecraft 26.3. */
public final class ServerHost implements Host {
    private final MinecraftServer server;

    public ServerHost(MinecraftServer server) { this.server = server; }

    @Override public String minecraftVersion() { return Mc.version(); }
    @Override public Side side() { return Side.SERVER; }

    static PlayerInfo info(ServerPlayer p) {
        return new PlayerInfo(p.getName().getString(), p.getUUID().toString(), p.level().dimension().identifier().toString(),
                p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.getHealth(), p.getMaxHealth(),
                p.getFoodData().getFoodLevel(), p.gameMode().getName(), Mc.itemId(p.getMainHandItem()),
                p.level().getServer().getPlayerList().isOp(p.nameAndId()));
    }

    private ServerPlayer p(String name) {
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        if (p == null) throw new IllegalArgumentException("player not online: " + name);
        return p;
    }

    private ServerLevel level(String dim) {
        ServerLevel l = server.getLevel(ResourceKey.create(Registries.DIMENSION, Mc.id(dim)));
        if (l == null) throw new IllegalArgumentException("unknown dimension: " + dim);
        return l;
    }

    @Override public List<PlayerInfo> players() { return server.getPlayerList().getPlayers().stream().map(ServerHost::info).toList(); }
    @Override public Optional<PlayerInfo> player(String name) {
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        return p == null ? Optional.empty() : Optional.of(info(p));
    }
    @Override public void tell(String player, String m) { p(player).sendSystemMessage(Component.literal(m)); }
    @Override public void broadcast(String m) { server.getPlayerList().broadcastSystemMessage(Component.literal(m), false); }
    @Override public void actionbar(String player, String m) { p(player).sendOverlayMessage(Component.literal(m)); }
    @Override public boolean teleport(String player, double x, double y, double z) { p(player).teleportTo(x, y, z); return true; }
    @Override public boolean give(String player, String item, int count) {
        var it = BuiltInRegistries.ITEM.getOptional(Mc.id(item)).orElseThrow(() -> new IllegalArgumentException("unknown item " + item));
        return p(player).getInventory().add(new ItemStack(it, count));
    }
    @Override public int countItem(String player, String item) {
        var inv = p(player).getInventory(); var id = Mc.id(item); int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) { var st = inv.getItem(i); if (!st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).equals(id)) n += st.getCount(); }
        return n;
    }
    @Override public int takeItem(String player, String item, int count) {
        var inv = p(player).getInventory(); var id = Mc.id(item); int left = count;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            var st = inv.getItem(i);
            if (!st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).equals(id)) { int k = Math.min(left, st.getCount()); st.shrink(k); left -= k; }
        }
        inv.setChanged();
        return count - left;
    }
    @Override public boolean setHealth(String player, float h) { ServerPlayer p = p(player); p.setHealth(Math.max(0, Math.min(h, p.getMaxHealth()))); return true; }
    @Override public boolean effect(String player, String effect, int seconds, int amp) {
        var holder = BuiltInRegistries.MOB_EFFECT.get(Mc.id(effect)).orElseThrow(() -> new IllegalArgumentException("unknown effect " + effect));
        return p(player).addEffect(new MobEffectInstance(holder, Math.min(seconds, 3600) * 20, Math.max(0, Math.min(amp, 9))));
    }

    @Override public String block(String dim, int x, int y, int z) {
        return BuiltInRegistries.BLOCK.getKey(level(dim).getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
    }
    @Override public boolean setBlock(String dim, int x, int y, int z, String block) {
        var b = BuiltInRegistries.BLOCK.getOptional(Mc.id(block)).orElseThrow(() -> new IllegalArgumentException("unknown block " + block));
        return level(dim).setBlockAndUpdate(new BlockPos(x, y, z), b.defaultBlockState());
    }
    @Override public boolean spawn(String dim, String entity, double x, double y, double z) {
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(Mc.id(entity)).orElseThrow(() -> new IllegalArgumentException("unknown entity " + entity));
        if (Mc.id(entity).getPath().equals("player")) throw new IllegalArgumentException("cannot spawn players");
        return type.spawn(level(dim), BlockPos.containing(x, y, z), EntitySpawnReason.COMMAND) != null;
    }
    @Override public boolean explode(String dim, double x, double y, double z, float power) {
        level(dim).explode(null, x, y, z, power, Level.ExplosionInteraction.TNT);
        return true;
    }
    @Override public long time(String dim) { return level(dim).getOverworldClockTime(); }
    @Override public void setTime(String dim, long t) { runCommand("time set " + Math.floorMod(t, 24000L)); }
    @Override public boolean particle(String dim, String particle, double x, double y, double z, int count) {
        var type = BuiltInRegistries.PARTICLE_TYPE.getOptional(Mc.id(particle)).orElseThrow(() -> new IllegalArgumentException("unknown particle " + particle));
        if (!(type instanceof ParticleOptions opts)) throw new IllegalArgumentException("particle needs parameters: " + particle);
        level(dim).sendParticles(opts, x, y, z, count, 0.3, 0.3, 0.3, 0.02);
        return true;
    }
    @Override public boolean sound(String dim, String sound, double x, double y, double z, float v, float pitch) {
        var ev = BuiltInRegistries.SOUND_EVENT.getOptional(Mc.id(sound))
                .orElseGet(() -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(Mc.id(sound)));
        level(dim).playSound(null, x, y, z, ev, SoundSource.MASTER, v, pitch);
        return true;
    }
    @Override public boolean runCommand(String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command.startsWith("/") ? command.substring(1) : command);
        return true;
    }
    @Override public boolean openMenu(String plugin, String player, String title, int rows, java.util.Map<Integer, String> items, int callback) {
        ServerPlayer p = p(player);
        var container = new net.minecraft.world.SimpleContainer(rows * 9);
        items.forEach((slot, id) -> BuiltInRegistries.ITEM.getOptional(Mc.id(id)).ifPresent(i -> container.setItem(slot, new ItemStack(i))));
        p.openMenu(new net.minecraft.world.SimpleMenuProvider((cid, inv, pl) ->
                new PluginMenu(PluginMenu.type(rows), cid, inv, container, rows, plugin, callback), Component.literal(title)));
        return true;
    }

    @Override public void sendMessage(String plugin, String player, String channel, String json) {
        var msg = new McaMessage(plugin, channel, json);
        if (player != null) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p(player), msg);
        else for (ServerPlayer sp : server.getPlayerList().getPlayers())
            if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(sp, McaMessage.TYPE))
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(sp, msg);
    }

    @Override public void hidingAllow(String player, boolean allowed) { HidingAllowed.send(p(player), allowed); }

    @Override public boolean gamerule(String rule, String value) { return runCommand("gamerule " + rule + " " + value); }

    @Override public void commandsChanged() { ServerCommands.publish(server); }
}
