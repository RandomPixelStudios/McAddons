package dev.mcaddons.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.mcaddons.core.Permission;
import dev.mcaddons.core.PluginManager;
import dev.mcaddons.core.Registry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code /mca ...} admin command plus the commands plugins create at runtime. Brigadier cannot
 * remove nodes, so each plugin command name gets one permanent node that delegates to whichever
 * plugin currently owns the name (or reports that it is gone).
 */
public final class ServerCommands {
    private ServerCommands() {}

    private static final Set<String> published = new HashSet<>();
    private static final Set<String> RESERVED = Set.of("mca", "help", "op", "deop", "stop", "kick", "ban", "give", "tp", "gamemode");

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        published.clear();
        d.register(Commands.literal("mca")
                .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(c -> reply(c, "MC Addons - /mca list|enable|disable|reload|remove|new|browse|install <id>|blockhiding"))
                .then(Commands.literal("list").executes(ServerCommands::list))
                .then(op("enable", PluginManager::enable))
                .then(op("disable", PluginManager::disable))
                .then(op("reload", PluginManager::reload))
                .then(op("remove", (m, id) -> { try { return m.remove(id); } catch (Exception e) { return "Failed: " + e.getMessage(); } }))
                .then(Commands.literal("new").then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> { try { return reply(c, mgr().scaffold(StringArgumentType.getString(c, "id"))); }
                                         catch (Exception e) { return reply(c, "Failed: " + e.getMessage()); } })))
                .then(Commands.literal("browse").executes(ServerCommands::browse))
                .then(Commands.literal("blockhiding")
                        .executes(c -> reply(c, "Block hiding: " + (ServerConfig.get().allow_block_hiding ? "allowed" : "not allowed")
                                + (ServerConfig.get().block_hiding_ops_only ? " (ops only)" : "") + " - /mca blockhiding on|off|ops"))
                        .then(Commands.literal("on").executes(c -> blockHiding(c, true, false)))
                        .then(Commands.literal("ops").executes(c -> blockHiding(c, true, true)))
                        .then(Commands.literal("off").executes(c -> blockHiding(c, false, false))))
                .then(Commands.literal("install").then(Commands.argument("id", StringArgumentType.word())
                        .executes(ServerCommands::install))));
        // re-add plugin commands after /reload rebuilt the dispatcher
        PluginManager m = McAddons.server();
        if (m != null) for (String n : m.commandNames()) add(d, n);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> op(String name, java.util.function.BiFunction<PluginManager, String, String> action) {
        return Commands.literal(name).then(Commands.argument("plugin", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                        mgr() == null ? java.util.List.of() : mgr().list().stream().map(PluginManager.Info::id).toList(), b))
                .executes(c -> reply(c, action.apply(mgr(), StringArgumentType.getString(c, "plugin")))));
    }

    private static PluginManager mgr() { return McAddons.server(); }

    private static int reply(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int blockHiding(CommandContext<CommandSourceStack> c, boolean allow, boolean opsOnly) {
        ServerConfig cfg = ServerConfig.get();
        cfg.allow_block_hiding = allow;
        cfg.block_hiding_ops_only = opsOnly;
        ServerConfig.save();
        HidingAllowed.broadcast(c.getSource().getServer());
        return reply(c, "Block hiding is now " + (allow ? (opsOnly ? "allowed for operators" : "allowed for everyone") : "disabled") + " on this server.");
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        var infos = mgr().list();
        if (infos.isEmpty()) return reply(c, "No plugins installed. Try /mca browse or /mca new <id>");
        for (var i : infos) {
            String state = i.running() ? "§arunning" : i.enabled() ? "§cerror" : "§7disabled";
            reply(c, "§f" + i.id() + " " + (i.manifest() != null ? i.manifest().version : "?") + " " + state
                    + (i.error() != null ? " §7(" + i.error() + ")" : ""));
        }
        return infos.size();
    }

    private static int browse(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        MinecraftServer s = src.getServer();
        McAddons.REGISTRY.fetch().whenComplete((idx, err) -> s.execute(() -> {
            if (err != null) { src.sendFailure(Component.literal(err.getMessage())); return; }
            for (Registry.Entry e : idx.plugins)
                src.sendSuccess(() -> Component.literal("§e" + e.id + "§f " + e.version + " - " + e.description
                        + (e.compatible() ? "" : " §c(incompatible API " + e.api + ")")), false);
        }));
        return reply(c, "Loading plugin index...");
    }

    private static int install(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "id");
        CommandSourceStack src = c.getSource();
        MinecraftServer s = src.getServer();
        McAddons.REGISTRY.fetch().thenCompose(idx -> {
            var e = idx.plugins.stream().filter(x -> x.id.equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no plugin '" + id + "' in the registry"));
            s.execute(() -> src.sendSuccess(() -> Component.literal("Permissions: " + String.join(", ",
                    e.permissions.stream().map(p -> p + " (" + Permission.describe(p) + ")").toList())), false));
            return McAddons.REGISTRY.install(e, mgr().pluginsDir());
        }).whenComplete((msg, err) -> s.execute(() -> {
            if (err != null) { src.sendFailure(Component.literal("Install failed: " + rootMessage(err))); return; }
            src.sendSuccess(() -> Component.literal(msg + " - " + mgr().reload(id)), true);
        }));
        return reply(c, "Installing " + id + "...");
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage();
    }

    /** Adds nodes for newly created plugin commands and resends the command tree to players. */
    public static void publish(MinecraftServer s) {
        PluginManager m = McAddons.server();
        if (m == null || s == null) return;
        boolean changed = false;
        for (String n : m.commandNames()) changed |= add(s.getCommands().getDispatcher(), n);
        if (changed) for (var p : s.getPlayerList().getPlayers()) s.getCommands().sendCommands(p);
    }

    private static boolean add(CommandDispatcher<CommandSourceStack> d, String name) {
        if (RESERVED.contains(name) || !published.add(name)) return false;
        if (d.getRoot().getChild(name) != null) { Mc.LOG.warn("Plugin command /" + name + " clashes with an existing command"); return false; }
        Function<CommandContext<CommandSourceStack>, String> args = c -> {
            try { return StringArgumentType.getString(c, "args"); } catch (IllegalArgumentException e) { return ""; }
        };
        d.register(Commands.literal(name)
                .executes(c -> exec(c, name, ""))
                .then(Commands.argument("args", StringArgumentType.greedyString()).executes(c -> exec(c, name, args.apply(c)))));
        return true;
    }

    private static int exec(CommandContext<CommandSourceStack> c, String name, String args) {
        CommandSourceStack src = c.getSource();
        PluginManager m = McAddons.server();
        boolean ok = m != null && m.runCommand(name, new PluginManager.Sender() {
            public String name() { return src.getTextName(); }
            public boolean isPlayer() { return src.getPlayer() != null; }
            public void reply(String msg) { src.sendSuccess(() -> Component.literal(msg), false); }
        }, args);
        if (!ok) src.sendFailure(Component.literal("/" + name + " is not provided by any enabled plugin"));
        return ok ? 1 : 0;
    }
}
