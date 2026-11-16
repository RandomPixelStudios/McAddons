package dev.mcaddons.fabric;

import dev.mcaddons.core.PluginManager;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * A read-only chest GUI. Items cannot be taken; every click is reported to the plugin's
 * on_click(e) callback with {slot, item, player, button}. Used for shops, selectors, menus.
 */
public final class PluginMenu extends ChestMenu {
    private final String plugin;
    private final int callback;
    private final int size;

    PluginMenu(MenuType<?> type, int id, Inventory inv, Container c, int rows, String plugin, int callback) {
        super(type, id, inv, c, rows);
        this.plugin = plugin;
        this.callback = callback;
        this.size = rows * 9;
    }

    static MenuType<?> type(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        // never move items; just report clicks inside the menu
        if (slot >= 0 && slot < size && callback > 0) {
            PluginManager m = McAddons.server();
            ItemStack stack = getContainer().getItem(slot);
            if (m != null) m.callback(plugin, callback, Map.of("slot", slot + 1, "button", button,
                    "player", player.getName().getString(), "item", Mc.itemId(stack)));
        }
        sendAllDataToRemote();
    }

    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return true; }
}
