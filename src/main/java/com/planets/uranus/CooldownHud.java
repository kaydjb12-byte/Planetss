package com.planets.uranus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns the ability cooldowns (one per player per ability) and shows them on the action bar,
 * just above the XP bar / hearts:
 *
 *   [icon] Uranus Freeze  ██████░░░░ 4.2s
 *   [icon] Earth Armor  ██████░░░░ 7.3s ACTIVE      (while an ability with a duration is running)
 *
 * The icon is a glyph from the resource pack (font planets:hud). Turn it off in config
 * ("hud.use-icon: false") for players without the pack.
 *
 * With several abilities on cooldown, the bar shows the one for the planet item the player is
 * holding, otherwise the one that takes longest to recharge.
 */
public final class CooldownHud {

    private static final Key FONT = Key.key("planets", "hud");
    private static final int REFRESH_TICKS = 2;
    private static final int READY_FLASH_TICKS = 30;

    /**
     * @param id           short id used in code, e.g. "uranus"
     * @param namePath     config path of the display name
     * @param defaultName  name used if the config has none
     * @param iconReady    resource-pack glyph for the ready state
     * @param iconCooldown resource-pack glyph for the recharging state
     * @param heldMatcher  true for the planet item that owns this ability
     */
    public record Ability(String id, String namePath, String defaultName, String iconReady, String iconCooldown,
                          Predicate<ItemStack> heldMatcher) {
    }

    private static final class State {
        final long start;
        final long end;
        /** 0 if the ability has no active phase. */
        final long activeUntil;

        State(long start, long end, long activeUntil) {
            this.start = start;
            this.end = end;
            this.activeUntil = activeUntil;
        }
    }

    private final UranusPlugin plugin;
    private final FreezeManager freeze;
    private final Map<String, Ability> abilities = new LinkedHashMap<>();
    private final Map<UUID, Map<String, State>> cooldowns = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> flashes = new HashMap<>();
    private BukkitTask task;

    public CooldownHud(UranusPlugin plugin, FreezeManager freeze) {
        this.plugin = plugin;
        this.freeze = freeze;
    }

    public void register(Ability ability) {
        abilities.put(ability.id(), ability);
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, REFRESH_TICKS, REFRESH_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        cooldowns.clear();
        flashes.clear();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("hud.enabled", true);
    }

    // ----------------------------------------------------------------- cooldown

    public long remainingMillis(UUID id, String abilityId) {
        Map<String, State> map = cooldowns.get(id);
        State state = map == null ? null : map.get(abilityId);
        if (state == null) {
            return 0L;
        }
        return Math.max(0L, state.end - System.currentTimeMillis());
    }

    /** Starts a cooldown with no active phase. */
    public void startCooldown(Player player, String abilityId, long cooldownMillis) {
        startCooldown(player, abilityId, cooldownMillis, 0L);
    }

    /** Starts a cooldown; the first {@code activeMillis} of it are shown as the ability being active. */
    public void startCooldown(Player player, String abilityId, long cooldownMillis, long activeMillis) {
        long now = System.currentTimeMillis();
        long end = now + Math.max(1L, cooldownMillis);
        long activeUntil = activeMillis > 0 ? now + activeMillis : 0L;
        cooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .put(abilityId, new State(now, end, activeUntil));
        Map<String, Integer> flash = flashes.get(player.getUniqueId());
        if (flash != null) {
            flash.remove(abilityId);
        }
    }

    // --------------------------------------------------------------------- tick

    private void tick() {
        boolean hud = enabled();
        long now = System.currentTimeMillis();

        Set<UUID> ids = new HashSet<>(cooldowns.keySet());
        ids.addAll(flashes.keySet());

        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            Map<String, State> cds = cooldowns.get(id);
            Map<String, Integer> fl = flashes.get(id);

            if (cds != null) {
                Iterator<Map.Entry<String, State>> it = cds.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, State> entry = it.next();
                    if (entry.getValue().end > now) {
                        continue;
                    }
                    it.remove();
                    if (hud && player != null) {
                        if (fl == null) {
                            fl = new HashMap<>();
                            flashes.put(id, fl);
                        }
                        fl.put(entry.getKey(), READY_FLASH_TICKS);
                        if (plugin.getConfig().getBoolean("hud.ready-sound", true)) {
                            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.4f);
                        }
                    }
                }
                if (cds.isEmpty()) {
                    cooldowns.remove(id);
                    cds = null;
                }
            }

            if (fl != null) {
                Iterator<Map.Entry<String, Integer>> it = fl.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Integer> entry = it.next();
                    int left = entry.getValue() - REFRESH_TICKS;
                    if (left <= 0 || player == null || (cds != null && cds.containsKey(entry.getKey()))) {
                        it.remove();
                    } else {
                        entry.setValue(left);
                    }
                }
                if (fl.isEmpty()) {
                    flashes.remove(id);
                    fl = null;
                }
            }

            // A frozen player sees the "frozen" message instead.
            if (!hud || player == null || freeze.isFrozen(id) || (cds == null && fl == null)) {
                continue;
            }
            render(player, cds, fl, now);
        }
    }

    private void render(Player player, Map<String, State> cds, Map<String, Integer> fl, long now) {
        String shown = null;

        // Prefer the ability of the planet item in hand.
        for (Ability ability : abilities.values()) {
            boolean has = (cds != null && cds.containsKey(ability.id())) || (fl != null && fl.containsKey(ability.id()));
            if (has && holding(player, ability)) {
                shown = ability.id();
                break;
            }
        }
        // Otherwise the cooldown that ends last.
        if (shown == null && cds != null) {
            long latest = Long.MIN_VALUE;
            for (Map.Entry<String, State> entry : cds.entrySet()) {
                if (entry.getValue().end > latest) {
                    latest = entry.getValue().end;
                    shown = entry.getKey();
                }
            }
        }
        if (shown == null && fl != null && !fl.isEmpty()) {
            shown = fl.keySet().iterator().next();
        }
        if (shown == null) {
            return;
        }

        Ability ability = abilities.get(shown);
        if (ability == null) {
            return;
        }
        State state = cds == null ? null : cds.get(shown);
        if (state == null) {
            player.sendActionBar(readyComponent(ability));
        } else if (state.activeUntil > now) {
            player.sendActionBar(activeComponent(ability, state, now));
        } else {
            player.sendActionBar(cooldownComponent(ability, state.end - now, state.end - state.start));
        }
    }

    private boolean holding(Player player, Ability ability) {
        return ability.heldMatcher().test(player.getInventory().getItemInMainHand())
                || ability.heldMatcher().test(player.getInventory().getItemInOffHand());
    }

    // -------------------------------------------------------------- components

    private String abilityName(Ability ability) {
        return plugin.getConfig().getString(ability.namePath(), ability.defaultName());
    }

    private int barLength() {
        return Math.max(4, Math.min(30, plugin.getConfig().getInt("hud.bar-length", 10)));
    }

    private Component icon(Ability ability, boolean cooling) {
        if (plugin.getConfig().getBoolean("hud.use-icon", true)) {
            return Component.text(cooling ? ability.iconCooldown() : ability.iconReady(), NamedTextColor.WHITE)
                    .font(FONT);
        }
        return Component.text("❄", cooling ? NamedTextColor.DARK_GRAY : NamedTextColor.AQUA);
    }

    private Component cooldownComponent(Ability ability, long remainingMillis, long totalMillis) {
        int length = barLength();
        double elapsed = totalMillis <= 0 ? 1.0 : 1.0 - ((double) remainingMillis / (double) totalMillis);
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, elapsed)) * length);
        String seconds = String.format(Locale.ROOT, "%.1fs", remainingMillis / 1000.0);

        return Component.text()
                .append(icon(ability, true))
                .append(Component.text(" " + abilityName(ability) + "  ", NamedTextColor.GRAY))
                .append(Component.text("█".repeat(filled), NamedTextColor.AQUA))
                .append(Component.text("█".repeat(length - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text(" " + seconds, NamedTextColor.WHITE))
                .build();
    }

    /** Shown while the ability itself is running: the bar drains as the effect runs out. */
    private Component activeComponent(Ability ability, State state, long now) {
        int length = barLength();
        long remaining = Math.max(0L, state.activeUntil - now);
        long total = Math.max(1L, state.activeUntil - state.start);
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, (double) remaining / (double) total)) * length);
        String seconds = String.format(Locale.ROOT, "%.1fs", remaining / 1000.0);

        return Component.text()
                .append(icon(ability, false))
                .append(Component.text(" " + abilityName(ability) + "  ", NamedTextColor.GREEN))
                .append(Component.text("█".repeat(filled), NamedTextColor.GREEN))
                .append(Component.text("█".repeat(length - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text(" " + seconds, NamedTextColor.WHITE))
                .append(Component.text(" ACTIVE", NamedTextColor.GREEN, TextDecoration.BOLD))
                .build();
    }

    private Component readyComponent(Ability ability) {
        return Component.text()
                .append(icon(ability, false))
                .append(Component.text(" " + abilityName(ability) + "  ", NamedTextColor.AQUA))
                .append(Component.text("READY", NamedTextColor.GREEN, TextDecoration.BOLD))
                .build();
    }
}
