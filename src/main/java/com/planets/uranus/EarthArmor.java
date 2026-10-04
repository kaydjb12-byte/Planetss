package com.planets.uranus;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.scheduler.BukkitTask;

/**
 * Active ability: "Earth Armor" (/earth1).
 *
 * For a few seconds the caster's armor, armor toughness and knockback resistance are raised to
 * full-netherite-armor levels. Nothing is swapped in the inventory - it is done with temporary
 * attribute modifiers, so the player keeps their own gear (and its enchantments) and there is no
 * way to lose items. Blue and green particles spiral around the caster while it lasts.
 */
public final class EarthArmor implements Listener {

    private static final Particle.DustOptions BLUE = new Particle.DustOptions(Color.fromRGB(47, 128, 255), 1.3f);
    private static final Particle.DustOptions GREEN = new Particle.DustOptions(Color.fromRGB(57, 211, 83), 1.3f);

    private static final class State {
        int ticksLeft;
        int age;

        State(int ticksLeft) {
            this.ticksLeft = ticksLeft;
        }
    }

    private final UranusPlugin plugin;
    private final Map<UUID, State> active = new HashMap<>();
    private final NamespacedKey armorKey;
    private final NamespacedKey toughnessKey;
    private final NamespacedKey knockbackKey;
    private final NamespacedKey passiveKnockbackKey;
    private BukkitTask task;

    public EarthArmor(UranusPlugin plugin) {
        this.plugin = plugin;
        this.armorKey = plugin.key("earth_armor");
        this.toughnessKey = plugin.key("earth_toughness");
        this.knockbackKey = plugin.key("earth_knockback");
        this.passiveKnockbackKey = plugin.key("earth_grounded");
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeModifiers(player);
        }
        active.clear();
    }

    public boolean isActive(UUID id) {
        return active.containsKey(id);
    }

    // --------------------------------------------------------------- activation

    public void activate(Player player) {
        FileConfiguration cfg = plugin.getConfig();
        int ticks = Math.max(1, (int) Math.round(cfg.getDouble("earth.duration-seconds", 10.0) * 20.0));

        // Re-casting while active just refreshes it.
        removeModifiers(player);
        raise(player, Attribute.ARMOR, armorKey, cfg.getDouble("earth.netherite-armor", 20.0), null);
        raise(player, Attribute.ARMOR_TOUGHNESS, toughnessKey, cfg.getDouble("earth.netherite-toughness", 12.0), null);
        // Ignore the Grounded passive's own bonus so this reaches netherite level on its own.
        raise(player, Attribute.KNOCKBACK_RESISTANCE, knockbackKey,
                cfg.getDouble("earth.netherite-knockback-resistance", 0.4), passiveKnockbackKey);

        active.put(player.getUniqueId(), new State(ticks));

        Location loc = player.getLocation();
        World world = loc.getWorld();
        world.playSound(loc, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.2f, 0.8f);
        world.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.4f);
        world.playSound(loc, Sound.BLOCK_GRASS_BREAK, 1.0f, 0.7f);
        burst(loc);
    }

    /**
     * Raises an attribute so its total reaches {@code target}. If the player already has at least
     * that much, nothing is added (it never lowers anything).
     *
     * @param ignoreKey another of our modifiers whose amount should not count towards the current value
     */
    private void raise(Player player, Attribute attribute, NamespacedKey key, double target, NamespacedKey ignoreKey) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(key);
        double current = instance.getValue();
        if (ignoreKey != null) {
            AttributeModifier ignored = instance.getModifier(ignoreKey);
            if (ignored != null) {
                current -= ignored.getAmount();
            }
        }
        double delta = target - current;
        if (delta > 0.0) {
            instance.addModifier(new AttributeModifier(key, delta, AttributeModifier.Operation.ADD_NUMBER,
                    EquipmentSlotGroup.ANY));
        }
    }

    private void removeModifiers(Player player) {
        removeModifier(player, Attribute.ARMOR, armorKey);
        removeModifier(player, Attribute.ARMOR_TOUGHNESS, toughnessKey);
        removeModifier(player, Attribute.KNOCKBACK_RESISTANCE, knockbackKey);
    }

    private void removeModifier(Player player, Attribute attribute, NamespacedKey key) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(key);
        }
    }

    // --------------------------------------------------------------------- tick

    private void tick() {
        Iterator<Map.Entry<UUID, State>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || player.isDead()) {
                if (player != null) {
                    removeModifiers(player);
                }
                it.remove();
                continue;
            }

            State state = entry.getValue();
            state.ticksLeft--;
            state.age++;

            if (state.ticksLeft <= 0) {
                removeModifiers(player);
                it.remove();
                Location loc = player.getLocation();
                loc.getWorld().playSound(loc, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.3f);
                loc.getWorld().playSound(loc, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.0f, 0.8f);
                burst(loc);
                continue;
            }
            particles(player, state.age);
        }
    }

    // ---------------------------------------------------------------- particles

    /** Two interleaved spirals, one blue and one green, plus a ring at the feet. */
    private void particles(Player player, int age) {
        Location base = player.getLocation();
        World world = base.getWorld();
        double t = age * 0.4;

        for (int k = 0; k < 3; k++) {
            double angle = t + k * (Math.PI * 2.0 / 3.0);
            double y = 1.0 + 0.85 * Math.sin(age * 0.12 + k * 2.1);
            Location point = base.clone().add(Math.cos(angle) * 0.85, y, Math.sin(angle) * 0.85);
            world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, (k % 2 == 0) ? BLUE : GREEN);
        }

        for (int k = 0; k < 2; k++) {
            double angle = -t * 0.8 + k * Math.PI;
            double y = 0.2 + (((age * 0.05) + k * 0.5) % 1.0) * 1.7;
            Location point = base.clone().add(Math.cos(angle) * 0.7, y, Math.sin(angle) * 0.7);
            world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, (k % 2 == 0) ? GREEN : BLUE);
        }

        if (age % 5 == 0) {
            int points = 12;
            for (int i = 0; i < points; i++) {
                double angle = (Math.PI * 2.0 * i) / points;
                Location point = base.clone().add(Math.cos(angle) * 1.1, 0.1, Math.sin(angle) * 1.1);
                world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, (i % 2 == 0) ? BLUE : GREEN);
            }
        }
        if (age % 10 == 0) {
            world.spawnParticle(Particle.HAPPY_VILLAGER, base.clone().add(0, 1, 0), 3, 0.5, 0.7, 0.5, 0.0);
        }
    }

    /** Shell of blue and green dust around the player (used when the ability starts and ends). */
    private void burst(Location loc) {
        World world = loc.getWorld();
        int points = 60;
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < points; i++) {
            double y = 1.0 - (2.0 * i) / (points - 1);
            double radius = Math.sqrt(1.0 - y * y);
            double theta = golden * i;
            Location point = loc.clone().add(Math.cos(theta) * radius * 1.3, 1.0 + y * 1.0, Math.sin(theta) * radius * 1.3);
            world.spawnParticle(Particle.DUST, point, 1, 0.02, 0.02, 0.02, 0, (i % 2 == 0) ? BLUE : GREEN);
        }
    }

    // ------------------------------------------------------------------ cleanup

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeModifiers(event.getPlayer());
        active.remove(event.getPlayer().getUniqueId());
    }

    /** Attribute modifiers are saved with the player, so clear any left over from a crash or restart. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        removeModifiers(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        removeModifiers(event.getEntity());
        active.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        removeModifiers(event.getPlayer());
    }
}
