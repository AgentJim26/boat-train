package club.anims.boattrain;

import io.papermc.paper.entity.Leashable;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards boat-train leads and remembers links for the relink safety net.
 *
 * <p>Train shapes covered:</p>
 * <ul>
 *   <li>one boat with 2 players, pulled by a lead (holder = player / fence knot / mob),</li>
 *   <li>a chain of boats where each boat is leashed to the next (holder = another boat),</li>
 *   <li>a player riding the front boat while holding the lead to the boat behind.</li>
 * </ul>
 */
public final class LeashGuard implements Listener {

    private final BoatTrainPlugin plugin;

    /** leashed entity UUID -> link (who holds it). */
    private final Map<UUID, Link> links = new ConcurrentHashMap<>();
    /** holder UUID -> last tick seen (used to resolve HOLDER_GONE restores). */
    private final Map<UUID, Entity> holderCache = new ConcurrentHashMap<>();

    LeashGuard(BoatTrainPlugin plugin) {
        this.plugin = plugin;
    }

    /** Small record of a leash link. */
    static final class Link {
        final UUID leashed;
        final UUID holder;
        final String holderKind;
        volatile long lastSeenTick;

        Link(UUID leashed, UUID holder, String holderKind) {
            this.leashed = leashed;
            this.holder = holder;
            this.holderKind = holderKind;
            this.lastSeenTick = tick();
        }

        static long tick() {
            return Bukkit.getCurrentTick();
        }
    }

    int trackedCount() {
        return links.size();
    }

    void clearStale() {
        links.entrySet().removeIf(e -> {
            Entity ent = Bukkit.getEntity(e.getKey());
            return ent == null || !ent.isValid();
        });
    }

    // ------------------------------------------------------------ helpers

    static boolean isBoatLike(Entity e) {
        // Boat, ChestBoat, rafts: all are Vehicle + Leashable in Paper.
        return e instanceof Vehicle && e instanceof Leashable;
    }

    static int playerPassengers(Entity e) {
        int n = 0;
        for (Entity p : e.getPassengers()) {
            if (p instanceof Player) {
                n++;
            }
        }
        return n;
    }

    /** True when this leashed entity should keep its lead. */
    boolean shouldProtect(Entity leashed, Entity holder) {
        if (!isBoatLike(leashed)) {
            return false;
        }
        if (plugin.getConfig().getBoolean("protect-all-boats", false)) {
            return true;
        }
        int min = plugin.getConfig().getInt("min-passengers-to-protect", 1);
        if (playerPassengers(leashed) >= min) {
            return true;
        }
        if (!plugin.getConfig().getBoolean("protect-chained-train", true)) {
            return false;
        }
        // Chained: holder is another occupied boat.
        if (holder != null && isBoatLike(holder) && playerPassengers(holder) > 0) {
            return true;
        }
        // Rider pull: holder is a player sitting in a boat.
        if (holder instanceof Player && holder.getVehicle() != null && isBoatLike(holder.getVehicle())) {
            return true;
        }
        // Reverse chain: some occupied boat nearby holds/goes to this one.
        // (Covers A->B->C where the middle boat just loaded and holder lookup lags.)
        return false;
    }

    private void remember(Entity leashed, Entity holder) {
        if (leashed == null || holder == null) {
            return;
        }
        String kind = holder instanceof Player ? "player"
                : holder instanceof LeashHitch ? "knot"
                : isBoatLike(holder) ? "boat"
                : holder.getType().name();
        links.put(leashed.getUniqueId(), new Link(leashed.getUniqueId(), holder.getUniqueId(), kind));
        holderCache.put(holder.getUniqueId(), holder);
        plugin.debug("remember lead: " + shortName(leashed) + " -> " + kind + " " + shortName(holder));
    }

    private void forget(Entity leashed) {
        if (leashed != null) {
            links.remove(leashed.getUniqueId());
        }
    }

    private static String shortName(Entity e) {
        return e.getType() + "@" + e.getUniqueId().toString().substring(0, 8);
    }

    // -------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        Entity leashed = event.getEntity();
        Entity holder = event.getLeashHolder();
        if (isBoatLike(leashed)) {
            remember(leashed, holder);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUnleash(EntityUnleashEvent event) {
        Entity leashed = event.getEntity();
        if (!isBoatLike(leashed)) {
            return;
        }

        boolean intentional = event instanceof PlayerUnleashEntityEvent
                || event.getReason() == EntityUnleashEvent.UnleashReason.PLAYER_UNLEASH;

        if (intentional && plugin.getConfig().getBoolean("allow-intentional-unleash", true)) {
            forget(leashed);
            plugin.debug("intentional unleash allowed: " + shortName(leashed));
            return;
        }

        Entity holder = holderOf(leashed);
        if (!shouldProtect(leashed, holder)) {
            // Not a train boat — let vanilla do its thing, drop tracking.
            return;
        }

        boolean guardThis;
        switch (event.getReason()) {
            case DISTANCE:
                guardThis = plugin.getConfig().getBoolean("protect-distance", true);
                break;
            case HOLDER_GONE:
                guardThis = plugin.getConfig().getBoolean("protect-holder-gone", true);
                break;
            default:
                guardThis = plugin.getConfig().getBoolean("protect-unknown", true);
                break;
        }
        if (!guardThis) {
            return;
        }

        // Keep the link alive. If the holder object is still there, cancelling
        // is enough. If it is gone (unloaded/dead), the relink task restores
        // the lead when both ends are loaded again.
        if (holder != null) {
            remember(leashed, holder);
        }
        event.setCancelled(true);
        if (plugin.getConfig().getBoolean("no-drop-on-guard", true)) {
            try {
                event.setDropLeash(false);
            } catch (NoSuchMethodError ignored) {
                // Very old API line without setDropLeash — cancel alone still holds.
            }
        }
        plugin.debug("guarded unleash (" + event.getReason() + "): " + shortName(leashed)
                + (holder == null ? " holder=? " : " holder=" + shortName(holder)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnter(VehicleEnterEvent event) {
        // A second player just sat down — the tick after this is when vanilla
        // usually pops the lead. Make sure we know the current holder now.
        if (isBoatLike(event.getVehicle())) {
            Entity holder = holderOf(event.getVehicle());
            if (holder != null) {
                remember(event.getVehicle(), holder);
                plugin.debug("rider entered " + shortName(event.getVehicle())
                        + " now " + playerPassengers(event.getVehicle()) + " player(s) aboard");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onChunkLoad(ChunkLoadEvent event) {
        // Opportunistic: when a chunk with train boats loads, the relink task
        // picks them up on its next sweep (5 ticks). Nothing to do here except
        // avoid a cold cache — the sweep scans live entities directly.
    }

    // -------------------------------------------------------------- relink

    private static Entity holderOf(Entity leashed) {
        if (leashed instanceof Leashable) {
            Leashable l = (Leashable) leashed;
            try {
                if (l.isLeashed()) {
                    return l.getLeashHolder();
                }
            } catch (IllegalStateException gone) {
                return null;
            }
        }
        return null;
    }

    /**
     * Scan all loaded boats, refresh tracking, and re-attach train leads that
     * came off. Returns the number re-attached this sweep.
     */
    int relinkAll() {
        if (!plugin.getConfig().getBoolean("relink-enabled", true)) {
            return 0;
        }
        double maxDist = plugin.getConfig().getDouble("relink-max-distance", 32.0);
        double maxSq = maxDist * maxDist;
        int fixed = 0;

        // 1. Refresh tracking from live leashes.
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (!isBoatLike(e)) {
                    continue;
                }
                Entity h = holderOf(e);
                if (h != null) {
                    remember(e, h);
                }
            }
        }

        // 2. Re-attach tracked links that are currently bare.
        for (Map.Entry<UUID, Link> e : links.entrySet()) {
            Entity leashed = Bukkit.getEntity(e.getKey());
            Link link = e.getValue();
            if (leashed == null || !leashed.isValid()) {
                links.remove(e.getKey());
                continue;
            }
            if (!(leashed instanceof Leashable)) {
                continue;
            }
            Leashable l = (Leashable) leashed;
            if (l.isLeashed()) {
                link.lastSeenTick = Link.tick();
                continue;
            }
            Entity holder = Bukkit.getEntity(link.holder);
            if (holder == null) {
                holder = holderCache.get(link.holder);
            }
            if (holder == null || !holder.isValid()) {
                continue;
            }
            if (!holder.getWorld().equals(leashed.getWorld())) {
                continue;
            }
            double distSq;
            try {
                distSq = holder.getLocation().distanceSquared(leashed.getLocation());
            } catch (IllegalArgumentException badWorld) {
                continue;
            }
            if (distSq > maxSq) {
                plugin.debug("relink skip (too far " + (int) Math.sqrt(distSq) + "m): " + shortName(leashed));
                continue;
            }
            if (!shouldProtect(leashed, holder)) {
                continue;
            }
            try {
                if (l.setLeashHolder(holder)) {
                    fixed++;
                    link.lastSeenTick = Link.tick();
                    plugin.debug("re-attached lead: " + shortName(leashed) + " -> " + shortName(holder));
                }
            } catch (Exception ex) {
                plugin.debug("relink failed for " + shortName(leashed) + ": " + ex.getMessage());
            }
        }
        return fixed;
    }
}
