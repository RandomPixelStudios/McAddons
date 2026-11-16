package dev.mcaddons.fabric;

import dev.mcaddons.core.PluginManager;
import dev.mcaddons.core.Registry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Server-side entrypoint (also runs on the client, where it drives the integrated server). */
public final class McAddons implements ModInitializer {
    public static final Path ROOT = FabricLoader.getInstance().getGameDir().resolve("mcaddons");
    public static final Registry REGISTRY = new Registry(System.getProperty("mcaddons.index", Registry.DEFAULT_INDEX));
    private static volatile PluginManager server;

    /** The plugin manager of the running logical server, or null. */
    public static PluginManager server() { return server; }

    @Override
    public void onInitialize() {
        Content.registerAll(ROOT);
        CommandRegistrationCallback.EVENT.register((d, ctx, sel) -> ServerCommands.register(d));

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = new PluginManager(new ServerHost(s), Mc.LOG, ROOT);
            server.loadAll();
            ServerCommands.publish(s);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (server != null) server.shutdown();
            server = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> { if (server != null) server.tick(); });

        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> emit("player_join", h.player));
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> emit("player_leave", h.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((o, n, alive) -> emit("player_respawn", n));
        ServerLivingEntityEvents.AFTER_DEATH.register((e, src) -> {
            if (e instanceof ServerPlayer p) emit("player_death", p, "cause", src.getMsgId());
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((msg, p, params) ->
                !emit("chat", p, "message", msg.signedContent()));

        PlayerBlockBreakEvents.BEFORE.register((level, p, pos, state, be) ->
                !emitAt("block_break", p, level, pos, "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
        UseBlockCallback.EVENT.register((p, level, hand, hit) -> {
            if (level.isClientSide()) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            boolean cancel = emitAt("block_use", p, level, pos,
                    "block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString(),
                    "item", Mc.itemId(p.getItemInHand(hand)), "face", hit.getDirection().getName());
            return cancel ? InteractionResult.FAIL : InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((p, level, hand) -> {
            if (level.isClientSide()) return InteractionResult.PASS;
            return emit("item_use", p, "item", Mc.itemId(p.getItemInHand(hand))) ? InteractionResult.FAIL : InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((p, level, hand, entity, hit) -> {
            if (level.isClientSide()) return InteractionResult.PASS;
            return emit("attack_entity", p, "entity", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    "name", entity.getName().getString(), "x", entity.getX(), "y", entity.getY(), "z", entity.getZ())
                    ? InteractionResult.FAIL : InteractionResult.PASS;
        });
        Mc.LOG.info("MC Addons ready - plugins in " + ROOT.resolve("plugins"));
    }

    private static boolean emitAt(String ev, Player p, Level level, BlockPos pos, Object... kv) {
        Object[] all = new Object[kv.length + 8];
        System.arraycopy(kv, 0, all, 0, kv.length);
        int i = kv.length;
        all[i++] = "x"; all[i++] = pos.getX(); all[i++] = "y"; all[i++] = pos.getY();
        all[i++] = "z"; all[i++] = pos.getZ(); all[i++] = "dimension"; all[i] = level.dimension().identifier().toString();
        return emit(ev, p, all);
    }

    /** Emits an event with {player = name, ...}; returns true if a plugin cancelled it. */
    static boolean emit(String ev, Player p, Object... kv) {
        PluginManager m = server;
        if (m == null) return false;
        Map<String, Object> data = new LinkedHashMap<>();
        if (p != null) data.put("player", p.getName().getString());
        for (int i = 0; i + 1 < kv.length; i += 2) data.put(String.valueOf(kv[i]), kv[i + 1]);
        try {
            return m.emit(ev, data);
        } catch (Throwable t) {
            Mc.LOG.error("event " + ev + " failed", t);
            return false;
        }
    }
}
