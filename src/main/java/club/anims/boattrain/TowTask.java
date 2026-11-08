package club.anims.boattrain;

import io.papermc.paper.entity.Leashable;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Active towing for boat trains.
 *
 * <p>Vanilla leads on boats only tether — unlike mobs, boats get no pull
 * force from a lead, so a trailer with riders just sits dead in the water
 * while the guard holds the rope. This task adds the missing pull: every
 * sweep, each leashed occupied boat gets blended velocity toward its holder,
 * and trailers that fall far behind are snapped back into range (riders stay
 * aboard — Bukkit carries passengers through vehicle teleports).</p>
 *
 * <p>Pure Paper API, no NMS.</p>
 */
final class TowTask extends BukkitRunnable {

    private final BoatTrainPlugin plugin;

    TowTask(BoatTrainPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        try {
            if (!plugin.getConfig().getBoolean("tow-enabled", true)) {
                return;
            }
            double start = plugin.getConfig().getDouble("tow-start-distance", 3.0);
            double maxSpeed = plugin.getConfig().getDouble("tow-max-speed", 1.2);
            double teleportAt = plugin.getConfig().getDouble("tow-teleport-distance", 20.0);
            for (World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntities()) {
                    towOne(e, start, maxSpeed, teleportAt);
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("BoatTrain tow sweep failed: " + ex.getMessage());
        }
    }

    private void towOne(Entity boat, double start, double maxSpeed, double teleportAt) {
        if (!LeashGuard.isBoatLike(boat)) {
            return;
        }
        if (LeashGuard.playerPassengers(boat) == 0
                && !plugin.getConfig().getBoolean("protect-all-boats", false)) {
            return;
        }
        Leashable leash;
        try {
            leash = (Leashable) boat;
            if (!leash.isLeashed()) {
                return;
            }
        } catch (IllegalStateException gone) {
            return;
        }
        Entity holder;
        try {
            holder = leash.getLeashHolder();
        } catch (IllegalStateException gone) {
            return;
        }
        if (holder == null || !holder.isValid() || !holder.getWorld().equals(boat.getWorld())) {
            return;
        }

        Location bl = boat.getLocation();
        Location hl = holder.getLocation();
        double dx = hl.getX() - bl.getX();
        double dz = hl.getZ() - bl.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < start) {
            return; // slack in the rope — leave the boat alone
        }

        if (dist > teleportAt) {
            // Fell far behind (chunk load, teleport of the leader): snap the
            // trailer back into range instead of dragging it across the map.
            double back = Math.max(2.5, start);
            double nx = hl.getX() - dx / dist * back;
            double nz = hl.getZ() - dz / dist * back;
            Location nl = new Location(bl.getWorld(), nx, bl.getY(), nz, bl.getYaw(), bl.getPitch());
            boat.teleport(nl);
            plugin.debug("tow snap: trailer back in range (" + (int) dist + "m was)");
            return;
        }

        // Blend tow pull with current motion so a driver in the trailer who is
        // actively steering keeps partial control instead of being overridden.
        double speed = Math.min(0.25 + (dist - start) * 0.3, Math.max(0.1, maxSpeed));
        double vx = dx / dist * speed;
        double vz = dz / dist * speed;
        Vector cur = boat.getVelocity();
        boat.setVelocity(new Vector(
                cur.getX() * 0.4 + vx * 0.6,
                cur.getY(), // buoyancy/physics owns Y
                cur.getZ() * 0.4 + vz * 0.6));
    }
}
