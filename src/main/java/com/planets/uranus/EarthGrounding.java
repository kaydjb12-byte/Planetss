package com.planets.uranus;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.scheduler.BukkitTask;

/**
 * Passive: "Grounded".
 *
 * While you hold Earth and are standing on the ground, knockback is reduced by 50% (configurable).
 * It uses the vanilla knockback-resistance attribute, so it applies to every kind of knockback
 * (melee, arrows, explosions) and is removed the moment you leave the ground. It works out its
 * bonus on top of any resistance you already have (for example from netherite armor) so the total
 * knockback you take is always cut by the configured percentage.
 *
 * Note: vanilla knockback resistance reduces horizontal knockback.
 */
public final class EarthGrounding implements Listener {

    private final UranusPlugin plugin;
    private final EarthItem item;
    private final NamespacedKey key;
    private BukkitTask task;

    public EarthGrounding(UranusPlugin plugin, EarthItem item) {
        this.plugin = plugin;
        this.item = item;
        this.key = plugin.key("earth_grounded");
    }

    public void start() {
        // Every 2 ticks is plenty and keeps it cheap.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            remove(player);
        }
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player);
        }
    }

    private void update(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (instance == null) {
            return;
        }
        FileConfiguration cfg = plugin.getConfig();

        boolean should = cfg.getBoolean("earth.passive.enabled", true)
                && (!cfg.getBoolean("earth.passive.require-held", true) || item.isHolding(player))
                && grounded(player);
        AttributeModifier existing = instance.getModifier(key);

        if (!should) {
            if (existing != null) {
                instance.removeModifier(key);
            }
            return;
        }

        double own = existing == null ? 0.0 : existing.getAmount();
        double current = Math.max(0.0, Math.min(1.0, instance.getValue() - own));
        double reduction = Math.max(0.0, Math.min(0.95, cfg.getDouble("earth.passive.knockback-reduction", 50.0) / 100.0));
        // Knockback taken is (1 - resistance), so scale what is left by (1 - reduction).
        double delta = (1.0 - current) * reduction;

        if (delta < 0.0001) {
            if (existing != null) {
                instance.removeModifier(key);
            }
            return;
        }
        if (existing == null || Math.abs(existing.getAmount() - delta) > 0.001) {
            instance.removeModifier(key);
            instance.addModifier(new AttributeModifier(key, delta, AttributeModifier.Operation.ADD_NUMBER,
                    EquipmentSlotGroup.ANY));
        }
    }

    /** On the ground - not jumping, falling, gliding or flying. */
    private boolean grounded(Player player) {
        return player.isOnGround() && !player.isGliding() && !player.isFlying();
    }

    private void remove(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (instance != null) {
            instance.removeModifier(key);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer());
    }
}
