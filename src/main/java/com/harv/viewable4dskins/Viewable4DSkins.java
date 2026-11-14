package com.harv.viewable4dskins;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Viewable 4D Skins
 *
 * Thin wrapper around FreeMinecraftModels' disguise system. Players pick a skin,
 * the choice is saved, and it is re-applied when they rejoin. FreeMinecraftModels
 * (with ResourcePackManager) handles what Java and Bedrock players actually see.
 */
public final class Viewable4DSkins extends JavaPlugin implements Listener, TabExecutor {

    private static final String PREFIX_TEXT = "[Skins] ";

    /** lowercase skin name -> FreeMinecraftModels model id */
    private final Map<String, String> skins = new LinkedHashMap<>();

    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSkins();

        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        PluginCommand command = getCommand("skin");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        if (!fmmPresent()) {
            getLogger().warning("FreeMinecraftModels was not found. Install it, or skins will not work.");
        }
    }

    private void loadSkins() {
        skins.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("skins");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            String modelId = section.getString(key);
            if (modelId != null && !modelId.isBlank()) {
                skins.put(key.toLowerCase(Locale.ROOT), modelId.trim());
            }
        }
    }

    private boolean fmmPresent() {
        return Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels");
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            send(sender, "Usage: /skin <name|list|off>", NamedTextColor.YELLOW);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> listSkins(sender);
            case "reload" -> {
                if (!sender.hasPermission("viewable4dskins.reload")) {
                    send(sender, "You do not have permission to do that.", NamedTextColor.RED);
                    return true;
                }
                reloadConfig();
                loadSkins();
                send(sender, "Reloaded. " + skins.size() + " skin(s) available.", NamedTextColor.GREEN);
            }
            case "off" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "Only players can use this.", NamedTextColor.RED);
                    return true;
                }
                removeSkin(player);
            }
            default -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "Only players can use this.", NamedTextColor.RED);
                    return true;
                }
                chooseSkin(player, sub);
            }
        }
        return true;
    }

    private void listSkins(CommandSender sender) {
        if (skins.isEmpty()) {
            send(sender, "No skins are available yet.", NamedTextColor.YELLOW);
            return;
        }
        send(sender, "Available skins: " + String.join(", ", skins.keySet()), NamedTextColor.AQUA);
    }

    private void chooseSkin(Player player, String name) {
        if (!fmmPresent()) {
            send(player, "Skins are unavailable right now. Ask an admin.", NamedTextColor.RED);
            return;
        }
        String modelId = skins.get(name);
        if (modelId == null) {
            send(player, "Unknown skin '" + name + "'. Use /skin list.", NamedTextColor.RED);
            return;
        }

        saveChoice(player.getUniqueId(), name);
        applySkin(player, modelId);
        send(player, "You are now wearing the " + name + " skin.", NamedTextColor.GREEN);
    }

    private void removeSkin(Player player) {
        if (!fmmPresent()) {
            send(player, "Skins are unavailable right now. Ask an admin.", NamedTextColor.RED);
            return;
        }
        saveChoice(player.getUniqueId(), null);
        runConsole("fmm undisguise " + player.getName());
        send(player, "Skin removed.", NamedTextColor.GREEN);
    }

    // ---------------------------------------------------------------- applying

    /** Undisguise first so switching between skins is clean, then disguise a moment later. */
    private void applySkin(Player player, String modelId) {
        runConsole("fmm undisguise " + player.getName());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                runConsole("fmm disguise " + modelId + " " + player.getName());
            }
        }, 2L);
    }

    private void runConsole(String commandLine) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandLine);
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String saved = data.getString(player.getUniqueId().toString());
        if (saved == null) {
            return;
        }
        // Wait a few seconds so Geyser/Floodgate and the resource pack are ready.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline() || !fmmPresent()) {
                return;
            }
            String modelId = skins.get(saved);
            if (modelId != null) {
                applySkin(player, modelId);
            }
        }, 60L);
    }

    // ----------------------------------------------------------------- storage

    private void saveChoice(UUID uuid, String skinName) {
        data.set(uuid.toString(), skinName);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    // --------------------------------------------------------------- tab / misc

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>(skins.keySet());
            options.add("list");
            options.add("off");
            if (sender.hasPermission("viewable4dskins.reload")) {
                options.add("reload");
            }
            for (String option : options) {
                if (option.startsWith(typed)) {
                    out.add(option);
                }
            }
        }
        return out;
    }

    private void send(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(PREFIX_TEXT, NamedTextColor.GOLD)
                .append(Component.text(text, color)));
    }
}
