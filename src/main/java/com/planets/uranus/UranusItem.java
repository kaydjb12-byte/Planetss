package com.planets.uranus;

import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Creates and recognises the Uranus item. */
public final class UranusItem {

    /** Must match the "when" value in the resource pack's heart_of_the_sea.json. */
    private static final String MODEL_KEY = "uranus";

    private final NamespacedKey key;

    public UranusItem(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "uranus_item");
    }

    public ItemStack create() {
        ItemStack stack = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(Component.text("Uranus", NamedTextColor.AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("The ice giant.", NamedTextColor.DARK_AQUA)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Hold it and type /uranus1", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Passive: Frost Armor - attackers get chilled.", NamedTextColor.DARK_AQUA)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);

        // Resource pack looks for this string; without the pack it is just a Heart of the Sea.
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(MODEL_KEY));
        meta.setCustomModelDataComponent(cmd);

        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isUranus(ItemStack stack) {
        if (stack == null || stack.getType() != Material.HEART_OF_THE_SEA || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
