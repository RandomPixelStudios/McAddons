package dev.mcaddons.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Loads, hot-reloads and drives plugins for one logical side. In singleplayer there are two
 * managers in the same JVM (client + integrated server), each with fully separate Lua VMs.
 * All methods must be called on that side's main thread.
 */
public final class PluginManager {
    public static final int KEY_SLOTS = 16;

    final Host host;
    final Log log;
    final Path pluginsDir;
    final Path dataDir;
    private final Path stateFile;
    final long instructionLimit;
    final long timeoutMs;

    private final Map<String, Plugin> running = new LinkedHashMap<>();
    private final Map<String, String> errors = new HashMap<>();
    private final Map<String, Plugin> commandOwners = new HashMap<>();
    private final Plugin[] keyOwners = new Plugin[KEY_SLOTS + 1];
    private final Set<String> disabled = new TreeSet<>();
    private final Set<String> pendingDisable = new HashSet<>();
    private final Map<String, Long> fingerprints = new HashMap<>();
    private long ticks;
    private boolean watch = true;

    public PluginManager(Host host, Log log, Path root) {
        this(host, log, root, 2_000_000L, 1000L);
    }

    public PluginManager(Host host, Log log, Path root, long instructionLimit, long timeoutMs) {
        this.host = host;
        this.log = log;
        this.pluginsDir = root.resolve("plugins");
        this.dataDir = root.resolve("data");
        this.stateFile = root.resolve("disabled.json");
        this.instructionLimit = instructionLimit;
        this.timeoutMs = timeoutMs;
        try { Files.createDirectories(pluginsDir); } catch (IOException e) { throw new UncheckedIOException(e); }
        loadState();
    }

    public Side side() { return host.side(); }
    public Path pluginsDir() { return pluginsDir; }
    public long now() { return ticks; }
    public void setWatch(boolean watch) { this.watch = watch; }

    // ---------------------------------------------------------------- discovery ---------------

    public record Info(String id, Manifest manifest, boolean enabled, boolean running, String error) {
        /** True if the plugin is meant for this side (otherwise it runs on the other side only). */
        public boolean forSide(Side s) { return manifest == null || manifest.sideEnum().runsOn(s); }
    }

    /** Every plugin folder on disk, with its status for this side. */
    public List<Info> list() {
        List<Info> out = new ArrayList<>();
        for (Path d : pluginDirs()) {
            String id = d.getFileName().toString();
            Manifest m = null;
            String err = errors.get(id);
            try { m = Manifest.read(d.resolve("plugin.json")); } catch (PluginException e) { err = e.getMessage(); }
            out.add(new Info(id, m, !disabled.contains(id), running.containsKey(id), err));
        }
        return out;
    }

    private List<Path> pluginDirs() {
        try (Stream<Path> s = Files.list(pluginsDir)) {
            return s.filter(p -> Files.isRegularFile(p.resolve("plugin.json")))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public Optional<Plugin> get(String id) { return Optional.ofNullable(running.get(id)); }
    public Set<String> runningIds() { return running.keySet(); }

    /** Starts every enabled plugin for this side. */
    public void loadAll() {
        for (Path d : pluginDirs()) {
            String id = d.getFileName().toString();
            if (!disabled.contains(id) && !running.containsKey(id)) start(id);
        }
    }

    public String start(String id) {
        if (running.containsKey(id)) return id + " is already running";
        Path d = pluginsDir.resolve(id);
        errors.remove(id);
        try {
            Manifest m = Manifest.read(d.resolve("plugin.json"));
            if (!m.id.equals(id)) throw new PluginException("folder name '" + id + "' must equal plugin id '" + m.id + "'");
            if (!m.sideEnum().runsOn(side())) return id + " does not run on the " + side().name().toLowerCase() + " side";
            Plugin p = new Plugin(this, m, d);
            running.put(id, p);
            try {
                p.start();
            } catch (PluginException e) {
                running.remove(id);
                release(p);
                throw e;
            }
            fingerprints.put(id, fingerprint(d));
            log.info("Enabled " + m.name + " " + m.version + " (" + side().name().toLowerCase() + ")");
            return "Enabled " + m.name;
        } catch (PluginException e) {
            errors.put(id, e.getMessage());
            log.error("Could not start plugin " + id + ": " + e.getMessage(), null);
            return "Failed: " + e.getMessage();
        }
    }

    public String stop(String id) {
        Plugin p = running.remove(id);
        if (p == null) return id + " is not running";
        try { p.stop(); } catch (Throwable t) { log.error("error while stopping " + id, t); }
        release(p);
        return "Stopped " + id;
    }

    private void release(Plugin p) {
        commandOwners.values().removeIf(o -> o == p);
        try { host.removeMenuButtons(p.id()); } catch (RuntimeException ignored) {}
        for (int i = 1; i <= KEY_SLOTS; i++) if (keyOwners[i] == p) { keyOwners[i] = null; host.bindKey(i, null, null); }
        host.commandsChanged();
    }

    public String enable(String id) {
        disabled.remove(id);
        saveState();
        return start(id);
    }

    public String disable(String id) {
        disabled.add(id);
        saveState();
        return stop(id);
    }

    public String reload(String id) {
        stop(id);
        return start(id).replace("Enabled", "Reloaded");
    }

    public void reloadAll() {
        for (String id : List.copyOf(running.keySet())) stop(id);
        loadAll();
    }

    /** Stops the plugin and deletes its folder. */
    public String remove(String id) throws IOException {
        if (!Manifest.ID.matcher(id).matches()) return "invalid id";
        stop(id);
        Path d = pluginsDir.resolve(id);
        if (Files.exists(d)) {
            try (Stream<Path> s = Files.walk(d)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        disabled.remove(id);
        saveState();
        return "Removed " + id;
    }

    public void shutdown() {
        for (String id : List.copyOf(running.keySet())) stop(id);
    }

    void scheduleDisable(String id, String reason) {
        if (pendingDisable.add(id)) log.warn("Disabling " + id + ": " + reason);
    }

    // ---------------------------------------------------------------- runtime -----------------

    public void tick() {
        ticks++;
        for (Plugin p : List.copyOf(running.values())) {
            p.tick(ticks);
            p.dispatch("tick", null);
        }
        if (!pendingDisable.isEmpty()) {
            for (String id : List.copyOf(pendingDisable)) { stop(id); errors.put(id, "disabled after repeated errors"); }
            pendingDisable.clear();
        }
        if (watch && ticks % 40 == 0) hotReloadChanged();
    }

    /** Dev convenience: reload a running plugin whenever one of its files changes on disk. */
    private void hotReloadChanged() {
        for (String id : List.copyOf(running.keySet())) {
            Long old = fingerprints.get(id);
            long now = fingerprint(pluginsDir.resolve(id));
            if (old != null && old != now) {
                log.info("Files of " + id + " changed - hot reloading");
                reload(id);
            }
        }
    }

    private static long fingerprint(Path dir) {
        try (Stream<Path> s = Files.walk(dir, 4)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.getLastModifiedTime(p).toMillis() * 31 + Files.size(p) + p.toString().hashCode(); }
                catch (IOException e) { return 0; }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Fires an event at all plugins. Returns true if a handler cancelled it. */
    public boolean emit(String event, Map<String, Object> data) {
        boolean cancel = false;
        for (Plugin p : List.copyOf(running.values())) cancel |= p.dispatch(event, data);
        return cancel && Events.CANCELLABLE.contains(event);
    }

    public List<HudOp> hud(int w, int h) {
        List<HudOp> ops = new ArrayList<>();
        for (Plugin p : running.values()) ops.addAll(p.hud(w, h));
        return ops;
    }

    public List<HudOp> render() {
        List<HudOp> ops = new ArrayList<>();
        for (Plugin p : running.values()) ops.addAll(p.render());
        return ops;
    }

    /** Invokes a callback a plugin handed to the host (GUI button, menu click). */
    public void callback(String pluginId, int id, Map<String, Object> data) {
        Plugin p = running.get(pluginId);
        if (p == null) return;
        var fn = p.callbacks.get(id);
        if (fn != null) p.call("callback", fn, Lua.args(data));
    }

    /** Delivers a network message to one plugin as a "message" event. */
    public void deliver(String pluginId, String channel, String json, String player) {
        Plugin p = running.get(pluginId);
        if (p == null) return;
        Object data;
        try { data = new Gson().fromJson(json, Object.class); } catch (Exception e) { data = null; }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("channel", channel);
        ev.put("data", normalize(data));
        if (player != null) ev.put("player", player);
        p.dispatch("message", ev);
    }

    /** Gson parses numbers as Double; give Lua integers where possible. */
    private static Object normalize(Object o) {
        if (o instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) return d.longValue();
        if (o instanceof Map<?, ?> m) { Map<String, Object> r = new LinkedHashMap<>(); m.forEach((k, v) -> r.put(String.valueOf(k), normalize(v))); return r; }
        if (o instanceof List<?> l) return l.stream().map(PluginManager::normalize).toList();
        return o;
    }

    // ---------------------------------------------------------------- commands & keys ---------

    void claimCommand(String name, Plugin p) throws org.squiddev.cobalt.LuaError {
        Plugin owner = commandOwners.get(name);
        if (owner != null && owner != p) throw new org.squiddev.cobalt.LuaError("command /" + name + " is already owned by " + owner.id());
        commandOwners.put(name, p);
    }

    public Set<String> commandNames() { return new TreeSet<>(commandOwners.keySet()); }

    public interface Sender {
        String name();
        boolean isPlayer();
        void reply(String message);
    }

    /** Executes a plugin command. Returns false if no plugin owns the name. */
    public boolean runCommand(String name, Sender sender, String rawArgs) {
        Plugin p = commandOwners.get(name);
        if (p == null) return false;
        var fn = p.commands.get(name);
        if (fn == null) return false;
        List<String> args = rawArgs == null || rawArgs.isBlank() ? List.of() : List.of(rawArgs.trim().split("\\s+"));
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("sender", sender.name());
        ctx.put("is_player", sender.isPlayer());
        ctx.put("args", args);
        ctx.put("raw", rawArgs == null ? "" : rawArgs);
        var r = p.call("command /" + name, fn, Lua.args(ctx));
        if (r != null && !r.first().isNil())
            sender.reply(r.first() instanceof org.squiddev.cobalt.LuaString ls ? Lua.str(ls) : r.first().toString());
        return true;
    }

    int claimKeySlot(Plugin p) {
        for (int i = 1; i <= KEY_SLOTS; i++) if (keyOwners[i] == null) { keyOwners[i] = p; return i; }
        return -1;
    }

    public void pressKey(int slot) {
        if (slot < 1 || slot > KEY_SLOTS || keyOwners[slot] == null) return;
        Plugin p = keyOwners[slot];
        var fn = p.keys.get(slot);
        if (fn != null) p.call("key", fn, org.squiddev.cobalt.Constants.NONE);
    }

    // ---------------------------------------------------------------- scaffolding -------------

    /** Creates a ready-to-edit example plugin. */
    public String scaffold(String id) throws IOException {
        if (!Manifest.ID.matcher(id).matches()) return "id must be lowercase letters, digits or _";
        Path d = pluginsDir.resolve(id);
        if (Files.exists(d)) return id + " already exists";
        Files.createDirectories(d);
        Manifest m = new Manifest();
        m.id = id; m.name = id; m.description = "My new plugin";
        m.permissions = new ArrayList<>(List.of("chat", "commands"));
        Files.writeString(d.resolve("plugin.json"), m.toJson());
        Files.writeString(d.resolve("main.lua"), """
                -- %s: edit this file, it is hot-reloaded automatically.
                mc.on("enable", function() mc.log("hello from %s") end)

                mc.command("%s", function(ctx)
                  return "Hi " .. ctx.sender .. "! args: " .. table.concat(ctx.args, ", ")
                end)
                """.formatted(id, id, id));
        disabled.remove(id);
        saveState();
        return "Created " + d + " - " + start(id);
    }

    // ---------------------------------------------------------------- state -------------------

    private void loadState() {
        try {
            if (Files.isRegularFile(stateFile)) {
                List<String> l = new Gson().fromJson(Files.readString(stateFile), new TypeToken<List<String>>() {}.getType());
                if (l != null) disabled.addAll(l);
            }
        } catch (Exception e) {
            log.warn("could not read " + stateFile + ": " + e.getMessage());
        }
    }

    private void saveState() {
        try {
            Files.writeString(stateFile, new Gson().toJson(disabled));
        } catch (IOException e) {
            log.error("could not save plugin state", e);
        }
    }

    /** Reads manifests of all enabled plugins without starting them (used at boot for items/blocks). */
    public static List<Manifest> enabledManifests(Path root, Log log) {
        List<Manifest> out = new ArrayList<>();
        Set<String> off = new HashSet<>();
        try {
            Path s = root.resolve("disabled.json");
            if (Files.isRegularFile(s)) {
                List<String> l = new Gson().fromJson(Files.readString(s), new TypeToken<List<String>>() {}.getType());
                if (l != null) off.addAll(l);
            }
        } catch (Exception ignored) {}
        try (Stream<Path> st = Files.list(root.resolve("plugins"))) {
            for (Path d : st.sorted().toList()) {
                Path f = d.resolve("plugin.json");
                if (!Files.isRegularFile(f) || off.contains(d.getFileName().toString())) continue;
                try { out.add(Manifest.read(f)); } catch (PluginException e) { log.warn(d.getFileName() + ": " + e.getMessage()); }
            }
        } catch (IOException ignored) {}
        return out;
    }
}
