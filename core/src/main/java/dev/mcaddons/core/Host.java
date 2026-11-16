package dev.mcaddons.core;

import java.util.List;
import java.util.Optional;

/**
 * Everything the Lua API needs from Minecraft. This is the ONLY seam between plugins and the game:
 * porting MC Addons to another Minecraft version means implementing this interface again, and all
 * plugins keep working unchanged. Methods a side cannot support keep the default (unsupported).
 */
public interface Host {
    String minecraftVersion();
    Side side();

    // -- players ------------------------------------------------------------------------------
    default List<PlayerInfo> players() { return List.of(); }
    default Optional<PlayerInfo> player(String name) {
        return players().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }
    default void tell(String player, String message) { unsupported("tell"); }
    default void broadcast(String message) { unsupported("broadcast"); }
    default void actionbar(String player, String message) { unsupported("actionbar"); }
    default boolean teleport(String player, double x, double y, double z) { return unsupported("teleport"); }
    default boolean give(String player, String item, int count) { return unsupported("give"); }
    default boolean setHealth(String player, float health) { return unsupported("set_health"); }
    default boolean effect(String player, String effect, int seconds, int amplifier) { return unsupported("effect"); }

    // -- world --------------------------------------------------------------------------------
    default String block(String dimension, int x, int y, int z) { unsupported("get_block"); return null; }
    default boolean setBlock(String dimension, int x, int y, int z, String block) { return unsupported("set_block"); }
    default boolean spawn(String dimension, String entity, double x, double y, double z) { return unsupported("spawn"); }
    default boolean explode(String dimension, double x, double y, double z, float power) { return unsupported("explode"); }
    default long time(String dimension) { unsupported("time"); return 0; }
    default void setTime(String dimension, long time) { unsupported("set_time"); }
    default boolean particle(String dimension, String particle, double x, double y, double z, int count) { return unsupported("particle"); }
    default boolean sound(String dimension, String sound, double x, double y, double z, float volume, float pitch) { return unsupported("sound"); }

    // -- commands -----------------------------------------------------------------------------
    /** Runs a command as the server console. */
    default boolean runCommand(String command) { return unsupported("run"); }
    /** Called after plugins added/removed commands so the adapter can (re)publish them. */
    default void commandsChanged() {}

    // -- client -------------------------------------------------------------------------------
    default void toast(String title, String message) { unsupported("toast"); }
    default int screenWidth() { return 0; }
    default int screenHeight() { return 0; }
    default void bindKey(int slot, String label, String defaultKey) {}

    default boolean unsupported(String what) {
        throw new UnsupportedOperationException(what + " is not available on the " + side().name().toLowerCase() + " side");
    }

    record PlayerInfo(String name, String uuid, String dimension, double x, double y, double z,
                      float yaw, float pitch, float health, float maxHealth, int food,
                      String gamemode, String heldItem, boolean op) {}
}
