package com.planets.uranus;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Keeps frozen entities frozen: no walking, no attacking, no freeze damage. */
public final class FreezeListener implements Listener {

    private final FreezeManager freeze;

    public FreezeListener(FreezeManager freeze) {
        this.freeze = freeze;
    }

    private boolean frozen(Entity entity) {
        return entity != null && freeze.isFrozen(entity.getUniqueId());
    }

    /** Frozen players can look around but cannot walk (they can still fall). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onMove(PlayerMoveEvent event) {
        if (!frozen(event.getPlayer())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            Location locked = new Location(to.getWorld(), from.getX(), to.getY(), from.getZ(),
                    to.getYaw(), to.getPitch());
            event.setTo(locked);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Frozen entities cannot deal damage - in melee or with projectiles. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamageBy(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (frozen(damager)) {
            event.setCancelled(true);
            return;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter
                && frozen(shooter)) {
            event.setCancelled(true);
        }
    }

    /** The freeze itself should never hurt anyone. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFreezeDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FREEZE && frozen(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onShoot(EntityShootBowEvent event) {
        if (frozen(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (frozen(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        freeze.release(event.getPlayer());
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        freeze.forget(event.getEntity().getUniqueId());
    }
}
