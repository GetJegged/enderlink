# EnderLink

**Two-way Discord bridge for Fabric 26.2+ servers.**

Joins, leaves, deaths, advancements and server status go to a Discord channel. Messages typed
in that channel appear in in-game chat. Server-side only — players need no mods and no launcher
changes.

- **Minecraft:** 26.2+
- **Platforms:** Fabric · NeoForge · Paper (and forks)
- **Java:** 25+
- **Requires:** Fabric API (Fabric only — NeoForge and Paper have no dependencies)
- **License:** MIT

| Platform | Download | Notes |
|---|---|---|
| Fabric | `enderlink-fabric-1.0.0.jar` → `mods/` | Needs Fabric API |
| NeoForge | `enderlink-neoforge-1.0.0.jar` → `mods/` | Dedicated servers only |
| Paper | `enderlink-paper-1.0.0.jar` → `plugins/` | Works on Purpur, Pufferfish and other Paper forks |

**Spigot is not currently supported.** Paper has replaced the chat and advancement APIs with its
own, and `Advancement#getDisplay()` now returns a Paper type — so one jar cannot bind to both for
those events. Open an issue if you need Spigot and it can be revisited.

---

## Why this exists

Minecraft 26.1 was the first release shipped **unobfuscated**, and it moved enough API surface
that most Discord bridge mods stopped working. Fabricord, Fabric2Discord and Fabric-Discord-Link
are all still pinned to 1.21 or older. DiscordSRV — the usual recommendation — is a
Bukkit/Spigot/Paper plugin and has never run on Fabric at all.

**Discord Integration** has ported to 26.x and is the mature, full-featured option: account
linking, whitelist integration, Dynmap, a developer API, five loaders. If you want all that,
use it — it's good software.

EnderLink is the small one. It does chat, joins, deaths, advancements and mentions, and nothing
else. **No external dependencies** — no JDA, nothing shaded. Discord is reached with the JDK's
own HTTP and WebSocket clients, and JSON is parsed with the Gson that Minecraft already ships.
The result is a ~30 KB jar with one config file and no database, and no Discord library release
cycle to keep in step with Minecraft's.

Pick it if you want a bridge you can read end to end in an afternoon.

---

## What it looks like

In Discord:

> ✅ Server is **online**
>
> **Steve** joined the server
>
> 💀 Steve was slain by Zombie
>
> 🏆 **Steve** has made the advancement **Stone Age**
>
> **Steve** — hey is anyone on tonight?
>
> ⛔ Server is **shutting down**

Player messages carry the player's own name and skin head as the avatar. Events arrive as
colour-coded embeds — green for joins, red for leaves, dark red for deaths, gold for
advancements.

In-game, Discord messages appear as:

```
[Discord] Blake: dinner's ready, log off
```

Because the "online" embed only posts once the server is genuinely accepting players, the
channel doubles as an uptime log — no need to launch the game to find out if the server is up.

### Mentions

Typing `@blake` or `@admins` in Minecraft chat pings that Discord user or role for real.

Only names EnderLink has actually resolved are ever pinged, and `@everyone` stays impossible no
matter what a player types. Roles are read once over the API, so they work immediately. Users
are learned as they speak in the channel — which means someone must have talked (or been
mentioned) once before they can be pinged from in-game. That's a deliberate trade: listing every
member of a server requires Discord's privileged `GUILD_MEMBERS` intent, and this avoids asking
for it.

### Commands

| Where | Command | Does |
|---|---|---|
| Discord | `!list` | Who's online, with the player count. |
| Discord | `!tps` | Current ticks per second. |
| Discord | `!uptime` | How long the server has been up. |
| Discord | `!link <code>` | Redeems a code from `/link`. |
| Discord | `!unlink` | Removes your account link. |
| In-game | `/discord` | Prints your invite link. Only exists if `discord-invite` is set. |
| In-game | `/link` | Issues a code to redeem in Discord. |

The bot's status also shows `N/M online`, updated as players come and go — visible in the
member list without opening the channel.

### Account linking

Set `enable-linking: true`. A player runs `/link` in-game, gets a six-character code, and sends
`!link ABC123` in Discord.

The flow starts **in Minecraft on purpose**: the server has already authenticated that side, so
the code only has to prove the Discord side. Starting in Discord would let anyone claim any
username. Codes are single-use, expire after `link-code-minutes`, and come from `SecureRandom` —
with `whitelist-on-link` enabled a guessable code would be a way onto the server.

Set `whitelist-on-link: true` to whitelist players automatically when they link.

### Management channel

Set `management-channel-id` and `management-role-id` to run server commands from Discord:

```
!say hello everyone
!whitelist list
```

**This is remote console access.** Three things guard it:

- Commands only work in the **management channel**, never the public one.
- The author must hold the **management role** — checked against the role IDs Discord attaches
  to the message.
- **A blank `management-role-id` means nobody is authorised**, not everyone. Misconfiguration
  fails closed.

Messages in the management channel are never relayed into in-game chat, and unprefixed text is
never executed — only `!`-prefixed messages run as commands, so ordinary chatter is safe.

On Fabric, command output is captured and posted back. On Paper, Bukkit gives no supported way
to intercept console output, so the reply confirms execution and points at the server console.

### Crash detection

`relay-crashes` (on by default) posts 💥 **Server stopped unexpectedly** when the JVM exits
without a clean shutdown. It uses a shutdown hook, so it catches crashes, OOM kills and most
terminations — not just exceptions the server happens to log.

---

## Install

1. Put **Fabric API** and **`enderlink-1.0.0.jar`** in your server's `mods/` folder.
2. Start the server once. It creates `config/enderlink.json` and logs that it's idle.
3. Fill in the config (below) and restart.

The two directions are independent. Configure just the webhook for one-way; add the bot when
you want Discord messages reaching players.

---

## Discord setup

### Part 1 — Webhook (Minecraft → Discord)

No bot, no token.

1. Right-click your channel → **Edit Channel**
2. **Integrations → Webhooks → New Webhook**
3. **Copy Webhook URL** → paste into `webhook-url`

A webhook is what lets each message carry the player's own name and skin. A bot posting the
same text would show up under one identity for everyone.

### Part 2 — Bot (Discord → Minecraft)

Webhooks can only write, so reading the channel needs a real bot.

1. <https://discord.com/developers/applications> → **New Application**
2. **Bot** → **Reset Token** → copy into `bot-token`

   > Treat this like a password. Anyone holding it controls the bot. If it leaks, hit **Reset
   > Token** — the old one dies immediately.

3. **Bot → Privileged Gateway Intents → enable MESSAGE CONTENT INTENT.**
   ← *This is the step everyone misses.* Without it Discord refuses the connection outright.
4. **OAuth2 → URL Generator**: scope `bot`, permissions `View Channel` + `Read Message History`.
   Open the generated URL and invite the bot.
5. Enable **Settings → Advanced → Developer Mode**, then right-click the channel →
   **Copy Channel ID** → paste into `channel-id`.

Restart. You should see:

```
[EnderLink] Connected to Discord as Your Bot Name
[EnderLink] EnderLink active (inbound=on outbound=on)
```

---

## Configuration

`config/enderlink.json`

| Key | Default | What it does |
|---|---|---|
| `webhook-url` | `""` | Channel webhook. Blank disables Minecraft → Discord. |
| `bot-token` | `""` | Bot token. Blank disables Discord → Minecraft. |
| `channel-id` | `""` | Which channel the bot listens to. |
| `discord-to-minecraft` | `true` | Master switch for the inbound half. |
| `relay-chat` | `true` | In-game chat → Discord. |
| `relay-join-leave` | `true` | "Steve joined the server" |
| `relay-deaths` | `true` | Uses the game's own death wording. |
| `relay-advancements` | `true` | Only advancements vanilla announces in chat. |
| `relay-server-status` | `true` | Server online / shutting down. |
| `server-name` | `"Minecraft Server"` | Name on event embeds. Blank falls back to the webhook's own name. |
| `avatar-url` | `https://mc-heads.net/avatar/{uuid}/64` | Player skin head. `{uuid}` and `{name}` are substituted. |
| `discord-chat-format` | `§9[Discord] §b{name}§r: {message}` | How Discord messages look in-game. |
| `max-message-length` | `256` | Longer Discord messages are truncated in-game. |
| `relay-mentions` | `true` | `@name` typed in Minecraft becomes a real Discord ping. |
| `command-prefix` | `"!"` | Prefix for commands typed in Discord. |
| `enable-list-command` | `true` | Answer `!list` with who's online. |
| `discord-invite` | `""` | Invite link shown by `/discord` in-game. Blank hides the command. |
| `show-player-count` | `true` | Bot activity shows "N/M online". |

> **Note:** the default avatar URL calls **mc-heads.net**, a third-party skin renderer. Discord
> fetches it, not your server. Point `avatar-url` elsewhere or blank it if you'd rather not
> involve a third party.

---

## What it handles for you

The failure modes that make a naive bridge unpleasant to run:

- **No relay loop.** Messages posted by webhooks are ignored on the way back in, so the bridge
  can't talk to itself.
- **No `@everyone` from in-game.** Every outbound post sets `allowed_mentions: {parse: []}`, so
  a player typing `@everyone` in Minecraft cannot ping your Discord.
- **No chat-colour injection.** Section signs from Discord are neutralised before reaching
  in-game chat — otherwise anyone could recolour or blank their own message.
- **No markdown surprises.** In-game text is escaped, so `*hi*` doesn't silently go italic.
- **Never blocks the server thread.** Outbound posts go through one background queue
  (single-threaded, so order is preserved). A slow or down Discord cannot lag the game.
- **Rate limits respected.** A 429 is honoured for exactly as long as Discord asks.
- **Reconnects on its own.** Dropped connections resume where they left off with exponential
  backoff; dead-but-open sockets are caught by heartbeat tracking.
- **Fails loudly.** A bad token or missing MESSAGE CONTENT intent stops the inbound half and
  names the exact portal setting to fix, instead of retrying forever.
- **Advancements report exactly once.** `award` fires per *criterion* and re-enters itself when
  an advancement grants a recipe — subtle enough to be worth stating: EnderLink tracks what it
  has already announced per player rather than inferring from call order.

## Known limitations

- **No inbound flood control.** Discord spam becomes in-game spam. Discord's own per-user rate
  limits keep this to a nuisance, so it's a deliberate omission at small-server scale.
- **One channel**, mapped to global in-game chat.
- **No Discord slash commands** (no `/list` from Discord yet).

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| `EnderLink is idle` | Config is blank. At minimum set `webhook-url`. |
| `Discord rejected the webhook post (HTTP 401/404)` | Webhook URL is wrong or was deleted. Make a new one. |
| `Discord refused our intents` | MESSAGE CONTENT INTENT is off in the developer portal. |
| `Discord rejected the bot token` | Bad or regenerated token. |
| Discord messages never reach the game | Bot isn't in the server, can't see the channel, or `channel-id` is a server/message ID rather than the channel ID. |
| Mod doesn't load at all | Fabric API missing from `mods/`, or Java below 25. |

---

## Architecture

Three Gradle modules:

```
core/      Discord transport, config, linking, formatting, commands.
           Zero Minecraft, Fabric, NeoForge or Bukkit types. Compiled to Java 21.
fabric/    glue + one mixin (Fabric has no advancement event).
neoforge/  glue only. No mixin — NeoForge has AdvancementEarnEvent.
paper/     glue only. No mixin — Bukkit has PlayerAdvancementDoneEvent.
```

Fabric is the only platform that needs bytecode surgery, and only because it's the only one
without an advancement event.

Everything platform-specific goes through one five-method interface,
[`BridgePlatform`](core/src/main/java/net/enderlink/core/BridgePlatform.java): config directory,
run-on-main-thread, broadcast, online players, max players. That's the entire surface a new
platform has to implement, which is why adding one is a few hundred lines rather than a fork.

`core` is deliberately compiled to **Java 21** while the platform modules target 25 — its class
files then load on any server new enough to run either, and a backport is not blocked by a
bytecode version.

## Building

Needs JDK 25.

```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew build
```

Outputs `fabric/build/libs/enderlink-fabric-1.0.0.jar` and
`paper/build/libs/enderlink-paper-1.0.0.jar`, each self-contained — `core` is folded in, and
nothing is shaded because both platforms already ship Gson and slf4j.

To test Fabric against a throwaway world: `./gradlew :fabric:runServer`.

Minecraft 26.1+ ships unobfuscated, so there's no `mappings` dependency, dependencies use plain
`implementation` rather than `modImplementation`, and the build output task is `jar`, not
`remapJar`. If you're porting an older mod, that's the part that trips people up.

---

## Contributing

Issues and pull requests welcome — particularly ports to other Minecraft versions.

## License

MIT. See [LICENSE](LICENSE).
