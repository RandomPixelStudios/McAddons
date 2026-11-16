package dev.mcaddons.core;

import java.nio.file.Path;

/** Path checks used everywhere a plugin or the registry names a file. */
public final class Paths {
    private Paths() {}

    public static boolean safeRelative(String p) {
        if (p == null || p.isEmpty() || p.length() > 128) return false;
        if (p.startsWith("/") || p.contains("\\") || p.contains(":") || p.contains("\0")) return false;
        for (String part : p.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith(".")) return false;
            if (!part.matches("[A-Za-z0-9_\\-.]+")) return false;
        }
        return true;
    }

    public static Path resolveInside(Path root, String relative) {
        if (!safeRelative(relative)) throw new IllegalArgumentException("unsafe path: " + relative);
        Path r = root.toAbsolutePath().normalize();
        Path f = r.resolve(relative).normalize();
        if (!f.startsWith(r)) throw new IllegalArgumentException("path escapes plugin dir: " + relative);
        return f;
    }
}
