package dev.mcaddons.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.function.LibFunction;
import org.squiddev.cobalt.function.LuaFunction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A loaded, running plugin: its sandbox, the {@code mc} API table and everything it registered.
 * All registrations live here, so disabling a plugin (hot reload) simply drops this object.
 */
public final class Plugin {
    public final Manifest manifest;
    public final Path dir;
    private final PluginManager manager;
    private final Host host;
    private final Log log;
    private final Set<Permission> perms;
    private Sandbox sandbox;

    final Map<String, List<LuaValue>> handlers = new HashMap<>();
    final Map<String, LuaValue> commands = new LinkedHashMap<>();
    final Map<Integer, LuaValue> keys = new HashMap<>();
    final List<Task> tasks = new ArrayList<>();
    private final Map<String, LuaValue> modules = new HashMap<>();
    private Map<String, Object> storage;
    private boolean storageDirty;
    private int errorsInARow;
    private List<HudOp> hudSink;
    private boolean closed;

    static final class Task {
        final LuaValue fn; final int interval; long due; boolean cancelled;
        Task(LuaValue fn, int interval, long due) { this.fn = fn; this.interval = interval; this.due = due; }
    }

    Plugin(PluginManager manager, Manifest manifest, Path dir) {
        this.manager = manager;
        this.manifest = manifest;
        this.dir = dir;
        this.host = manager.host;
        this.perms = manifest.permissionSet();
        String prefix = "[" + manifest.id + "] ";
        Log base = manager.log;
        this.log = new Log() {
            public void info(String m) { base.info(prefix + m); }
            public void warn(String m) { base.warn(prefix + m); }
            public void error(String m, Throwable t) { base.error(prefix + m, t); }
        };
    }

    public String id() { return manifest.id; }

    void start() throws PluginException {
        sandbox = new Sandbox(log, manager.instructionLimit, manager.timeoutMs);
        LuaTable g = sandbox.globals();
        g.rawset("mc", buildApi());
        g.rawset("require", fn(a -> requireModule(a.arg(1).checkString())));
        try {
            String src = Files.readString(Paths.resolveInside(dir, manifest.entry));
            LuaFunction main = sandbox.compile(src, manifest.id + "/" + manifest.entry);
            sandbox.call(main, Constants.NONE);
            fire("enable");
        } catch (IOException e) {
            throw new PluginException("cannot read " + manifest.entry + ": " + e.getMessage(), e);
        } catch (LuaError e) {
            throw new PluginException(e.getMessage(), e);
        }
    }

    void stop() {
        if (closed) return;
        fire("disable");
        flushStorage();
        closed = true;
        handlers.clear(); commands.clear(); keys.clear(); tasks.clear();
    }

    boolean closed() { return closed; }

    // ---------------------------------------------------------------- dispatch -----------------

    /** Calls handlers for an event. Returns true if any handler returned false (= cancel). */
    boolean dispatch(String event, Object data) {
        List<LuaValue> list = handlers.get(event);
        if (list == null || list.isEmpty() || closed) return false;
        boolean cancel = false;
        for (LuaValue h : List.copyOf(list)) {
            Varargs r = call("event " + event, h, data == null ? Constants.NONE : Lua.args(data));
            if (r != null && r.first() == Constants.FALSE) cancel = true;
        }
        return cancel;
    }

    private void fire(String event) { dispatch(event, null); }

    Varargs call(String what, LuaValue fn, Varargs args) {
        if (closed || sandbox == null) return null;
        if (sandbox.running()) return null; // never re-enter a running VM (e.g. command -> event loop)
        try {
            Varargs r = sandbox.call(fn, args);
            errorsInARow = 0;
            return r;
        } catch (Throwable e) {
            log.error(what + " failed: " + e.getMessage(), null);
            if (++errorsInARow >= 5) manager.scheduleDisable(id(), "too many errors in a row");
            return null;
        } finally {
            if (storageDirty) flushStorage();
        }
    }

    List<HudOp> hud(int w, int h) {
        List<HudOp> ops = new ArrayList<>();
        if (!handlers.containsKey("hud")) return ops;
        hudSink = ops;
        try { dispatch("hud", Map.of("width", w, "height", h)); } finally { hudSink = null; }
        return ops;
    }

    void tick(long now) {
        for (Task t : List.copyOf(tasks)) {
            if (t.cancelled) { tasks.remove(t); continue; }
            if (now < t.due) continue;
            call("scheduled task", t.fn, Constants.NONE);
            if (t.interval > 0) t.due = now + t.interval; else tasks.remove(t);
        }
    }

    // ---------------------------------------------------------------- API ----------------------

    @FunctionalInterface interface Body { Varargs run(Varargs a) throws LuaError; }

    private static LuaValue fn(Body body) {
        return LibFunction.createV((LuaState s, Varargs a) -> body.run(a));
    }

    private void need(Permission p) throws LuaError {
        if (!perms.contains(p)) throw new LuaError("permission '" + p.id + "' not declared in plugin.json");
    }

    private Varargs hostCall(java.util.function.Supplier<Object> action) throws LuaError {
        try {
            return Lua.of(action.get());
        } catch (UnsupportedOperationException e) {
            throw new LuaError(e.getMessage());
        } catch (RuntimeException e) {
            throw new LuaError("host error: " + e.getMessage());
        }
    }

    private LuaTable buildApi() {
        LuaTable mc = new LuaTable();
        mc.rawset("api_version", ValueFactory.valueOf(ApiVersion.STRING));
        mc.rawset("minecraft_version", ValueFactory.valueOf(host.minecraftVersion()));
        mc.rawset("side", ValueFactory.valueOf(host.side().name().toLowerCase()));
        mc.rawset("plugin", Lua.of(Map.of("id", manifest.id, "name", manifest.name, "version", manifest.version)));

        mc.rawset("log", fn(a -> { log.info(join(a)); return Constants.NONE; }));
        mc.rawset("warn", fn(a -> { log.warn(join(a)); return Constants.NONE; }));
        mc.rawset("has_permission", fn(a -> ValueFactory.valueOf(Permission.byId(a.arg(1).checkString()).map(perms::contains).orElse(false))));

        // events
        mc.rawset("on", fn(a -> {
            String ev = a.arg(1).checkString();
            LuaValue f = a.arg(2).checkFunction();
            if (!Events.ALL.contains(ev)) throw new LuaError("unknown event '" + ev + "'. Known: " + Events.ALL);
            if (ev.equals("chat")) need(Permission.CHAT);
            if (ev.equals("hud")) need(Permission.HUD);
            handlers.computeIfAbsent(ev, k -> new ArrayList<>()).add(f);
            return Constants.NONE;
        }));

        // scheduler (ticks: 20 per second)
        mc.rawset("after", fn(a -> schedule(a.arg(1).checkInteger(), a.arg(2).checkFunction(), false)));
        mc.rawset("every", fn(a -> schedule(a.arg(1).checkInteger(), a.arg(2).checkFunction(), true)));

        // commands
        mc.rawset("command", fn(a -> {
            need(Permission.COMMANDS);
            String name = a.arg(1).checkString().toLowerCase();
            if (!name.matches("[a-z][a-z0-9_]{0,31}")) throw new LuaError("invalid command name: " + name);
            LuaValue f = a.arg(2).checkFunction();
            manager.claimCommand(name, this);
            commands.put(name, f);
            host.commandsChanged();
            return Constants.NONE;
        }));
        mc.rawset("run", fn(a -> { need(Permission.COMMANDS_RUN); String c = a.arg(1).checkString(); return hostCall(() -> host.runCommand(c)); }));

        // chat
        mc.rawset("tell", fn(a -> { need(Permission.CHAT); String p = a.arg(1).checkString(), m = a.arg(2).checkString(); return hostCall(() -> { host.tell(p, m); return null; }); }));
        mc.rawset("broadcast", fn(a -> { need(Permission.CHAT); String m = a.arg(1).checkString(); return hostCall(() -> { host.broadcast(m); return null; }); }));
        mc.rawset("actionbar", fn(a -> { need(Permission.CHAT); String p = a.arg(1).checkString(), m = a.arg(2).checkString(); return hostCall(() -> { host.actionbar(p, m); return null; }); }));
        mc.rawset("toast", fn(a -> { String t = a.arg(1).checkString(), m = a.arg(2).optString(""); return hostCall(() -> { host.toast(t, m); return null; }); }));

        // players
        mc.rawset("players", fn(a -> { need(Permission.PLAYERS); return hostCall(host::players); }));
        mc.rawset("player", fn(a -> { need(Permission.PLAYERS); String n = a.arg(1).checkString(); return hostCall(() -> host.player(n).orElse(null)); }));
        mc.rawset("teleport", fn(a -> { need(Permission.PLAYERS_MODIFY); String p = a.arg(1).checkString(); double x = a.arg(2).checkDouble(), y = a.arg(3).checkDouble(), z = a.arg(4).checkDouble(); return hostCall(() -> host.teleport(p, x, y, z)); }));
        mc.rawset("give", fn(a -> { need(Permission.PLAYERS_MODIFY); String p = a.arg(1).checkString(), i = a.arg(2).checkString(); int c = a.arg(3).optInteger(1); return hostCall(() -> host.give(p, i, Math.max(1, Math.min(c, 6400)))); }));
        mc.rawset("set_health", fn(a -> { need(Permission.PLAYERS_MODIFY); String p = a.arg(1).checkString(); float h = (float) a.arg(2).checkDouble(); return hostCall(() -> host.setHealth(p, h)); }));
        mc.rawset("effect", fn(a -> { need(Permission.PLAYERS_MODIFY); String p = a.arg(1).checkString(), e = a.arg(2).checkString(); int s = a.arg(3).optInteger(10), amp = a.arg(4).optInteger(0); return hostCall(() -> host.effect(p, e, s, amp)); }));

        // world
        LuaTable world = new LuaTable();
        world.rawset("get_block", fn(a -> { need(Permission.WORLD_READ); String d = a.arg(1).checkString(); int x = a.arg(2).checkInteger(), y = a.arg(3).checkInteger(), z = a.arg(4).checkInteger(); return hostCall(() -> host.block(d, x, y, z)); }));
        world.rawset("set_block", fn(a -> { need(Permission.WORLD_WRITE); String d = a.arg(1).checkString(); int x = a.arg(2).checkInteger(), y = a.arg(3).checkInteger(), z = a.arg(4).checkInteger(); String b = a.arg(5).checkString(); return hostCall(() -> host.setBlock(d, x, y, z, b)); }));
        world.rawset("spawn", fn(a -> { need(Permission.WORLD_WRITE); String d = a.arg(1).checkString(), e = a.arg(2).checkString(); double x = a.arg(3).checkDouble(), y = a.arg(4).checkDouble(), z = a.arg(5).checkDouble(); return hostCall(() -> host.spawn(d, e, x, y, z)); }));
        world.rawset("explode", fn(a -> { need(Permission.WORLD_WRITE); String d = a.arg(1).checkString(); double x = a.arg(2).checkDouble(), y = a.arg(3).checkDouble(), z = a.arg(4).checkDouble(); float p = (float) Math.min(a.arg(5).optDouble(2), 10); return hostCall(() -> host.explode(d, x, y, z, p)); }));
        world.rawset("time", fn(a -> { need(Permission.WORLD_READ); String d = a.arg(1).optString("minecraft:overworld"); return hostCall(() -> host.time(d)); }));
        world.rawset("set_time", fn(a -> { need(Permission.WORLD_WRITE); String d = a.arg(1).checkString(); long t = a.arg(2).checkLong(); return hostCall(() -> { host.setTime(d, t); return null; }); }));
        world.rawset("particle", fn(a -> { need(Permission.EFFECTS); String d = a.arg(1).checkString(), p = a.arg(2).checkString(); double x = a.arg(3).checkDouble(), y = a.arg(4).checkDouble(), z = a.arg(5).checkDouble(); int c = Math.min(a.arg(6).optInteger(1), 500); return hostCall(() -> host.particle(d, p, x, y, z, c)); }));
        world.rawset("sound", fn(a -> { need(Permission.EFFECTS); String d = a.arg(1).checkString(), s = a.arg(2).checkString(); double x = a.arg(3).checkDouble(), y = a.arg(4).checkDouble(), z = a.arg(5).checkDouble(); float v = (float) a.arg(6).optDouble(1), p = (float) a.arg(7).optDouble(1); return hostCall(() -> host.sound(d, s, x, y, z, v, p)); }));
        mc.rawset("world", world);

        // HUD (only valid inside an "hud" handler)
        LuaTable hud = new LuaTable();
        hud.rawset("text", fn(a -> { hudOp(new HudOp.Text(a.arg(1).checkInteger(), a.arg(2).checkInteger(), clip(a.arg(3).checkString()), (int) a.arg(4).optLong(0xFFFFFFFFL), a.arg(5).optBoolean(true))); return Constants.NONE; }));
        hud.rawset("rect", fn(a -> { hudOp(new HudOp.Rect(a.arg(1).checkInteger(), a.arg(2).checkInteger(), a.arg(3).checkInteger(), a.arg(4).checkInteger(), (int) a.arg(5).optLong(0x80000000L))); return Constants.NONE; }));
        hud.rawset("item", fn(a -> { hudOp(new HudOp.Item(a.arg(1).checkInteger(), a.arg(2).checkInteger(), a.arg(3).checkString())); return Constants.NONE; }));
        hud.rawset("width", fn(a -> ValueFactory.valueOf(host.screenWidth())));
        hud.rawset("height", fn(a -> ValueFactory.valueOf(host.screenHeight())));
        mc.rawset("hud", hud);

        // keys (client)
        mc.rawset("key", fn(a -> {
            need(Permission.KEYS);
            String label = a.arg(1).checkString(), def = a.arg(2).optString("");
            LuaValue f = a.arg(3).checkFunction();
            int slot = manager.claimKeySlot(this);
            if (slot < 0) throw new LuaError("all " + PluginManager.KEY_SLOTS + " plugin key slots are in use");
            keys.put(slot, f);
            host.bindKey(slot, manifest.name + ": " + label, def);
            return ValueFactory.valueOf(slot);
        }));

        // storage
        LuaTable store = new LuaTable();
        store.rawset("get", fn(a -> { need(Permission.STORAGE); return Lua.of(storage().get(a.arg(1).checkString())); }));
        store.rawset("set", fn(a -> {
            need(Permission.STORAGE);
            storage().put(a.arg(1).checkString(), Lua.toJava(a.arg(2), 0));
            storageDirty = true;
            return Constants.NONE;
        }));
        store.rawset("keys", fn(a -> { need(Permission.STORAGE); return Lua.of(new ArrayList<>(storage().keySet())); }));
        mc.rawset("storage", store);

        // content registered from plugin.json (items/blocks get ids "<plugin>:<id>")
        List<String> content = new ArrayList<>();
        manifest.content.items.forEach(i -> content.add(manifest.id + ":" + i.id));
        manifest.content.blocks.forEach(b -> content.add(manifest.id + ":" + b.id));
        mc.rawset("content", Lua.of(content));
        return mc;
    }

    private Varargs schedule(int ticks, LuaValue f, boolean repeat) throws LuaError {
        if (ticks < (repeat ? 1 : 0) || ticks > 20 * 60 * 60 * 24) throw new LuaError("ticks out of range");
        if (tasks.size() >= 256) throw new LuaError("too many scheduled tasks");
        Task t = new Task(f, repeat ? ticks : 0, manager.now() + ticks);
        tasks.add(t);
        LuaTable handle = new LuaTable();
        handle.rawset("cancel", fn(a -> { t.cancelled = true; return Constants.NONE; }));
        return handle;
    }

    private void hudOp(HudOp op) throws LuaError {
        need(Permission.HUD);
        if (hudSink == null) throw new LuaError("mc.hud.* can only be used inside an on('hud') handler");
        if (hudSink.size() < 512) hudSink.add(op);
    }

    private static String clip(String s) { return s.length() > 256 ? s.substring(0, 256) : s; }

    private static String join(Varargs a) {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= a.count(); i++) { if (i > 1) b.append(' '); b.append(a.arg(i).toString()); }
        return b.toString();
    }

    private LuaValue requireModule(String name) throws LuaError {
        String rel = name.replace('.', '/') + ".lua";
        LuaValue cached = modules.get(rel);
        if (cached != null) return cached;
        try {
            Path f = Paths.resolveInside(dir, rel);
            if (!Files.isRegularFile(f)) throw new LuaError("module not found: " + name);
            modules.put(rel, Constants.TRUE);
            LuaFunction chunk = sandbox.compile(Files.readString(f), manifest.id + "/" + rel);
            LuaValue v = org.squiddev.cobalt.function.Dispatch.invoke(sandbox.state(), chunk, Constants.NONE).first();
            if (v.isNil()) v = Constants.TRUE;
            modules.put(rel, v);
            return v;
        } catch (LuaError e) {
            modules.remove(rel);
            throw e;
        } catch (Exception | org.squiddev.cobalt.UnwindThrowable e) {
            modules.remove(rel);
            throw new LuaError("cannot load module " + name + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- storage ------------------

    private Path storageFile() { return manager.dataDir.resolve(manifest.id + ".json"); }

    private Map<String, Object> storage() {
        if (storage == null) {
            storage = new LinkedHashMap<>();
            Path f = storageFile();
            if (Files.isRegularFile(f)) {
                try {
                    Map<String, Object> m = new Gson().fromJson(Files.readString(f), new TypeToken<Map<String, Object>>() {}.getType());
                    if (m != null) storage.putAll(m);
                } catch (Exception e) {
                    log.warn("storage file corrupt, starting empty: " + e.getMessage());
                }
            }
        }
        return storage;
    }

    private void flushStorage() {
        if (!storageDirty || storage == null) return;
        storageDirty = false;
        try {
            String json = new Gson().toJson(storage);
            if (json.length() > 1 << 20) { log.warn("storage exceeds 1 MiB, not saved"); return; }
            Files.createDirectories(manager.dataDir);
            Files.writeString(storageFile(), json);
        } catch (IOException e) {
            log.error("could not save storage", e);
        }
    }
}
