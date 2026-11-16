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
    default int countItem(String player, String item) { unsupported("count_item"); return 0; }
    /** Removes up to count items; returns how many were removed. */
    default int takeItem(String player, String item, int count) { unsupported("take_item"); return 0; }
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
    /** Client: true in singleplayer (integrated server), false on a remote server. */
    default boolean singleplayer() { return false; }
    default int screenWidth() { return 0; }
    default int screenHeight() { return 0; }
    default void bindKey(int slot, String label, String defaultKey) {}

    // -- GUI / menus / network / rules (API 1.1) ----------------------------------------------
    /** Client: opens a screen described by plain data; callbacks are ids for {@link PluginManager#callback}. */
    default void openScreen(String pluginId, java.util.Map<String, Object> spec) { unsupported("gui.open"); }
    default void closeScreen() { unsupported("gui.close"); }
    /** Server: opens a chest-style menu. items: slot(0-based) -> item id. Clicks go to callback id. */
    default boolean openMenu(String pluginId, String player, String title, int rows, java.util.Map<Integer, String> items, int callback) { return unsupported("menu.open"); }
    /** Sends a message to the same plugin on the other side. player == null: server→all players / client→server. */
    default void sendMessage(String pluginId, String player, String channel, String json) { unsupported("net.send"); }
    /** Adds a button to the title and pause screen (client). */
    default void addMenuButton(String pluginId, String label, int callback) { unsupported("gui.menu_button"); }
    default void removeMenuButtons(String pluginId) {}

    // -- block hiding (engine lives in the adapter; permission enforced there) ------------------
    default boolean hidingAllowed() { return unsupported("blockhiding"); }
    default boolean hidingActive() { return unsupported("blockhiding"); }
    default boolean hidingSetActive(boolean active) { return unsupported("blockhiding"); }
    /** cfg: mode ("xray"|"hide"), blocks (list), range (blocks, 0 = all), night_vision, while_moving */
    default void hidingConfigure(java.util.Map<String, Object> cfg) { unsupported("blockhiding"); }
    /** Server: allow/deny block hiding for one player (sent to their client). */
    default void hidingAllow(String player, boolean allowed) { unsupported("blockhiding.allow"); }

    default boolean gamerule(String rule, String value) { return unsupported("gamerule"); }

    default boolean unsupported(String what) {
        throw new UnsupportedOperationException(what + " is not available on the " + side().name().toLowerCase() + " side");
    }

    record PlayerInfo(String name, String uuid, String dimension, double x, double y, double z,
                      float yaw, float pitch, float health, float maxHealth, int food,
                      String gamemode, String heldItem, boolean op) {}
}
