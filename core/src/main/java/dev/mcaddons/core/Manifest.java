package dev.mcaddons.core;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** plugin.json – the only metadata format a plugin needs. */
public final class Manifest {
    public static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]{1,31}");
    private static final Gson GSON = new Gson();

    public String id;
    public String name;
    public String version = "1.0.0";
    public String description = "";
    public List<String> authors = new ArrayList<>();
    public String api = ApiVersion.STRING;
    public String side = "both";
    public String entry = "main.lua";
    public List<String> permissions = new ArrayList<>();
    public Content content = new Content();

    public static final class Content {
        public List<ItemDef> items = new ArrayList<>();
        public List<BlockDef> blocks = new ArrayList<>();
    }

    public static final class ItemDef {
        public String id;
        public String name;
        @SerializedName("max_stack") public int maxStack = 64;
        public int food = 0;          // nutrition, 0 = not edible
        public float saturation = 0.3f;
        public boolean glint = false;
    }

    public static final class BlockDef {
        public String id;
        public String name;
        public float hardness = 1.5f;
        public float resistance = 6f;
        public int light = 0;
        public String sound = "stone";
    }

    public static Manifest read(Path file) throws PluginException {
        try {
            Manifest m = GSON.fromJson(Files.readString(file), Manifest.class);
            if (m == null) throw new PluginException("plugin.json is empty");
            m.validate();
            return m;
        } catch (IOException | JsonParseException e) {
            throw new PluginException("invalid plugin.json: " + e.getMessage(), e);
        }
    }

    public void validate() throws PluginException {
        if (id == null || !ID.matcher(id).matches())
            throw new PluginException("id must match " + ID.pattern() + ": " + id);
        if (name == null || name.isBlank()) name = id;
        if (!ApiVersion.compatible(api))
            throw new PluginException("plugin needs API " + api + " but this build provides " + ApiVersion.STRING);
        if (entry == null || !Paths.safeRelative(entry)) throw new PluginException("unsafe entry path: " + entry);
        if (permissions == null) permissions = new ArrayList<>();
        for (String p : permissions)
            if (Permission.byId(p).isEmpty()) throw new PluginException("unknown permission: " + p);
        if (content == null) content = new Content();
        for (ItemDef i : content.items)
            if (i.id == null || !ID.matcher(i.id).matches()) throw new PluginException("bad item id " + i.id);
        for (BlockDef b : content.blocks)
            if (b.id == null || !ID.matcher(b.id).matches()) throw new PluginException("bad block id " + b.id);
        if ((!content.items.isEmpty() || !content.blocks.isEmpty()) && !permissions.contains("content"))
            throw new PluginException("plugins adding items/blocks must request the 'content' permission");
    }

    public Side sideEnum() { return Side.parse(side); }

    public Set<Permission> permissionSet() {
        Set<Permission> set = EnumSet.noneOf(Permission.class);
        for (String p : permissions) Permission.byId(p).ifPresent(set::add);
        return set;
    }

    public String toJson() { return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(this); }
}
