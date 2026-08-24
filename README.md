# Graves

A Fabric mod that places a grave where you die, holding your inventory and XP
so you can come back and recover everything instead of losing it to a timer or
a mob.

Inspired by the classic Vanilla Tweaks Graves datapack.

## Commands

- `/graves list` lists your active graves with their coordinates.
- `/graves locate` points you to your nearest grave.
- `/graves key` hands you a Grave Key, a marked tripwire hook that opens any
  grave. Requires operator level 2.
- `/graves config` shows the current config. Requires operator level 2.
- `/graves config get` shows the current config.
- `/graves config set <key> <value>` sets one option live.

## Configuration

All options live in `config/graves.json` next to the other mod configs. Change
the file and restart, set them live with `/graves config set`, or edit them from
the Mods screen via ModMenu in singleplayer. Defaults mirror the original
datapack:

- `allow_robbing` (false): whether players other than the owner may open a grave.
- `pick_up_xp` (true): capture the death XP into the grave. When false the XP
  orbs stay where you died.
- `allow_locating` (true): whether `/graves locate` works. Defaults to the
  reducedDebugInfo gamerule, but can be pinned in the config file.
- `compatibility_mode` (false): makes the mod passive so a datapack version can
  take over.
- `despawn_seconds` (0): seconds until a grave decays and spills its contents on
  the ground. 0 disables despawning.

## Details

- Runs on the server. The integrated server in singleplayer also works.
- Clients do not need the mod installed.
- In singleplayer, the config can also be edited from the Mods screen (ModMenu
  is optional; install it to get the config button).
- A grave spawns only when keepInventory is off and you died with items or XP.
- Opening a grave is all-at-once, like the datapack: every item is handed back
  to its original slot (or the first free slot), anything that does not fit
  drops on the ground, and the grave disappears.
- With `allow_robbing` off, non-owners are refused entirely. With it on, a
  robber takes both the items and the XP.
- Graves persist to `<world>/data/graves.json` and survive restarts.
- Graves that fall into the void get a small cobblestone platform to rest on.

## Requirements

- Fabric Loader 0.19.3 or newer for Minecraft 26.1.2.
- Fabric API for 26.1.2.
- Java 25.

## Building

JDK 25 and Gradle 9.7 or newer (Loom 1.17.19).

```bash
./gradlew build
```

The jar is in `build/libs/`.

## License

GPL-3.0-or-later
