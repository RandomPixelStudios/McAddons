package dev.mcaddons.core;

/** Which logical side a plugin (or a plugin manager) runs on. */
public enum Side {
    SERVER, CLIENT, BOTH;

    public boolean runsOn(Side managerSide) {
        return this == BOTH || this == managerSide;
    }

    public static Side parse(String s) {
        if (s == null) return BOTH;
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "server" -> SERVER;
            case "client" -> CLIENT;
            default -> BOTH;
        };
    }
}
