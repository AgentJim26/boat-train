# BoatTrain 1.1.0 — Paper plugin for 26.3 boat trains

Keeps boat-train leads attached when more than 1 player rides, and tows
trailers so they actually follow the leader.

## The bug

Vanilla pops a boat's lead as soon as a second player sits down (the boat
shifts, the `DISTANCE` leash check trips, `EntityUnleashEvent` fires and the
lead drops). So a "boat train" pulled by leads falls apart the moment you put
2 riders in it.

## The fix (no NMS, pure Paper API)

- Cancels `EntityUnleashEvent` for train boats (`DISTANCE` / `HOLDER_GONE` /
  `UNKNOWN`) and sets `dropLeash=false` so no lead items dupe.
- Leaves intentional removal alone: a player right-clicking the lead off
  (`PLAYER_UNLEASH`) still works.
- Safety net: a 5-tick relink task remembers `boat -> holder` links and
  re-attaches any lead that still came off (chunk unload, restart, vanilla
  force-unleash), within `relink-max-distance` (default 32m).
- Train-aware: protects a leashed boat with ≥1 player aboard, a boat chained
  to another occupied boat, or a boat pulled by a player riding a boat.
- **Towing (1.1.0):** vanilla leads only *tether* boats — unlike mobs, boats
  get zero pull force, so an occupied trailer just sits dead in the water.
  A 2-tick tow task blends pull velocity toward the holder (60% pull, 40%
  current motion so a driver in the trailer keeps partial steering) and
  snaps trailers that fall >20m behind back to ~2.5m behind the holder
  (riders stay aboard). Tune with the `tow-*` keys in `config.yml`.

## Layout

```
boat-train-plugin/
  pom.xml                  # maven build (paper-api 1.21.1 baseline, same as WaterFrames module)
  src/main/java/club/anims/boattrain/
    BoatTrainPlugin.java   # enable, /boattrain command
    LeashGuard.java        # guard + link tracker + relink sweep
    RelinkTask.java        # 5-tick timer
  src/main/resources/
    plugin.yml
    config.yml
  target/boat-train-1.0.0.jar   # drop into plugins/
```

Built with `javac --release 21` against
`paper-api-1.21.1-R0.1-SNAPSHOT` (same baseline as the WaterFrames paper
module). TODO(26.3): re-pin `pom.xml` to the published 26.3 Paper API artifact
when it lands and recompile — no code changes expected, everything used here
(`Leashable`, `EntityUnleashEvent`, `VehicleEnterEvent`) is stable public API.

## Install

1. Drop `target/boat-train-1.0.0.jar` into the server's `plugins/` folder.
2. Restart (or `/reload` — restart preferred).
3. Test:
   - Leash boat A to a fence / player.
   - Player 1 sits in boat A, player 2 sits in the same boat (or the boat behind it).
   - Lead should stay. Ride around — it should stretch, not pop.
   - Right-click the lead off → it still comes off (intentional unleash allowed).
4. Tune in `plugins/BoatTrain/config.yml`:
   - `min-passengers-to-protect: 1` (set to 2 to match the exact "2 in one boat" case only)
   - `protect-chained-train: true`
   - `relink-max-distance: 32.0`
   - `debug: true` for `[debug]` logs

## Commands

- `/boattrain status` — tracked links + relink state
- `/boattrain relink` — force a relink sweep now
- `/boattrain reload` — reload config
- `/boattrain debug` — toggle debug logs

Permission: `boattrain.admin` (default op).
