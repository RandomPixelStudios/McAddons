package dev.mcaddons.core;

import java.util.Locale;
import java.util.Optional;

/**
 * Capabilities a plugin must declare in plugin.json. Anything not declared is denied at runtime,
 * and the player sees the list before installing.
 */
public enum Permission {
    CHAT("chat", "Read chat and send messages"),
    PLAYERS("players", "Read player names, positions and stats"),
    PLAYERS_MODIFY("players.modify", "Teleport, heal and give items to players"),
    WORLD_READ("world.read", "Read blocks and world time"),
    WORLD_WRITE("world.write", "Place/break blocks, spawn entities, change time"),
    EFFECTS("effects", "Play sounds and spawn particles"),
    COMMANDS("commands", "Add new chat commands"),
    COMMANDS_RUN("commands.run", "Run ANY server command as the console (dangerous)"),
    HUD("hud", "Draw on the screen overlay"),
    KEYS("keys", "Add key bindings"),
    STORAGE("storage", "Save its own data"),
    CONTENT("content", "Add new items and blocks (requires restart)");

    public final String id;
    public final String description;

    Permission(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public static Optional<Permission> byId(String id) {
        if (id == null) return Optional.empty();
        String n = id.toLowerCase(Locale.ROOT).trim();
        for (Permission p : values()) if (p.id.equals(n)) return Optional.of(p);
        return Optional.empty();
    }

    public static String describe(String id) {
        return byId(id).map(p -> p.description).orElse("Unknown permission");
    }
}
