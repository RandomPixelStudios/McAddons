package dev.mcaddons.core;

import java.util.Set;

/**
 * Event names are part of the stable API. Handlers receive one table argument. Handlers of
 * cancellable events may return {@code false} to cancel the action.
 */
public final class Events {
    private Events() {}

    public static final Set<String> ALL = Set.of(
            "enable", "disable", "tick",
            "player_join", "player_leave", "player_respawn", "player_death",
            "chat",            // {player, message}            cancellable (server)
            "block_break",     // {player, block, x, y, z, dimension} cancellable
            "block_use",       // {player, block, x, y, z, dimension, item} cancellable
            "item_use",        // {player, item}               cancellable
            "attack_entity",   // {player, entity, name}       cancellable
            "world_load", "world_unload",
            "hud",             // {width, height} – client only, draw with mc.hud.*
            "render",          // {} – client only, draw in the world with mc.render.*
            "message"          // {channel, data, player} – from the other side of the same plugin (mc.net.send)
    );

    public static final Set<String> CANCELLABLE = Set.of("chat", "block_break", "block_use", "item_use", "attack_entity");
}
