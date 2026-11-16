package dev.mcaddons.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @TempDir Path root;
    final List<String> chat = new ArrayList<>();
    final List<String> logs = new ArrayList<>();
    PluginManager mgr;

    final Host host = new Host() {
        public String minecraftVersion() { return "test"; }
        public Side side() { return Side.SERVER; }
        public void broadcast(String m) { chat.add(m); }
        public List<PlayerInfo> players() {
            return List.of(new PlayerInfo("Steve", "u", "minecraft:overworld", 1, 64, 2, 0, 0, 20, 20, 20, "survival", "minecraft:air", false));
        }
    };
    final Log log = new Log() {
        public void info(String m) { logs.add(m); }
        public void warn(String m) { logs.add(m); }
        public void error(String m, Throwable t) { logs.add("ERR " + m); }
    };

    @BeforeEach void setup() { mgr = new PluginManager(host, log, root, 200_000, 500); }

    void plugin(String id, String lua, String... perms) throws Exception {
        Path d = root.resolve("plugins").resolve(id);
        Files.createDirectories(d);
        Manifest m = new Manifest();
        m.id = id; m.permissions = new ArrayList<>(List.of(perms)); m.side = "server";
        Files.writeString(d.resolve("plugin.json"), m.toJson());
        Files.writeString(d.resolve("main.lua"), lua);
    }

    @Test void eventsAndChat() throws Exception {
        plugin("greeter", "mc.on('player_join', function(e) mc.broadcast('hi ' .. e.player) end)", "chat");
        mgr.loadAll();
        mgr.emit("player_join", Map.of("player", "Steve"));
        assertEquals(List.of("hi Steve"), chat);
    }

    @Test void cancellation() throws Exception {
        plugin("guard", "mc.on('block_break', function(e) if e.block == 'minecraft:diamond_ore' then return false end end)");
        mgr.loadAll();
        assertTrue(mgr.emit("block_break", Map.of("block", "minecraft:diamond_ore")));
        assertFalse(mgr.emit("block_break", Map.of("block", "minecraft:stone")));
    }

    @Test void permissionsAreEnforced() throws Exception {
        plugin("sneaky", "mc.on('tick', function() mc.broadcast('x') end)");
        mgr.loadAll();
        mgr.tick();
        assertTrue(chat.isEmpty());
        assertTrue(logs.stream().anyMatch(l -> l.contains("permission 'chat' not declared")));
    }

    @Test void sandboxHasNoEscapes() throws Exception {
        plugin("evil", """
                assert(io == nil and debug == nil and load == nil and loadstring == nil and dofile == nil)
                assert(os.execute == nil and os.getenv == nil and string.dump == nil and package == nil)
                mc.command('ok', function() return 'sandbox ok' end)
                """, "commands");
        mgr.loadAll();
        List<String> replies = new ArrayList<>();
        assertTrue(mgr.runCommand("ok", sender(replies), ""));
        assertEquals(List.of("sandbox ok"), replies);
    }

    @Test void infiniteLoopIsStopped() throws Exception {
        plugin("loop", "mc.command('spin', function() while true do end end)", "commands");
        mgr.loadAll();
        long t = System.currentTimeMillis();
        mgr.runCommand("spin", sender(new ArrayList<>()), "");
        assertTrue(System.currentTimeMillis() - t < 3000);
        assertTrue(logs.stream().anyMatch(l -> l.contains("instruction budget") || l.contains("took longer")));
    }

    @Test void schedulerAndStorageAndReload() throws Exception {
        plugin("counter", """
                local n = mc.storage.get('n') or 0
                mc.every(2, function() n = n + 1; mc.storage.set('n', n) end)
                mc.command('n', function() return tostring(n) end)
                """, "commands", "storage");
        mgr.loadAll();
        for (int i = 0; i < 6; i++) mgr.tick();
        List<String> r = new ArrayList<>();
        mgr.runCommand("n", sender(r), "");
        assertEquals("3", r.get(0));
        mgr.reload("counter");
        r.clear();
        mgr.runCommand("n", sender(r), "");
        assertEquals("3", r.get(0), "storage survives hot reload");
        mgr.disable("counter");
        assertFalse(mgr.runCommand("n", sender(r), ""), "commands vanish when disabled");
    }

    @Test void requireStaysInsidePlugin() throws Exception {
        plugin("mods", "local u = require('util'); mc.command('u', function() return u.x end); assert(not pcall(require, '../evil'))", "commands");
        Files.writeString(root.resolve("plugins/mods/util.lua"), "return { x = 'from module' }");
        mgr.loadAll();
        List<String> r = new ArrayList<>();
        mgr.runCommand("u", sender(r), "");
        assertEquals("from module", r.get(0));
    }

    @Test void badManifestsAreRejected() {
        Manifest m = new Manifest();
        m.id = "Bad-Id";
        assertThrows(PluginException.class, m::validate);
        m.id = "ok"; m.api = "2.0";
        assertThrows(PluginException.class, m::validate);
        m.api = "1.0"; m.permissions = List.of("root");
        assertThrows(PluginException.class, m::validate);
        assertFalse(Paths.safeRelative("../x.lua"));
        assertFalse(Paths.safeRelative("/etc/passwd"));
        assertTrue(Paths.safeRelative("lib/util.lua"));
    }

    @Test void registryBlocksForeignHosts() {
        Registry reg = new Registry("https://evil.example/index.json");
        assertThrows(Exception.class, () -> reg.get("https://evil.example/x"));
        assertThrows(Exception.class, () -> reg.get("http://raw.githubusercontent.com/x"));
    }

    static PluginManager.Sender sender(List<String> replies) {
        return new PluginManager.Sender() {
            public String name() { return "tester"; }
            public boolean isPlayer() { return false; }
            public void reply(String m) { replies.add(m); }
        };
    }
}
