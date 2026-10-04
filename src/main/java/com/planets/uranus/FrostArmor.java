package com.planets.uranus;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Passive: "Frost Armor".
 *
 * While you hold Uranus, anyone who hits you is chilled - a nerfed powder-snow effect:
 * a partial frost overlay plus a short, mild Slowness. It never fully freezes or damages,
 * and each attacker has a cooldown so it cannot be chained.
 */
public final class FrostArmor implements Listener {

    private final UranusPlugin plugin;
    private final UranusItem item;
    private final FreezeManager freeze;

    /** Attacker -> ticks of chill remaining. */
    private final Map<UUID, Integer> chilled = new HashMap<>();
    /** Attacker -> time (ms) when they can be chilled again. */
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private BukkitTask task;

    public FrostArmor(UranusPlugin plugin, UranusItem item, FreezeManager freeze) {
        this.plugin = plugin;
        this.item = item;
        this.freeze = freeze;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : chilled.keySet()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity living && !freeze.isFrozen(id)) {
                living.setFreezeTicks(0);
            }
        }
        chilled.clear();
        cooldowns.clear();
    }

    // ------------------------------------------------------------------ trigger

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("passive.enabled", true)) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (event.getFinalDamage() <= 0.0) {
            return;
        }

        // Who dealt the hit? Melee by default; projectiles only if enabled.
        LivingEntity attacker = null;
        Entity damager = event.getDamager();
        if (damager instanceof LivingEntity living) {
            attacker = living;
        } else if (damager instanceof Projectile projectile
                && cfg.getBoolean("passive.include-projectiles", false)
                && projectile.getShooter() instanceof LivingEntity shooter) {
            attacker = shooter;
        }
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        if (attacker instanceof Player player) {
            if (player.hasPermission("planets.uranus.bypass")
                    || player.getGameMode() == GameMode.SPECTATOR) {
                return;
            }
        } else if (!cfg.getBoolean("passive.affect-mobs", false)) {
            return;
        }

        if (cfg.getBoolean("passive.require-held", true) && !holdsUranus(victim)) {
            return;
        }

        // Already fully frozen by the active ability - nothing to add.
        if (freeze.isFrozen(attacker.getUniqueId())) {
            return;
        }

        long now = System.currentTimeMillis();
        Long readyAt = cooldowns.get(attacker.getUniqueId());
        if (readyAt != null && readyAt > now) {
            return;
        }
        long cooldownMillis = (long) (cfg.getDouble("passive.per-attacker-cooldown-seconds", 4.0) * 1000.0);
        cooldowns.put(attacker.getUniqueId(), now + cooldownMillis);

        chill(attacker);
    }

    private boolean holdsUranus(Player player) {
        return item.isUranus(player.getInventory().getItemInMainHand())
                || item.isUranus(player.getInventory().getItemInOffHand());
    }

    // ------------------------------------------------------------------- effect

    private void chill(LivingEntity target) {
        FileConfiguration cfg = plugin.getConfig();
        int ticks = Math.max(1, (int) Math.round(cfg.getDouble("passive.duration-seconds", 2.0) * 20.0));
        int amplifier = Math.max(0, cfg.getInt("passive.slowness-amplifier", 0));

        chilled.put(target.getUniqueId(), ticks);

        // Don't downgrade a stronger slowness the target already has.
        PotionEffect existing = target.getPotionEffect(PotionEffectType.SLOWNESS);
        if (existing == null || existing.getAmplifier() <= amplifier) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, amplifier, false, false, true));
        }
        target.setFreezeTicks(Math.max(target.getFreezeTicks(), chillLevel(target)));

        Location loc = target.getLocation();
        loc.getWorld().spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
        loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_HURT_FREEZE, 0.6f, 1.3f);

        if (target instanceof Player player) {
            player.sendActionBar(plugin.message("chilled"));
        }
    }

    /** Freeze ticks for the configured overlay strength - always below full freeze (no damage). */
    private int chillLevel(LivingEntity target) {
        double percent = plugin.getConfig().getDouble("passive.freeze-percent", 45.0);
        percent = Math.max(0.0, Math.min(95.0, percent));
        int max = target.getMaxFreezeTicks();
        return Math.min(max - 1, (int) (max * percent / 100.0));
    }

    private void tick() {
        Iterator<Map.Entry<UUID, Integer>> it = chilled.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity target) || !target.isValid()) {
                it.remove();
                continue;
            }

            int left = entry.getValue() - 1;
            if (left <= 0) {
                it.remove();
                if (!freeze.isFrozen(entry.getKey())) {
                    target.setFreezeTicks(0);
                }
                continue;
            }
            entry.setValue(left);

            // Vanilla freeze ticks drain quickly, so top them up to hold the overlay.
            if (!freeze.isFrozen(entry.getKey())) {
                target.setFreezeTicks(Math.max(target.getFreezeTicks(), chillLevel(target)));
            }
            if (left % 10 == 0) {
                Location loc = target.getLocation();
                loc.getWorld().spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0, 1, 0), 3, 0.3, 0.5, 0.3, 0.01);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        chilled.remove(id);
        cooldowns.remove(id);
    }
}
