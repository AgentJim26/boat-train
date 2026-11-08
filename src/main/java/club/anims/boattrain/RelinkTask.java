package club.anims.boattrain;

import org.bukkit.scheduler.BukkitRunnable;

/** Periodic safety net: re-attach train leads that vanilla still popped. */
final class RelinkTask extends BukkitRunnable {

    private final BoatTrainPlugin plugin;
    private final LeashGuard guard;

    RelinkTask(BoatTrainPlugin plugin, LeashGuard guard) {
        this.plugin = plugin;
        this.guard = guard;
    }

    @Override
    public void run() {
        try {
            int fixed = guard.relinkAll();
            if (fixed > 0) {
                plugin.getLogger().info("BoatTrain re-attached " + fixed + " lead(s).");
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("BoatTrain relink sweep failed: " + ex.getMessage());
        }
    }
}
