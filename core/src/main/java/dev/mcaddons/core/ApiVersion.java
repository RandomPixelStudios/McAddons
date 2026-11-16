package dev.mcaddons.core;

/**
 * The Lua API is versioned independently of Minecraft. Plugins declare "api": "1.0"; they load as
 * long as the major version matches and the requested minor is not newer than ours. Every
 * version adapter (fabric-26.3, fabric-1.21, ...) implements the same API version, which is what
 * lets one plugin run on every Minecraft version.
 */
public final class ApiVersion {
    public static final int MAJOR = 1;
    public static final int MINOR = 2;
    public static final String STRING = MAJOR + "." + MINOR;

    private ApiVersion() {}

    public static boolean compatible(String requested) {
        if (requested == null || requested.isBlank()) return false;
        String[] parts = requested.trim().split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major == MAJOR && minor <= MINOR;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
