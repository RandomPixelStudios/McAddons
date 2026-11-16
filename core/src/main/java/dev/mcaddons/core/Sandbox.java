package dev.mcaddons.core;

import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaThread;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.compiler.LoadState;
import org.squiddev.cobalt.debug.DebugFrame;
import org.squiddev.cobalt.debug.DebugHook;
import org.squiddev.cobalt.debug.DebugState;
import org.squiddev.cobalt.function.LibFunction;
import org.squiddev.cobalt.function.LuaFunction;
import org.squiddev.cobalt.interrupt.InterruptAction;
import org.squiddev.cobalt.lib.CoreLibraries;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One isolated Lua 5.2 VM (Cobalt, pure Java). Security model:
 * <ul>
 *   <li>No io, os (except time/clock), debug, package, load, loadstring, dofile, loadfile, getfenv/setfenv,
 *       collectgarbage, string.dump. Cobalt has no Java bridge, so plugins cannot reach JVM objects.</li>
 *   <li>Every call gets an instruction budget and a wall-clock timeout, so an endless loop cannot hang the game.</li>
 *   <li>string.rep output is capped to avoid trivial memory bombs.</li>
 * </ul>
 */
public final class Sandbox {
    private static final int HOOK_INTERVAL = 1000;
    private static final int MAX_STRING = 1 << 20;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcaddons-lua-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final LuaState state;
    private final long instructionLimit;
    private final long timeoutMs;
    private long remaining;
    private volatile boolean running;
    private volatile boolean timedOut;

    public Sandbox(Log log, long instructionLimit, long timeoutMs) {
        this.instructionLimit = instructionLimit;
        this.timeoutMs = timeoutMs;
        this.state = LuaState.builder()
                .interruptHandler(() -> {
                    if (running && timedOut) throw new LuaError("plugin took longer than " + timeoutMs + " ms");
                    return InterruptAction.CONTINUE;
                })
                .errorReporter((e, msg) -> log.error("Lua VM: " + msg.get(), e))
                .build();
        try {
            CoreLibraries.standardGlobals(state);
        } catch (LuaError e) {
            throw new IllegalStateException(e);
        }
        harden(log);
        state.getMainThread().getDebugState().setHook(new Budget(), false, false, false, HOOK_INTERVAL);
    }

    public LuaState state() { return state; }
    public LuaTable globals() { return state.globals(); }

    private void harden(Log log) {
        LuaTable g = state.globals();
        for (String name : new String[]{"dofile", "loadfile", "load", "loadstring", "setfenv", "getfenv",
                "require", "collectgarbage", "module", "io", "debug", "package", "os"}) {
            g.rawset(name, Constants.NIL);
        }
        try {
            LuaTable string = g.rawget("string").checkTable();
            string.rawset("dump", Constants.NIL);
            LuaValue rep = string.rawget("rep");
            string.rawset("rep", LibFunction.createV((s, args) -> {
                long len = (long) args.arg(1).checkLuaString().length() * Math.max(0, args.arg(2).checkInteger());
                if (len > MAX_STRING) throw new LuaError("string.rep result too large");
                try {
                    return org.squiddev.cobalt.function.Dispatch.invoke(s, rep, args);
                } catch (org.squiddev.cobalt.UnwindThrowable u) {
                    throw new LuaError("unexpected yield");
                }
            }));
        } catch (Exception e) {
            throw new IllegalStateException("could not harden string library", e);
        }
        LuaTable os = new LuaTable();
        os.rawset("time", LibFunction.createV((s, a) -> ValueFactory.valueOf(System.currentTimeMillis() / 1000L)));
        os.rawset("clock", LibFunction.createV((s, a) -> ValueFactory.valueOf(System.nanoTime() / 1e9)));
        g.rawset("os", os);
        g.rawset("print", LibFunction.createV((s, args) -> {
            StringBuilder b = new StringBuilder();
            for (int i = 1; i <= args.count(); i++) {
                if (i > 1) b.append('\t');
                b.append(args.arg(i).toString());
            }
            log.info(b.toString());
            return Constants.NONE;
        }));
    }

    /** Compiles a chunk with the sandbox globals as environment. */
    public LuaFunction compile(String source, String chunkName) throws LuaError {
        try {
            return LoadState.load(state, new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), chunkName, state.globals());
        } catch (LuaError e) {
            throw e;
        } catch (Exception e) {
            throw new LuaError("compile error in " + chunkName + ": " + e.getMessage());
        }
    }

    public boolean running() { return running; }

    /** Calls a Lua function with budget + timeout. Not re-entrant (checked by the caller). */
    public Varargs call(LuaValue fn, Varargs args) throws LuaError {
        if (!(fn instanceof LuaFunction f)) throw new LuaError("not a function");
        remaining = instructionLimit;
        timedOut = false;
        running = true;
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            timedOut = true;
            try { state.interrupt(); } catch (Throwable ignored) {}
        }, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            return LuaThread.run(new LuaThread(state, f), args);
        } finally {
            watchdog.cancel(false);
            running = false;
        }
    }

    private final class Budget implements DebugHook {
        @Override
        public void onCount(LuaState s, DebugState ds, DebugFrame frame) throws LuaError {
            remaining -= HOOK_INTERVAL;
            if (remaining <= 0) throw new LuaError("plugin exceeded its instruction budget (" + instructionLimit + ")");
        }
    }
}
