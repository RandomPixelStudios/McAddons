package dev.mcaddons.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mcaddons.core.Manifest;
import dev.mcaddons.core.PluginManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Builds one always-on pack ("MC Addons plugins") from all enabled plugins:
 * <ul>
 *   <li>{@code assets/} folders of plugins with the "resources" permission (textures, models, sounds, lang)</li>
 *   <li>{@code data/} folders of plugins with the "data" permission (recipes, loot tables, tags, advancements, worldgen)</li>
 *   <li>generated models / blockstates / block loot / translations for items and blocks from plugin.json</li>
 *   <li>generated worldgen features for ores from plugin.json</li>
 * </ul>
 * The pack is injected into both the client resource repository and every server data repository.
 */
public final class PluginPack {
    private PluginPack() {}

    public static final Path DIR = McAddons.ROOT.resolve("generated-pack");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> ASSET_EXT = Set.of(".png", ".json", ".ogg", ".mcmeta", ".txt", ".fsh", ".vsh", ".glsl");
    private static final Set<String> DATA_EXT = Set.of(".json", ".mcfunction", ".nbt");

    public static void build(List<Manifest> manifests) {
        try {
            deleteTree(DIR);
            Files.createDirectories(DIR);
            Map<String, Object> pack = new LinkedHashMap<>();
            pack.put("description", "Resources and data from MC Addons plugins");
            pack.put("min_format", 82);
            pack.put("max_format", 1000);
            json(DIR.resolve("pack.mcmeta"), Map.of("pack", pack));
            for (Manifest m : manifests) {
                Path src = McAddons.ROOT.resolve("plugins").resolve(m.id);
                if (m.permissions.contains("resources")) copy(src.resolve("assets"), DIR.resolve("assets"), ASSET_EXT, m.id);
                if (m.permissions.contains("data")) copy(src.resolve("data"), DIR.resolve("data"), DATA_EXT, m.id);
                if (m.permissions.contains("content")) generate(m);
            }
            Mc.LOG.info("Built plugin pack at " + DIR);
        } catch (IOException e) {
            Mc.LOG.error("could not build plugin pack", e);
        }
    }

    private static void generate(Manifest m) throws IOException {
        String ns = m.id;
        Map<String, String> lang = new LinkedHashMap<>();
        for (Manifest.ItemDef i : m.content.items) {
            ifAbsent(DIR.resolve("assets/" + ns + "/items/" + i.id + ".json"),
                    Map.of("model", Map.of("type", "minecraft:model", "model", ns + ":item/" + i.id)));
            ifAbsent(DIR.resolve("assets/" + ns + "/models/item/" + i.id + ".json"),
                    Map.of("parent", "minecraft:item/generated", "textures", Map.of("layer0", ns + ":item/" + i.id)));
            lang.put("item." + ns + "." + i.id, i.name == null ? i.id : i.name);
        }
        for (Manifest.BlockDef b : m.content.blocks) {
            ifAbsent(DIR.resolve("assets/" + ns + "/blockstates/" + b.id + ".json"),
                    Map.of("variants", Map.of("", Map.of("model", ns + ":block/" + b.id))));
            ifAbsent(DIR.resolve("assets/" + ns + "/models/block/" + b.id + ".json"),
                    Map.of("parent", "minecraft:block/cube_all", "textures", Map.of("all", ns + ":block/" + b.id)));
            ifAbsent(DIR.resolve("assets/" + ns + "/items/" + b.id + ".json"),
                    Map.of("model", Map.of("type", "minecraft:model", "model", ns + ":block/" + b.id)));
            ifAbsent(DIR.resolve("data/" + ns + "/loot_table/blocks/" + b.id + ".json"), Map.of(
                    "type", "minecraft:block",
                    "pools", List.of(Map.of("rolls", 1, "entries", List.of(Map.of("type", "minecraft:item", "name", ns + ":" + b.id)),
                            "conditions", List.of(Map.of("condition", "minecraft:survives_explosion"))))));
            lang.put("block." + ns + "." + b.id, b.name == null ? b.id : b.name);
        }
        if (!lang.isEmpty()) ifAbsent(DIR.resolve("assets/" + ns + "/lang/en_us.json"), lang);

        int n = 0;
        for (Manifest.OreDef o : m.content.ores) {
            String name = "ore_" + (n++);
            String replace = switch (o.dimension) {
                case "nether" -> "minecraft:base_stone_nether";
                case "end" -> null;
                default -> "minecraft:stone_ore_replaceables";
            };
            Object target = replace == null
                    ? Map.of("predicate_type", "minecraft:block_match", "block", "minecraft:end_stone")
                    : Map.of("predicate_type", "minecraft:tag_match", "tag", replace);
            // 26.x format: worldgen/feature, flat config, state as plain block id
            json(DIR.resolve("data/" + ns + "/worldgen/feature/" + name + ".json"), Map.of(
                    "type", "minecraft:ore", "size", o.size, "discard_chance_on_air_exposure", 0.0,
                    "targets", List.of(Map.of("target", target, "state", m.qualify(o.block)))));
            json(DIR.resolve("data/" + ns + "/worldgen/placed_feature/" + name + ".json"), Map.of(
                    "feature", ns + ":" + name,
                    "placement", List.of(
                            Map.of("type", "minecraft:count", "count", o.perChunk),
                            Map.of("type", "minecraft:in_square"),
                            Map.of("type", "minecraft:height_range", "height", Map.of("type", "minecraft:uniform",
                                    "min_inclusive", Map.of("absolute", o.minY), "max_inclusive", Map.of("absolute", o.maxY))),
                            Map.of("type", "minecraft:biome"))));
        }
    }

    private static void copy(Path from, Path to, Set<String> allowed, String plugin) throws IOException {
        if (!Files.isDirectory(from)) return;
        Path root = from.toRealPath();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                if (Files.isSymbolicLink(f)) continue;
                String rel = root.relativize(f).toString().replace('\\', '/');
                String name = f.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot < 0 || !allowed.contains(name.substring(dot)) || !dev.mcaddons.core.Paths.safeRelative(rel)) {
                    Mc.LOG.warn(plugin + ": skipping pack file " + rel);
                    continue;
                }
                if (Files.size(f) > 8 << 20) { Mc.LOG.warn(plugin + ": file too large " + rel); continue; }
                Path out = to.resolve(rel).normalize();
                if (!out.startsWith(to)) continue;
                Files.createDirectories(out.getParent());
                Files.copy(f, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void ifAbsent(Path p, Object value) throws IOException { if (!Files.exists(p)) json(p, value); }

    private static void json(Path p, Object value) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, GSON.toJson(value));
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) { for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x); }
    }

    /** Repository source used by the mixin; type is detected from the repository it is added to. */
    public static RepositorySource source(PackType type) {
        return consumer -> {
            if (!Files.isRegularFile(DIR.resolve("pack.mcmeta"))) return;
            Pack pack = Pack.readMetaAndCreate(
                    new PackLocationInfo("mcaddons_plugins", Component.literal("MC Addons plugins"), PackSource.BUILT_IN, Optional.empty()),
                    new PathPackResources.PathResourcesSupplier(DIR), type,
                    new PackSelectionConfig(true, Pack.Position.TOP, true));
            if (pack != null) consumer.accept(pack);
            else Mc.LOG.warn("plugin pack could not be read");
        };
    }

    static List<Manifest> enabled() { return PluginManager.enabledManifests(McAddons.ROOT, Mc.LOG); }
}
