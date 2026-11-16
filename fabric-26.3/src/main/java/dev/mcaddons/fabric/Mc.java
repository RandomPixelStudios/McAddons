package dev.mcaddons.fabric;

import dev.mcaddons.core.Log;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Small helpers shared by the 26.3 adapter. */
public final class Mc {
    private Mc() {}

    public static final Logger LOGGER = LoggerFactory.getLogger("MC Addons");
    public static final Log LOG = new Log() {
        public void info(String m) { LOGGER.info(m); }
        public void warn(String m) { LOGGER.warn(m); }
        public void error(String m, Throwable t) { if (t == null) LOGGER.error(m); else LOGGER.error(m, t); }
    };

    public static Identifier id(String s) {
        Identifier id = Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s);
        if (id == null) throw new IllegalArgumentException("invalid id: " + s);
        return id;
    }

    public static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static String version() {
        return net.minecraft.SharedConstants.getCurrentVersion().name();
    }
}
