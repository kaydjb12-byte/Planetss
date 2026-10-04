package com.planets.uranus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.inventory.ItemStack;

/**
 * All planet items the plugin knows about. /planets give and /planets remove work off this list,
 * so adding a new planet later is a single register(...) call in UranusPlugin.onEnable.
 */
public final class PlanetRegistry {

    /**
     * @param id          lowercase name used in commands, e.g. "uranus"
     * @param displayName name shown in messages, e.g. "Uranus"
     * @param factory     creates a fresh item
     * @param matcher     true if a stack is this planet's item
     */
    public record Planet(String id, String displayName, Supplier<ItemStack> factory, Predicate<ItemStack> matcher) {
    }

    private final Map<String, Planet> planets = new LinkedHashMap<>();

    public void register(Planet planet) {
        planets.put(planet.id().toLowerCase(Locale.ROOT), planet);
    }

    public Planet get(String id) {
        return id == null ? null : planets.get(id.toLowerCase(Locale.ROOT));
    }

    public List<String> ids() {
        return new ArrayList<>(planets.keySet());
    }
}
