package dev.mcaddons.fabric;

import dev.mcaddons.core.Manifest;
import dev.mcaddons.core.PluginManager;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Registers items/blocks declared in plugin.json. Minecraft freezes its registries after startup,
 * so content (unlike Lua code) needs a restart; behaviour is still written in Lua via events.
 */
public final class Content {
    private Content() {}

    public static final List<String> REGISTERED = new ArrayList<>();

    public static void registerAll(Path root) {
        List<Manifest> manifests = PluginManager.enabledManifests(root, Mc.LOG);
        registerContent(manifests);
        registerWorldgenAndLoot(manifests);
        registerCreativeTab();
        PluginPack.build(manifests);
    }

    private static void registerContent(List<Manifest> manifests) {
        for (Manifest m : manifests) {
            if (!m.permissions.contains("content")) continue;
            for (Manifest.ItemDef def : m.content.items) {
                Identifier id = Identifier.fromNamespaceAndPath(m.id, def.id);
                ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
                Item.Properties p = new Item.Properties().setId(key).stacksTo(Math.max(1, Math.min(99, def.maxStack)))
                        .component(DataComponents.ITEM_NAME, Component.literal(def.name == null ? def.id : def.name));
                if (def.food > 0) p.food(new FoodProperties(def.food, def.saturation, false));
                if (def.glint) p.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                Registry.register(BuiltInRegistries.ITEM, key, new Item(p));
                REGISTERED.add(id.toString());
            }
            for (Manifest.BlockDef def : m.content.blocks) {
                Identifier id = Identifier.fromNamespaceAndPath(m.id, def.id);
                ResourceKey<Block> bkey = ResourceKey.create(Registries.BLOCK, id);
                int light = Math.max(0, Math.min(15, def.light));
                Block block = new Block(BlockBehaviour.Properties.of().setId(bkey)
                        .strength(def.hardness, def.resistance).lightLevel(s -> light).sound(sound(def.sound)));
                Registry.register(BuiltInRegistries.BLOCK, bkey, block);
                ResourceKey<Item> ikey = ResourceKey.create(Registries.ITEM, id);
                Registry.register(BuiltInRegistries.ITEM, ikey, new BlockItem(block, new Item.Properties().setId(ikey)
                        .component(DataComponents.ITEM_NAME, Component.literal(def.name == null ? def.id : def.name))));
                REGISTERED.add(id.toString());
            }
        }
        if (!REGISTERED.isEmpty()) Mc.LOG.info("Registered plugin content: " + REGISTERED);
    }

    private static void registerWorldgenAndLoot(List<Manifest> manifests) {
        for (Manifest m : manifests) {
            if (!m.permissions.contains("content")) continue;
            int n = 0;
            for (Manifest.OreDef o : m.content.ores) {
                ResourceKey<net.minecraft.world.level.levelgen.placement.PlacedFeature> key = ResourceKey.create(
                        Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath(m.id, "ore_" + (n++)));
                var selector = switch (o.dimension) {
                    case "nether" -> net.fabricmc.fabric.api.biome.v1.BiomeSelectors.foundInTheNether();
                    case "end" -> net.fabricmc.fabric.api.biome.v1.BiomeSelectors.foundInTheEnd();
                    default -> net.fabricmc.fabric.api.biome.v1.BiomeSelectors.foundInOverworld();
                };
                net.fabricmc.fabric.api.biome.v1.BiomeModifications.addFeature(selector,
                        net.minecraft.world.level.levelgen.GenerationStep.Decoration.UNDERGROUND_ORES, key);
            }
            for (Manifest.LootDef l : m.content.loot) {
                Identifier table = Mc.id(l.table);
                Identifier item = Mc.id(m.qualify(l.item));
                net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY.register((key, builder, source, lookup) -> {
                    if (!key.identifier().equals(table)) return;
                    var it = BuiltInRegistries.ITEM.getOptional(item);
                    if (it.isEmpty()) { Mc.LOG.warn(m.id + ": loot item not found " + item); return; }
                    builder.withPool(net.minecraft.world.level.storage.loot.LootPool.lootPool()
                            .add(net.minecraft.world.level.storage.loot.entries.LootItem.lootTableItem(it.get())
                                    .apply(net.minecraft.world.level.storage.loot.functions.SetItemCountFunction.setCount(
                                            net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders.between(l.min, Math.max(l.min, l.max)))))
                            .when(net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition.randomChance(Math.max(0f, Math.min(1f, l.chance)))));
                });
            }
        }
    }

    private static void registerCreativeTab() {
        if (REGISTERED.isEmpty()) return;
        var key = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("mcaddons", "plugins"));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, key, net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder()
                .title(Component.literal("Plugins"))
                .icon(() -> new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(REGISTERED.get(0)))))
                .displayItems((params, out) -> REGISTERED.forEach(id -> out.accept(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)))))
                .build());
    }

    private static SoundType sound(String s) {
        return switch (s == null ? "" : s) {
            case "wood" -> SoundType.WOOD;
            case "metal" -> SoundType.METAL;
            case "glass" -> SoundType.GLASS;
            case "grass" -> SoundType.GRASS;
            case "wool" -> SoundType.WOOL;
            case "sand" -> SoundType.SAND;
            case "gravel" -> SoundType.GRAVEL;
            default -> SoundType.STONE;
        };
    }
}
