# Fahare

Fahare (short for **Fa**st **Ha**rdcore **Re**set) is a Minecraft: Java Edition multiplayer server mod that
automatically resets your hardcore world when all online players die. This fork is built for Paper 26.3+
(upstream [qixils/Fahare](https://github.com/qixils/Fahare) supports Paper 26.1+ since v1.4.0) and is versioned
`<mc major>.<mc minor>.<build>`. On top of upstream it adds a game over sequence (announcement, explosion at every
player, on-screen countdown, then the reset), a world age boss bar with a persistent dragon-kill record, and a
hardcore warning on join; all of it is optional and configured below.

By default, the mod is configured to a fairly normal hardcore experience,
though note that for the full experience you should change `difficulty` in your `server.properties` to `hard`.
Together with `auto-reset: true` and `lives: 1` (the defaults) this makes the play world hardcore, so players
get hardcore hearts and a proper hardcore experience.

## How a game over plays out

1. A player dies. Everyone is told who it was in chat and on screen, with a thunder clap.
2. An explosion goes off at every player, the victim's crater included. Anyone still standing afterwards is
   killed too, so everyone spectates their own crater.
3. After `game-over.delay` seconds the worlds regenerate; the last `game-over.countdown` seconds are counted
   down on screen. Nobody is kicked: everyone wakes up at the spawn of the new world in survival with a fresh
   inventory, and the world age starts again at day 0.

The world age boss bar counts one day per dawn in the current world. Sleeping through a night counts as a
day, and with `world-age.freeze-when-empty` the clock pauses while nobody is online. Killing the ender dragon
records the fewest days ever needed, kept in `plugins/Fahare/world-age.yml` across resets and shown on the bar
as `[⭐N]`. Upgrading from the Skript-based setup this replaces: a `world.dragon_record` found in
`plugins/Skript/variables.csv` is imported once, and the Skript's vanilla boss bar `minecraft:world_age` is
removed.

## Configuration

| Setting      | Default | Description                                                                                                                                                                          |
|--------------|---------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `backup`     | `true`  | Whether to save and backup worlds before resetting.<br>Backups go to `fahare-backups/<date>-<n>/` next to the server jar and include the worlds and the level's `players/` folder.<br>When disabled, worlds will be permanently deleted upon reset (much faster). |
| `auto-reset` | `true`  | Whether resets should happen automatically upon the death of all players.<br>When disabled, you may instead run `/fahare reset` to manually reset the world.                         |
| `any-death`  | `false` | Whether to trigger a reset upon *any* death.<br>When disabled, automatic resets will trigger only when all players are dead.<br>This setting is ignored if `auto-reset` is disabled. |
| `lives`      | `1`     | How many lives a player should have before they are considered dead.                                                                                                                 |
| `game-over.delay` | `30` | Seconds between the fatal death and the reset. `0` resets immediately (upstream behaviour). |
| `game-over.countdown` | `5` | Seconds of on-screen countdown right before the reset (at most `delay`). `0` disables it. |
| `game-over.explosion-power` | `40` | Power of the explosion set off at every player when the game ends (a powered creeper is 40). `0` disables it. Survivors are killed anyway so that everyone spectates their own crater until the reset. |
| `world-age.enabled` | `true` | Boss bar with the days spent in the current world (one per dawn, read from the play world's clock; sleeping counts) and the best dragon kill, kept in `plugins/Fahare/world-age.yml` across resets. |
| `world-age.freeze-when-empty` | `true` | Pause the day/night cycle of the play world while nobody is online, so days only pass while someone plays. |
| `join-warning.enabled` | `true` | Send a hardcore warning to players a second after they join. |
| `join-warning.name` | `THIS WORLD` | The name shown in that warning ("YOU ARE IN ..."). |

## Commands

| Command         | Permission     | Description               |
|-----------------|----------------|---------------------------|
| `/fahare reset` | `fahare.reset` | Manually reset the world. |
| `/fahare days` | `fahare.worldage` | Show the world age and the dragon-kill record. |
| `/fahare days <n>` | `fahare.worldage` | Set the world age to `n` days (keeps the time of day). |
| `/fahare record <n>` | `fahare.worldage` | Set the dragon-kill record to `n` days. |
| `/fahare record clear` | `fahare.worldage` | Clear the dragon-kill record. |
