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
        public List<OreDef> ores = new ArrayList<>();
        public List<LootDef> loot = new ArrayList<>();
    }

    /** World generation: places a block like an ore. */
    public static final class OreDef {
        public String block;                       // "ruby_ore" (own) or "minecraft:..." / "plugin:..."
        public String dimension = "overworld";     // overworld | nether | end
        public int size = 6;
        @SerializedName("per_chunk") public int perChunk = 4;
        @SerializedName("min_y") public int minY = -64;
        @SerializedName("max_y") public int maxY = 48;
    }

    /** Adds an extra drop to an existing loot table. */
    public static final class LootDef {
        public String table;          // e.g. "minecraft:blocks/grass_block" or "minecraft:entities/zombie"
        public String item;           // own id or full id
        public float chance = 1f;
        public int min = 1;
        public int max = 1;
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
        if (content.ores == null) content.ores = new ArrayList<>();
        if (content.loot == null) content.loot = new ArrayList<>();
        for (OreDef o : content.ores) {
            if (o.block == null || o.block.isBlank()) throw new PluginException("ore needs a block");
            o.size = Math.max(1, Math.min(o.size, 32));
            o.perChunk = Math.max(1, Math.min(o.perChunk, 64));
            if (o.minY > o.maxY) throw new PluginException("ore min_y > max_y");
        }
        for (LootDef l : content.loot)
            if (l.table == null || l.item == null) throw new PluginException("loot entry needs table and item");
        boolean any = !content.items.isEmpty() || !content.blocks.isEmpty() || !content.ores.isEmpty() || !content.loot.isEmpty();
        if (any && !permissions.contains("content"))
            throw new PluginException("plugins adding items/blocks/ores/loot must request the 'content' permission");
    }

    /** Resolves "ruby_ore" to "<plugin>:ruby_ore", keeps namespaced ids. */
    public String qualify(String id) { return id.contains(":") ? id : this.id + ":" + id; }

    public Side sideEnum() { return Side.parse(side); }

    public Set<Permission> permissionSet() {
        Set<Permission> set = EnumSet.noneOf(Permission.class);
        for (String p : permissions) Permission.byId(p).ifPresent(set::add);
        return set;
    }

    public String toJson() { return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(this); }
}
