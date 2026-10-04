package com.planets.uranus;

import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Creates and recognises the Earth item (a Nautilus Shell, so it differs from Uranus even without the resource pack). */
public final class EarthItem {

    /** Must match the "when" value in the resource pack's nautilus_shell.json. */
    private static final String MODEL_KEY = "earth";

    private final NamespacedKey key;

    public EarthItem(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "earth_item");
    }

    public ItemStack create() {
        ItemStack stack = new ItemStack(Material.NAUTILUS_SHELL);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(Component.text("Earth", NamedTextColor.GREEN, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("The living world.", NamedTextColor.DARK_GREEN)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Hold it and type /earth1", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Passive: Grounded - half knockback while on the ground.", NamedTextColor.DARK_GREEN)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);

        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(MODEL_KEY));
        meta.setCustomModelDataComponent(cmd);

        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isEarth(ItemStack stack) {
        if (stack == null || stack.getType() != Material.NAUTILUS_SHELL || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** True if the player holds Earth in the main hand or off hand. */
    public boolean isHolding(Player player) {
        return isEarth(player.getInventory().getItemInMainHand())
                || isEarth(player.getInventory().getItemInOffHand());
    }
}
