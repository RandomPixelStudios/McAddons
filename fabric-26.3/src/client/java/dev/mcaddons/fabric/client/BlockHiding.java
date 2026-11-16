package dev.mcaddons.fabric.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mcaddons.fabric.Mc;
import dev.mcaddons.fabric.McAddons;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Built-in block hiding (technique from X To Xray): while active, chunk meshes are rebuilt and
 * blocks are swapped for air by a mixin. Mode "xray" shows only the listed blocks, mode "hide"
 * hides only the listed blocks. Allowed in singleplayer, or when the server grants it.
 */
public final class BlockHiding {
    private BlockHiding() {}

    public static final List<String> XRAY_DEFAULT = List.of(
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore", "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
            "minecraft:copper_ore", "minecraft:deepslate_copper_ore", "minecraft:gold_ore", "minecraft:deepslate_gold_ore",
            "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore", "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
            "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
            "minecraft:nether_gold_ore", "minecraft:nether_quartz_ore", "minecraft:ancient_debris",
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:spawner", "minecraft:water", "minecraft:lava");

    public static final class Config {
        public String mode = "xray";                         // "xray" or "hide"
        public List<String> xray = new ArrayList<>(XRAY_DEFAULT);
        public List<String> hide = new ArrayList<>(List.of("minecraft:grass_block", "minecraft:short_grass", "minecraft:tall_grass"));
        public int radius_chunks = 16;
        public boolean night_vision = true;
        public int range = 0;                 // blocks from the player, 0 = everywhere
        public boolean while_moving = true;   // false: show the world normally while moving
    }

    private static final Path FILE = McAddons.ROOT.resolve("block-hiding.json");
    private static Config config;
    private static volatile boolean active;
    private static volatile boolean serverAllows;
    private static volatile Set<Block> blocks = Set.of();
    private static volatile boolean xrayMode = true;
    private static SectionPos lastPos;
    private static volatile boolean renderingEnabled = true;
    private static volatile int range;
    private static volatile int px, py, pz;

    public static Config config() {
        if (config == null) {
            try { config = Files.isRegularFile(FILE) ? new Gson().fromJson(Files.readString(FILE), Config.class) : null; }
            catch (Exception e) { Mc.LOG.warn("invalid block-hiding.json: " + e.getMessage()); }
            if (config == null) config = new Config();
            apply();
        }
        return config;
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(config));
        } catch (Exception e) {
            Mc.LOG.error("could not save block-hiding.json", e);
        }
        apply();
        if (active) rebuild();
    }

    private static void apply() {
        Set<Block> set = new HashSet<>();
        xrayMode = !"hide".equals(config.mode);
        for (String id : xrayMode ? config.xray : config.hide)
            BuiltInRegistries.BLOCK.getOptional(Mc.id(id)).ifPresent(set::add);
        blocks = Set.copyOf(set);
        range = Math.max(0, config.range);
    }

    /** Used by plugins (mc.blockhiding.configure). Unknown keys are ignored. */
    @SuppressWarnings("unchecked")
    public static void configure(java.util.Map<String, Object> cfg) {
        Config c = config();
        if (cfg.get("mode") instanceof String m) c.mode = m.equals("hide") ? "hide" : "xray";
        if (cfg.get("blocks") instanceof List<?> l) {
            List<String> ids = new ArrayList<>();
            for (Object o : l) if (o != null) ids.add(String.valueOf(o));
            if ("hide".equals(c.mode)) c.hide = ids; else c.xray = ids;
        }
        if (cfg.get("range") instanceof Number n) c.range = Math.max(0, n.intValue());
        if (cfg.get("night_vision") instanceof Boolean b) c.night_vision = b;
        if (cfg.get("while_moving") instanceof Boolean b) c.while_moving = b;
        save();
        nightVision(Minecraft.getInstance());
    }

    public static boolean setActive(boolean on) {
        if (on != active) toggle();
        return active;
    }

    public static boolean active() { return active; }

    public static boolean allowed() {
        Minecraft mc = Minecraft.getInstance();
        return mc.hasSingleplayerServer() || serverAllows;
    }

    /** Called by the mixins for every block the chunk mesher reads. Must be fast. */
    public static BlockState filter(BlockState state) { return filter(state, px, py, pz); }

    public static BlockState filter(BlockState state, int x, int y, int z) {
        if (!active || !renderingEnabled || state == null || state.isAir()) return state;
        int r = range;
        if (r > 0 && (Math.abs(x - px) > r || Math.abs(y - py) > r || Math.abs(z - pz) > r)) return state;
        boolean listed = blocks.contains(state.getBlock());
        return (xrayMode != listed) ? Blocks.AIR.defaultBlockState() : state;
    }

    public static void toggle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!active && !allowed()) {
            mc.player.sendSystemMessage(Component.literal("§cBlock hiding is not allowed on this server. (Server admins: /mca blockhiding on)"));
            return;
        }
        config();
        var bp = mc.player.blockPosition();
        px = bp.getX(); py = bp.getY(); pz = bp.getZ();
        renderingEnabled = true;
        active = !active;
        nightVision(mc);
        mc.player.sendOverlayMessage(Component.literal(active ? "§aBlock hiding ON (" + (xrayMode ? "X-ray" : "hide list") + ")" : "§cBlock hiding OFF"));
        rebuild();
    }

    /** Client-side night vision while active (like X To Xray); never sent to the server. */
    static void nightVision(Minecraft mc) {
        if (mc.player == null) return;
        var nv = net.minecraft.world.effect.MobEffects.NIGHT_VISION;
        if (active && renderingEnabled && config().night_vision) mc.player.addEffect(new net.minecraft.world.effect.MobEffectInstance(nv, -1, 0, false, false, false));
        else if (mc.player.hasEffect(nv) && mc.player.getEffect(nv).getDuration() == -1) mc.player.removeEffect(nv);
    }

    public static void setServerAllows(boolean allows) {
        serverAllows = allows;
        if (!allowed() && active) { active = false; nightVision(Minecraft.getInstance()); rebuild(); }
    }

    public static void onDisconnect() { serverAllows = false; active = false; }

    static void tick(Minecraft mc) {
        if (!active || mc.player == null) return;
        if (!allowed()) { active = false; nightVision(mc); rebuild(); return; }
        var bp = mc.player.blockPosition();
        px = bp.getX(); py = bp.getY(); pz = bp.getZ();
        if (!config().while_moving) {
            boolean moving = mc.player.getDeltaMovement().lengthSqr() > 0.01;
            if (renderingEnabled == moving) {   // state changed
                renderingEnabled = !moving;
                rebuild();
                nightVision(mc);
            }
        } else {
            renderingEnabled = true;
            SectionPos pos = SectionPos.of(bp);
            if (range > 0 && !pos.equals(lastPos)) { lastPos = pos; rebuild(); }
        }
    }

    /** Marks all loaded sections dirty so they are meshed again with/without the filter. */
    public static void rebuild() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        SectionPos c = SectionPos.of(mc.player.blockPosition());
        int r = mc.options.getEffectiveRenderDistance();
        for (int sy = mc.level.getMinSectionY(); sy < mc.level.getMaxSectionY(); sy++)
            for (int sx = c.getX() - r; sx <= c.getX() + r; sx++)
                for (int sz = c.getZ() - r; sz <= c.getZ() + r; sz++)
                    mc.level.setSectionDirtyWithNeighbors(sx, sy, sz);
    }
}
