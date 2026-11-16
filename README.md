# MC Addons

**Write Minecraft mods as safe, hot-reloadable Lua plugins – and install community plugins from an in-game browser.**

* **Lua 5.2 plugins** (pure-Java [Cobalt](https://github.com/cc-tweaked/Cobalt) VM, no native code).
* **Hot reload** – save `main.lua` and the plugin reloads within 2 seconds. No restart.
* **Secure sandbox** – no `io`, `os`, `debug`, `load`, no Java access, per-call instruction budget + timeout, declared permissions, path-checked `require`.
* **Plugin browser** – press **K** (or *Plugins* on the title/pause screen). Index comes from
  [RandomPixelStudios/McAddons-Plugins](https://github.com/RandomPixelStudios/McAddons-Plugins); files are SHA-256 verified.
* **Version independent** – plugins only talk to the stable `mc.*` API (versioned separately, currently **1.1**).
  Each Minecraft version gets a small adapter module (`fabric-26.3/`); plugins never touch Minecraft classes, so the same plugin runs everywhere an adapter exists.
* **Real content** – plugins can add items and blocks (declared in `plugin.json`), HUD overlays, key bindings, commands, world edits, events with cancellation.

Currently: **Minecraft 26.3, Fabric Loader ≥ 0.20.1, Fabric API, Java 25.**

## Install

Drop `mcaddons-fabric-26.3-<version>.jar` (from Releases or `./gradlew build` → `fabric-26.3/build/libs/`) and Fabric API into `mods/`.
Plugins live in `.minecraft/mcaddons/plugins/<id>/`.

| Where | How |
|---|---|
| In game | **K** → Browse → Install (shows permissions first) / Enable / Disable / Reload / Remove |
| Server console / ops | `/mca list`, `/mca browse`, `/mca install <id>`, `/mca enable|disable|reload|remove <id>`, `/mca new <id>` |
| Client plugin commands | `/pc <command> [args]` |

## Built-in block hiding (X-ray)

Press **X** (or *Block hiding* in the plugin browser). Same technique as X To Xray: chunk meshes are rebuilt and blocks are swapped for air by a mixin (Sodium supported).

* **Mode X-ray:** only listed blocks stay visible (default: ores, chests, spawners, water, lava).
* **Mode hide:** listed blocks become invisible (e.g. grass, leaves).
* Lists are edited in the settings screen (add any block id).
* **Singleplayer:** always allowed. **Servers:** only if the server runs MC Addons and the admin allows it:
  `/mca blockhiding on` (everyone), `/mca blockhiding ops` (operators only), `/mca blockhiding off` (default) –
  stored in `mcaddons/server-config.json`. On servers without permission it stays off.

## Your first plugin

`/mca new hello` creates `mcaddons/plugins/hello/`:

```json
{
  "id": "hello", "name": "Hello", "version": "1.0.0", "api": "1.0",
  "side": "server",
  "description": "Says hi",
  "permissions": ["chat", "commands"]
}
```

```lua
mc.on("player_join", function(e) mc.broadcast("Hi " .. e.player) end)
mc.command("hi", function(ctx) return "Hello " .. ctx.sender end)
```

Edit, save → reloaded. Publish with a pull request to the registry repo.

## Lua API

`side`: `"server"` (logic, world, commands – also the integrated server in singleplayer), `"client"` (HUD, keys) or `"both"` (two independent instances; check `mc.side`).

| Function | Permission | Notes |
|---|---|---|
| `mc.on(event, fn)` | – (`chat`, `hud` for those events) | see events below; return `false` to cancel cancellable events |
| `mc.after(ticks, fn)` / `mc.every(ticks, fn)` | – | returns handle with `:cancel()`; 20 ticks = 1 s |
| `mc.log(...)`, `mc.warn(...)`, `print(...)` | – | |
| `mc.singleplayer()` | – | client: true in singleplayer |
| `mc.command(name, fn(ctx))` | `commands` | ctx = `{sender, is_player, args, raw}`; returned string is sent back |
| `mc.run(cmd)` | `commands.run` | runs a command as console (server) / as the player (client) |
| `mc.tell(player, msg)`, `mc.broadcast(msg)`, `mc.actionbar(player, msg)` | `chat` | `§` colour codes work |
| `mc.toast(title, msg)` | – | client only |
| `mc.players()`, `mc.player(name)` | `players` | `{name, uuid, dimension, x, y, z, yaw, pitch, health, max_health, food, gamemode, held_item, op}` |
| `mc.teleport(p, x, y, z)`, `mc.give(p, item, n)`, `mc.set_health(p, h)`, `mc.effect(p, id, sec, amp)` | `players.modify` | |
| `mc.world.get_block(dim, x, y, z)`, `mc.world.time(dim)` | `world.read` | |
| `mc.world.set_block(dim, x, y, z, id)`, `mc.world.spawn(dim, entity, x, y, z)`, `mc.world.explode(...)`, `mc.world.set_time(dim, t)` | `world.write` | |
| `mc.world.particle(dim, id, x, y, z, count)`, `mc.world.sound(dim, id, x, y, z, vol, pitch)` | `effects` | |
| `mc.hud.text(x, y, s, argb, shadow)`, `mc.hud.rect(x, y, w, h, argb)`, `mc.hud.item(x, y, id)` | `hud` | only inside `on("hud")` |
| `mc.key(label, default_key, fn)` | `keys` | 16 slots, rebindable under Controls → MC Addons |
| `mc.storage.get(k)`, `.set(k, v)`, `.keys()` | `storage` | JSON-persisted per plugin, survives reloads |
| `mc.count_item(p, item)` / `mc.take_item(p, item, n)` | `players` / `players.modify` | inventory checks, e.g. for shops |
| `mc.render.line(x1,y1,z1,x2,y2,z2,argb,width,through_walls)`, `mc.render.box(x1,y1,z1,x2,y2,z2,argb,fill,through_walls)`, `mc.render.text(s,x,y,z,argb,scale)` | `render` | client, only inside `on("render")`; drawn in the world |
| `mc.gui.open{title, width, height, widgets, on_close}`, `mc.gui.close()` | `gui` | client screens: `label`, `button` (on_click gets `e.inputs`), `input`, `item`, `rect` |
| `mc.menu.open(player, {title, rows, items = {[slot]=id}, on_click})` | `gui` | server chest menus (shops, selectors); items can't be taken |
| `mc.net.send(channel, data [, player])` | `network` | talks to the other half of the same plugin → `on("message", fn(e))` |
| `mc.world.gamerule(name, value)` | `world.write` | |
| `require("module")` | – | loads `module.lua` from the plugin folder only |
| `mc.api_version`, `mc.minecraft_version`, `mc.side`, `mc.plugin`, `mc.content`, `mc.has_permission(p)` | – | |

**Events:** `enable`, `disable`, `tick`, `player_join`, `player_leave`, `player_respawn`, `player_death`,
`chat`\*, `block_break`\*, `block_use`\*, `item_use`\*, `attack_entity`\*, `world_load`, `world_unload`, `hud`, `render`, `message` (\* = cancellable).

### Resources & data packs (textures, sounds, recipes, loot tables, tags, advancements, worldgen)

A plugin folder can contain normal resource/data pack folders:

```
plugins/ruby/
  plugin.json  main.lua  icon.png
  assets/ruby/textures/item/ruby.png        ← permission "resources" (textures, models, sounds.json + .ogg, lang)
  data/ruby/recipe/ruby_block.json          ← permission "data" (recipes, loot_table, tags, advancement, worldgen, functions)
```

MC Addons merges all enabled plugins into one always-active pack ("MC Addons plugins") for the client and every world.
For declared items/blocks it auto-generates models, blockstates, block drops and names – you only drop in the PNGs
(`textures/item/<id>.png`, `textures/block/<id>.png`).

### Items, blocks, ores & loot

```json
"permissions": ["content"],
"content": {
  "items":  [{ "id": "ruby", "name": "Ruby", "max_stack": 64, "food": 0, "glint": true }],
  "blocks": [{ "id": "ruby_block", "name": "Ruby Block", "hardness": 5, "resistance": 6, "light": 10, "sound": "metal" }],
  "ores":   [{ "block": "ruby_ore", "dimension": "overworld", "size": 5, "per_chunk": 6, "min_y": -48, "max_y": 40 }],
  "loot":   [{ "table": "minecraft:entities/zombie", "item": "ruby", "chance": 0.05, "min": 1, "max": 1 }]
}
```

Registered as `<plugin_id>:<id>` at game start (Minecraft freezes registries, so new content needs one restart; Lua behaviour still hot-reloads).
On servers, clients need the same content plugins installed.

## Security model

| Threat | Defence |
|---|---|
| File/OS access | `io`, `os.*` (except `time`/`clock`), `debug`, `package`, `load*`, `dofile`, `string.dump` removed; Cobalt has no Java bridge |
| Hanging the game | instruction budget (2M) + 1 s watchdog per call; 5 errors in a row → auto-disable |
| Abusing the game | every API function checks the permissions declared in `plugin.json`; user sees them before installing |
| Malicious downloads | HTTPS to `raw.githubusercontent.com` only, no redirects, commit-pinned URLs, SHA-256 per file, size limits, staged install |
| Path tricks | all paths validated (no `..`, absolute, hidden files) |
| Supply chain | registry only lists reviewed & merged plugins; CI validates every PR |

## Architecture & porting

```
core/          version-independent: Sandbox, Plugin, PluginManager, Registry, Host interface   (unit tested)
fabric-26.3/   adapter: ServerHost / ClientHost implement Host, event bridges, commands, GUI
```

To support another Minecraft version, copy `fabric-26.3/` to `fabric-<version>/`, fix the Minecraft calls, add it to `settings.gradle`. Plugins don't change.

## Honest limits

Lua plugins cannot do everything a Java mod can: no rendering-pipeline replacement (Sodium/Iris class mods), no mixins,
no custom entity types/AI, no new dimensions, no fuel/compost values (data-driven components in 26.3).
Those need new API surface in `Host` – the design allows adding it (API 1.x minor releases) without breaking existing plugins.

## Build & test

```bash
./gradlew build                          # mod jar + core unit tests (sandbox escape, budget, permissions, reload ...)
./gradlew :fabric-26.3:runServer         # dedicated server
./gradlew :fabric-26.3:runClientGameTest # automated client test (works under xvfb-run)
```
