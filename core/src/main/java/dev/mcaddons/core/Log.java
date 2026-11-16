package dev.mcaddons.core;

/** Minimal logging abstraction so the core does not depend on a logging framework. */
public interface Log {
    void info(String msg);
    void warn(String msg);
    void error(String msg, Throwable t);

    Log STDOUT = new Log() {
        public void info(String m) { System.out.println("[INFO] " + m); }
        public void warn(String m) { System.out.println("[WARN] " + m); }
        public void error(String m, Throwable t) { System.out.println("[ERROR] " + m + (t != null ? " (" + t + ")" : "")); }
    };
}
