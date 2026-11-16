package dev.mcaddons.fabric;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** One packet type carries all plugin messages: plugin id + channel + JSON body (max 32 KB). */
public record McaMessage(String plugin, String channel, String json) implements CustomPacketPayload {
    public static final Type<McaMessage> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcaddons", "msg"));
    public static final StreamCodec<RegistryFriendlyByteBuf, McaMessage> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), McaMessage::plugin,
            ByteBufCodecs.stringUtf8(64), McaMessage::channel,
            ByteBufCodecs.stringUtf8(32_768), McaMessage::json,
            McaMessage::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
