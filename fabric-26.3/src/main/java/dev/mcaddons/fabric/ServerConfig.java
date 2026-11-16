package dev.mcaddons.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

/** mcaddons/server-config.json – settings server operators control. */
public final class ServerConfig {
    /** Lets players with MC Addons use the built-in block hiding (X-ray) on this server. */
    public boolean allow_block_hiding = false;
    /** If allowed: only operators may use it. */
    public boolean block_hiding_ops_only = false;

    private static final Path FILE = McAddons.ROOT.resolve("server-config.json");
    private static ServerConfig current;

    public static synchronized ServerConfig get() {
        if (current == null) {
            try {
                current = Files.isRegularFile(FILE) ? new Gson().fromJson(Files.readString(FILE), ServerConfig.class) : null;
            } catch (Exception e) {
                Mc.LOG.warn("invalid server-config.json, using defaults: " + e.getMessage());
            }
            if (current == null) current = new ServerConfig();
            save();
        }
        return current;
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(current));
        } catch (Exception e) {
            Mc.LOG.error("could not save server-config.json", e);
        }
    }
}
