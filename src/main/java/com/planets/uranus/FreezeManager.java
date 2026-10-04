package com.planets.uranus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/** Handles the Uranus freeze: effects, frost wave, shivering and shattering. */
public final class FreezeManager {

    private static final class FrozenState {
        int ticksLeft;

        FrozenState(int ticksLeft) {
            this.ticksLeft = ticksLeft;
        }
    }

    private static final class Wave {
        final Location origin;
        final double maxRadius;
        int age = 0;

        Wave(Location origin, double maxRadius) {
            this.origin = origin;
            this.maxRadius = maxRadius;
        }
    }

    private static final int WAVE_STEPS = 6;
    private static final int WAVE_STEP_TICKS = 2;

    private final UranusPlugin plugin;
    private final Map<UUID, FrozenState> frozen = new HashMap<>();
    private final List<Wave> waves = new ArrayList<>();
    private BukkitTask task;

    public FreezeManager(UranusPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        releaseAll();
        waves.clear();
    }

    public boolean isFrozen(UUID id) {
        return frozen.containsKey(id);
    }

    // ------------------------------------------------------------------ casting

    public void cast(Player caster) {
        FileConfiguration cfg = plugin.getConfig();
        double radius = cfg.getDouble("radius", 10.0);
        int ticks = Math.max(1, (int) Math.round(cfg.getDouble("duration-seconds", 3.0) * 20.0));
        boolean freezePlayers = cfg.getBoolean("freeze-players", true);
        boolean freezeMobs = cfg.getBoolean("freeze-mobs", true);

        Location origin = caster.getLocation();
        World world = origin.getWorld();

        for (Entity entity : caster.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target) || target instanceof ArmorStand) {
                continue;
            }
            if (target.getWorld() != world
                    || target.getLocation().distanceSquared(origin) > radius * radius) {
                continue;
            }
            if (target instanceof Player victim) {
                if (!freezePlayers
                        || victim.getGameMode() == GameMode.SPECTATOR
                        || victim.hasPermission("planets.uranus.bypass")) {
                    continue;
                }
            } else if (!freezeMobs) {
                continue;
            }
            freeze(target, ticks);
        }

        // Burst around the caster, then the expanding frost wave.
        Location chest = origin.clone().add(0, 1, 0);
        world.spawnParticle(Particle.END_ROD, chest, 60, 0.3, 0.6, 0.3, 0.25);
        world.spawnParticle(Particle.SNOWFLAKE, chest, 120, 1.0, 0.8, 1.0, 0.1);
        world.spawnParticle(Particle.BLOCK, origin.clone().add(0, 0.2, 0), 80, 1.5, 0.1, 1.5, 0.1,
                Material.BLUE_ICE.createBlockData());
        world.playSound(origin, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.6f);
        world.playSound(origin, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.5f, 0.8f);
        world.playSound(origin, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.5f, 0.5f);

        waves.add(new Wave(origin.clone(), radius));
    }

    private void freeze(LivingEntity target, int ticks) {
        boolean already = frozen.containsKey(target.getUniqueId());
        frozen.put(target.getUniqueId(), new FrozenState(ticks));

        // Particles are hidden; the plugin draws its own ice effects.
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 255, false, false, false));
        target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 250, false, false, false));
        target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 255, false, false, false));
        target.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, ticks, 255, false, false, false));
        // Full freeze: blue overlay and shivering (works on players too).
        target.setFreezeTicks(target.getMaxFreezeTicks());

        if (already) {
            return;
        }

        Location loc = target.getLocation();
        World world = loc.getWorld();
        Location body = loc.clone().add(0, 1, 0);
        world.spawnParticle(Particle.BLOCK, body, 40, 0.4, 0.7, 0.4, 0.05,
                Material.PACKED_ICE.createBlockData());
        world.spawnParticle(Particle.SNOWFLAKE, body, 30, 0.4, 0.7, 0.4, 0.05);
        world.playSound(loc, Sound.BLOCK_GLASS_PLACE, 1.0f, 0.7f);

        if (target instanceof Player victim) {
            victim.sendActionBar(plugin.message("frozen"));
            victim.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT_FREEZE, 1.0f, 1.0f);
        }
    }

    // --------------------------------------------------------------------- tick

    private void tick() {
        tickWaves();
        tickFrozen();
    }

    private void tickWaves() {
        Iterator<Wave> it = waves.iterator();
        while (it.hasNext()) {
            Wave wave = it.next();
            wave.age++;
            if (wave.age % WAVE_STEP_TICKS == 1) {
                int step = (wave.age / WAVE_STEP_TICKS) + 1;
                if (step > WAVE_STEPS) {
                    it.remove();
                    continue;
                }
                drawRing(wave.origin, wave.maxRadius * step / WAVE_STEPS);
            }
        }
    }

    private void drawRing(Location origin, double radius) {
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        int points = 24;
        for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2.0 * i) / points;
            Location p = origin.clone().add(Math.cos(angle) * radius, 0.1, Math.sin(angle) * radius);
            world.spawnParticle(Particle.SNOWFLAKE, p, 3, 0.05, 0.05, 0.05, 0.01);
            world.spawnParticle(Particle.BLOCK, p, 2, 0.1, 0.05, 0.1, 0.0,
                    Material.BLUE_ICE.createBlockData());
            if (i % 6 == 0) {
                world.spawnParticle(Particle.END_ROD, p.clone().add(0, 0.2, 0), 3, 0.05, 0.3, 0.05, 0.03);
            }
        }
    }

    private void tickFrozen() {
        Iterator<Map.Entry<UUID, FrozenState>> it = frozen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, FrozenState> entry = it.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity target) || !target.isValid()) {
                // Offline, dead or unloaded - nothing left to maintain.
                it.remove();
                continue;
            }

            FrozenState state = entry.getValue();
            state.ticksLeft--;

            if (state.ticksLeft <= 0) {
                shatter(target);
                clearEffects(target);
                it.remove();
                continue;
            }

            target.setFreezeTicks(target.getMaxFreezeTicks());

            Location loc = target.getLocation();
            World world = loc.getWorld();
            Location body = loc.clone().add(0, 1, 0);
            world.spawnParticle(Particle.SNOWFLAKE, body, 2, 0.3, 0.6, 0.3, 0.01);
            world.spawnParticle(Particle.BLOCK, body, 3, 0.35, 0.7, 0.35, 0.0,
                    Material.BLUE_ICE.createBlockData());

            int mod = state.ticksLeft % 20;
            if (mod == 10) {
                world.spawnParticle(Particle.END_ROD, body, 6, 0.3, 0.6, 0.3, 0.02);
            }
            if (mod == 15) {
                world.playSound(loc, Sound.ENTITY_PLAYER_HURT_FREEZE, 0.8f, 1.4f);
            }

            if (target instanceof Player player) {
                shiver(player, state.ticksLeft);
            }
        }
    }

    /** Small alternating camera jitter so the frozen player visibly shivers. */
    private void shiver(Player player, int ticksLeft) {
        float sign = (ticksLeft % 2 == 0) ? 1.0f : -1.0f;
        Location loc = player.getLocation();
        float pitch = Math.max(-90.0f, Math.min(90.0f, loc.getPitch() + 0.8f * sign));
        player.setRotation(loc.getYaw() + 1.5f * sign, pitch);

        // Frost flakes drifting in front of the face.
        Location face = player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.7));
        player.getWorld().spawnParticle(Particle.SNOWFLAKE, face, 2, 0.25, 0.2, 0.1, 0.0);
    }

    // ------------------------------------------------------------------ cleanup

    private void shatter(LivingEntity target) {
        Location loc = target.getLocation();
        World world = loc.getWorld();
        Location body = loc.clone().add(0, 1, 0);
        world.spawnParticle(Particle.BLOCK, body, 60, 0.4, 0.6, 0.4, 0.2,
                Material.BLUE_ICE.createBlockData());
        world.spawnParticle(Particle.BLOCK, body, 40, 0.4, 0.6, 0.4, 0.15,
                Material.GLASS.createBlockData());
        world.spawnParticle(Particle.SNOWFLAKE, body, 50, 0.4, 0.6, 0.4, 0.15);
        world.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1.2f, 1.2f);
        world.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 0.8f);
    }

    private void clearEffects(LivingEntity target) {
        target.removePotionEffect(PotionEffectType.SLOWNESS);
        target.removePotionEffect(PotionEffectType.JUMP_BOOST);
        target.removePotionEffect(PotionEffectType.WEAKNESS);
        target.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        target.setFreezeTicks(0);
    }

    /** Ends the freeze immediately without the shatter effect (quit, shutdown, etc.). */
    public void release(LivingEntity target) {
        if (frozen.remove(target.getUniqueId()) != null) {
            clearEffects(target);
        }
    }

    /** Forget a dead entity - nothing to clean up on it. */
    public void forget(UUID id) {
        frozen.remove(id);
    }

    private void releaseAll() {
        for (UUID id : new ArrayList<>(frozen.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity living) {
                clearEffects(living);
            }
        }
        frozen.clear();
    }
}
