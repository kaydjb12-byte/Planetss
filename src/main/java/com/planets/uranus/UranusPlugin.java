package com.planets.uranus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

public final class UranusPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final PlanetRegistry planets = new PlanetRegistry();
    private UranusItem uranusItem;
    private EarthItem earthItem;
    private FreezeManager freezeManager;
    private FrostArmor frostArmor;
    private EarthArmor earthArmor;
    private EarthGrounding earthGrounding;
    private CooldownHud cooldownHud;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Add any config keys introduced by updates without touching the ones already set.
        getConfig().options().copyDefaults(true);
        saveConfig();

        uranusItem = new UranusItem(this);
        earthItem = new EarthItem(this);
        // Register every planet here. /planets give|remove picks them up automatically.
        planets.register(new PlanetRegistry.Planet("uranus", "Uranus", uranusItem::create, uranusItem::isUranus));
        planets.register(new PlanetRegistry.Planet("earth", "Earth", earthItem::create, earthItem::isEarth));

        freezeManager = new FreezeManager(this);
        freezeManager.start();
        getServer().getPluginManager().registerEvents(new FreezeListener(freezeManager), this);

        frostArmor = new FrostArmor(this, uranusItem, freezeManager);
        frostArmor.start();
        getServer().getPluginManager().registerEvents(frostArmor, this);

        earthArmor = new EarthArmor(this);
        earthArmor.start();
        getServer().getPluginManager().registerEvents(earthArmor, this);

        earthGrounding = new EarthGrounding(this, earthItem);
        earthGrounding.start();
        getServer().getPluginManager().registerEvents(earthGrounding, this);

        cooldownHud = new CooldownHud(this, freezeManager);
        cooldownHud.register(new CooldownHud.Ability("uranus", "hud.ability-name", "Uranus Freeze",
                "", "", uranusItem::isUranus));
        cooldownHud.register(new CooldownHud.Ability("earth", "hud.earth-ability-name", "Earth Armor",
                "", "", earthItem::isEarth));
        cooldownHud.start();

        Objects.requireNonNull(getCommand("uranus1")).setExecutor(this);
        Objects.requireNonNull(getCommand("earth1")).setExecutor(this);
        Objects.requireNonNull(getCommand("planets")).setExecutor(this);
        Objects.requireNonNull(getCommand("planets")).setTabCompleter(this);

        getLogger().info("PlanetsUranus enabled (planets: " + String.join(", ", planets.ids()) + ").");
    }

    @Override
    public void onDisable() {
        if (cooldownHud != null) {
            cooldownHud.stop();
        }
        if (earthGrounding != null) {
            earthGrounding.stop();
        }
        if (earthArmor != null) {
            earthArmor.stop();
        }
        if (frostArmor != null) {
            frostArmor.stop();
        }
        if (freezeManager != null) {
            freezeManager.stop();
        }
    }

    /** Namespaced key owned by this plugin. */
    public NamespacedKey key(String name) {
        return new NamespacedKey(this, name);
    }

    /** Reads a MiniMessage string from config.yml. */
    public Component message(String key) {
        return message(key, Map.of());
    }

    public Component message(String key, Map<String, String> placeholders) {
        String raw = getConfig().getString("messages." + key, "");
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return MINI.deserialize(raw);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "uranus1":
                return handleUranus1(sender);
            case "earth1":
                return handleEarth1(sender);
            case "planets":
                return handlePlanets(sender, args);
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- /uranus1

    private boolean handleUranus1(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(message("players-only"));
            return true;
        }

        boolean requireItem = getConfig().getBoolean("require-item", true);
        if (requireItem
                && !uranusItem.isUranus(player.getInventory().getItemInMainHand())
                && !uranusItem.isUranus(player.getInventory().getItemInOffHand())) {
            player.sendMessage(message("need-item"));
            return true;
        }
        // A frozen player can't use abilities.
        if (freezeManager.isFrozen(player.getUniqueId())) {
            return true;
        }

        long remaining = cooldownHud.remainingMillis(player.getUniqueId(), "uranus");
        if (remaining > 0) {
            // With the HUD on, the timer is already on the action bar - keep chat quiet.
            if (!cooldownHud.enabled()) {
                long secondsLeft = (remaining + 999) / 1000;
                player.sendMessage(message("cooldown", Map.of("seconds", String.valueOf(secondsLeft))));
            }
            return true;
        }
        long cooldownMillis = (long) (getConfig().getDouble("cooldown-seconds", 25.0) * 1000.0);

        freezeManager.cast(player);
        cooldownHud.startCooldown(player, "uranus", cooldownMillis);
        if (!cooldownHud.enabled()) {
            player.sendActionBar(message("cast"));
        }
        return true;
    }

    // ----------------------------------------------------------------- /earth1

    private boolean handleEarth1(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(message("players-only"));
            return true;
        }

        if (getConfig().getBoolean("earth.require-item", true) && !earthItem.isHolding(player)) {
            player.sendMessage(message("need-item-earth"));
            return true;
        }
        if (freezeManager.isFrozen(player.getUniqueId())) {
            return true;
        }

        long remaining = cooldownHud.remainingMillis(player.getUniqueId(), "earth");
        if (remaining > 0) {
            if (!cooldownHud.enabled()) {
                long secondsLeft = (remaining + 999) / 1000;
                player.sendMessage(message("cooldown", Map.of("seconds", String.valueOf(secondsLeft))));
            }
            return true;
        }
        long cooldownMillis = (long) (getConfig().getDouble("earth.cooldown-seconds", 45.0) * 1000.0);
        long activeMillis = (long) (getConfig().getDouble("earth.duration-seconds", 10.0) * 1000.0);

        earthArmor.activate(player);
        cooldownHud.startCooldown(player, "earth", cooldownMillis, activeMillis);
        if (!cooldownHud.enabled()) {
            player.sendActionBar(message("earth-cast"));
        }
        return true;
    }

    // ---------------------------------------------------------------- /planets

    private boolean handlePlanets(CommandSender sender, String[] args) {
        // plugin.yml already limits the command to ops; this is a second lock in case a
        // permissions plugin changes the default.
        if (!sender.hasPermission("planets.admin")) {
            sender.sendMessage(message("no-permission"));
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> handleGive(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "list" -> sender.sendMessage(message("list", Map.of("planets", String.join(", ", planets.ids()))));
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(message("reloaded"));
            }
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(message("usage", Map.of("planets", String.join("|", planets.ids()))));
    }

    /** Works out who a give/remove applies to: the named player, or the sender. Null = already told the sender why not. */
    private Player resolveTarget(CommandSender sender, String[] args) {
        if (args.length >= 3) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(message("no-player"));
            }
            return target;
        }
        if (sender instanceof Player self) {
            return self;
        }
        sender.sendMessage(message("players-only"));
        return null;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }
        PlanetRegistry.Planet planet = planets.get(args[1]);
        if (planet == null) {
            sender.sendMessage(message("unknown-planet", Map.of("planets", String.join(", ", planets.ids()))));
            return;
        }
        Player target = resolveTarget(sender, args);
        if (target == null) {
            return;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(planet.factory().get());
        for (ItemStack extra : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), extra);
        }
        target.sendMessage(message("planet-given", Map.of("planet", planet.displayName())));
        if (target != sender) {
            sender.sendMessage(message("planet-given-other",
                    Map.of("planet", planet.displayName(), "player", target.getName())));
        }
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }
        PlanetRegistry.Planet planet = planets.get(args[1]);
        if (planet == null) {
            sender.sendMessage(message("unknown-planet", Map.of("planets", String.join(", ", planets.ids()))));
            return;
        }
        Player target = resolveTarget(sender, args);
        if (target == null) {
            return;
        }

        int removed = removeFromInventory(target, planet);
        if (removed == 0) {
            sender.sendMessage(message("nothing-to-remove",
                    Map.of("planet", planet.displayName(), "player", target.getName())));
            return;
        }
        Map<String, String> placeholders = Map.of(
                "planet", planet.displayName(),
                "player", target.getName(),
                "amount", String.valueOf(removed));
        target.sendMessage(message("planet-removed", placeholders));
        if (target != sender) {
            sender.sendMessage(message("planet-removed-other", placeholders));
        }
    }

    /** Takes every copy of the planet's item out of the player's inventory, armor and off hand. */
    private int removeFromInventory(Player player, PlanetRegistry.Planet planet) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        int removed = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack != null && planet.matcher().test(stack)) {
                removed += stack.getAmount();
                contents[i] = null;
            }
        }
        if (removed > 0) {
            inventory.setContents(contents);
        }
        return removed;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!command.getName().equalsIgnoreCase("planets") || !sender.hasPermission("planets.admin")) {
            return out;
        }
        if (args.length == 1) {
            addMatches(out, List.of("give", "remove", "list", "reload"), args[0]);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("remove"))) {
            addMatches(out, planets.ids(), args[1]);
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("remove"))) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
            }
            addMatches(out, names, args[2]);
        }
        return out;
    }

    private static void addMatches(List<String> out, List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(option);
            }
        }
    }
}
