package dev.mcaddons.fabric;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Server → client: whether this player may use block hiding on this server. */
public record HidingAllowed(boolean allowed) implements CustomPacketPayload {
    public static final Type<HidingAllowed> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcaddons", "hiding_allowed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HidingAllowed> CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, HidingAllowed::allowed, HidingAllowed::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    static boolean allowedFor(ServerPlayer p) {
        ServerConfig c = ServerConfig.get();
        if (!c.allow_block_hiding) return false;
        return !c.block_hiding_ops_only || p.level().getServer().getPlayerList().isOp(p.nameAndId());
    }

    static void send(ServerPlayer p) { send(p, allowedFor(p)); }

    static void send(ServerPlayer p, boolean allowed) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, TYPE))
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new HidingAllowed(allowed));
    }

    /** Re-sends the permission to everyone (after /mca blockhiding). */
    static void broadcast(net.minecraft.server.MinecraftServer s) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) send(p);
    }
}
