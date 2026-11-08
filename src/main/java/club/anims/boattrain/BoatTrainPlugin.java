package club.anims.boattrain;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * BoatTrain: keeps boat-train leads attached when multiple players ride.
 *
 * <p>Vanilla breaks a boat's lead as soon as a second player piles in (the
 * boat shifts, the distance check trips, the lead pops). This plugin guards
 * the {@code EntityUnleashEvent} for occupied boats and re-attaches any lead
 * that still comes off, so a boat train survives 2+ riders.</p>
 *
 * <p>Pure Paper API, no NMS, no reflection.</p>
 */
public final class BoatTrainPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private LeashGuard guard;
    private RelinkTask relinkTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.guard = new LeashGuard(this);
        Bukkit.getPluginManager().registerEvents(guard, this);

        long period = Math.max(1L, getConfig().getLong("relink-period-ticks", 5L));
        this.relinkTask = new RelinkTask(this, guard);
        this.relinkTask.runTaskTimer(this, period, period);

        if (getCommand("boattrain") != null) {
            getCommand("boattrain").setExecutor(this);
            getCommand("boattrain").setTabCompleter(this);
        }
        getLogger().info("BoatTrain enabled: boat-train leads will survive multi-rider boats.");
    }

    @Override
    public void onDisable() {
        if (relinkTask != null) {
            try {
                relinkTask.cancel();
            } catch (IllegalStateException ignored) {
                // Already cancelled during reload/shutdown.
            }
        }
        getLogger().info("BoatTrain disabled.");
    }

    public void debug(String msg) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[debug] " + msg);
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("boattrain")) {
            return false;
        }
        if (!sender.hasPermission("boattrain.admin")) {
            sender.sendMessage("§cYou need boattrain.admin for that.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "reload":
                reloadConfig();
                guard.clearStale();
                sender.sendMessage("§aBoatTrain config reloaded. Tracked links: " + guard.trackedCount());
                return true;
            case "relink":
                int n = guard.relinkAll();
                sender.sendMessage("§aBoatTrain relink sweep: re-attached " + n + " lead(s). Tracked: " + guard.trackedCount());
                return true;
            case "debug":
                boolean cur = getConfig().getBoolean("debug", false);
                getConfig().set("debug", !cur);
                saveConfig();
                sender.sendMessage("§aBoatTrain debug " + (!cur ? "on" : "off") + ".");
                return true;
            case "status":
            default:
                sender.sendMessage("§bBoatTrain: §fguard active, tracked links: §b" + guard.trackedCount()
                        + "§f, relink " + (getConfig().getBoolean("relink-enabled", true) ? "§aon" : "§coff"));
                sender.sendMessage("§7/boattrain reload|status|relink|debug");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length <= 1) {
            return Arrays.asList("status", "reload", "relink", "debug");
        }
        return Collections.emptyList();
    }
}
