# EnderLink

**Two-way Discord bridge for Minecraft 26.2+ servers.**

Chat, joins, deaths, advancements and mentions go to a Discord channel. Messages typed there
appear in in-game chat. Server-side only — players need no mods.

| Platform | Jar | Drop it in | Notes |
|---|---|---|---|
| Fabric | `enderlink-fabric-1.0.0.jar` | `mods/` | Needs Fabric API |
| NeoForge | `enderlink-neoforge-1.0.0.jar` | `mods/` | Dedicated servers only |
| Paper | `enderlink-paper-1.0.0.jar` | `plugins/` | Also Purpur, Pufferfish and other forks |

Minecraft **26.2+** · Java **25+** · MIT · **no external dependencies**

---

## Why

Minecraft 26.1 shipped unobfuscated and broke most Discord bridges — Fabricord, Fabric2Discord
and Fabric-Discord-Link are all still on 1.21, and DiscordSRV is Paper-only and never ran on
Fabric.

[Discord Integration](https://modrinth.com/plugin/dcintegration) *has* ported to 26.x and is the
mature, full-featured choice — account linking, Dynmap, a dev API, five loaders. Use it if you
want all that.

EnderLink is the small one: a ~30 KB jar, one config file, no database, and no Discord library
to keep in step with Minecraft. Discord is reached with the JDK's own HTTP and WebSocket
clients and the Gson the server already ships.

---

## Setup

Start the server once to generate `config/enderlink.json` (`plugins/EnderLink/` on Paper), then:

**Minecraft → Discord** — channel → *Edit Channel → Integrations → Webhooks → New Webhook* →
copy the URL into `webhook-url`. Done.

**Discord → Minecraft** — needs a bot, since webhooks can only write:

1. <https://discord.com/developers/applications> → **New Application**
2. **Bot → Reset Token** → copy into `bot-token`. Treat it like a password.
3. **Bot → Privileged Gateway Intents → enable MESSAGE CONTENT INTENT.**
   ← *the step everyone misses.* Without it Discord refuses the connection.
4. **OAuth2 → URL Generator**: scope `bot`, permissions `View Channel` + `Read Message History`.
   Open the URL and invite the bot.
5. Enable *Settings → Advanced → Developer Mode*, right-click the channel → **Copy Channel ID**
   → `channel-id`.

Restart. You should see `Connected to Discord as …` and `EnderLink active (inbound=on outbound=on)`.

---

## Features

In Discord you get colour-coded embeds:

> ✅ Server is **online** · **Steve** joined the server · 💀 Steve was slain by Zombie ·
> 🏆 **Steve** has made the advancement **Stone Age**

Player messages carry the player's own name and skin as the avatar. The bot's status shows
`N/M online`. Because the "online" embed only posts once the server is really accepting players,
the channel doubles as an uptime log.

**Commands**

| Where | Command | Does |
|---|---|---|
| Discord | `!list` `!tps` `!uptime` | Server status |
| Discord | `!link <code>` `!unlink` | Account linking |
| In-game | `/discord` | Your invite link (hidden if `discord-invite` is unset) |
| In-game | `/link` | Issues a code to redeem in Discord |

**Mentions** — typing `@blake` or `@admins` in Minecraft pings that Discord user or role.
Only names EnderLink has resolved are ever pinged, and `@everyone` stays impossible. Roles work
immediately; users are learned as they speak in the channel, which avoids needing Discord's
privileged `GUILD_MEMBERS` intent.

**Account linking** (`enable-linking`) — a player runs `/link`, gets a six-character code, and
sends `!link ABC123` **in a DM to the bot**, or in the bridged channel.

DMs are the better route: the code is single-use, and whoever posts it is who gets linked, so
posting it publicly is a (small) race. DMs are accepted **only** for linking — DM content is
never relayed into in-game chat, and management commands can't run there at all, since a DM
carries no roles to check. The flow starts in Minecraft on purpose: the server has already
authenticated that side, so the code only has to prove the Discord side. Codes are single-use,
expiring and `SecureRandom`. `whitelist-on-link` whitelists them automatically.

**Management channel** (`management-channel-id` + `management-role-id`) — `!`-prefixed messages
run as server commands. This is remote console access, so: it works only in that channel, the
author must hold the management role, and **a blank role id authorises nobody, not everyone**.
Management messages never reach in-game chat, and unprefixed text never executes. Fabric and
NeoForge capture real command output; Paper confirms execution only, as Bukkit gives no
supported way to intercept it.

**Content filter** (`blocked-words`) — blocks matching messages **in both directions**. That
matters: in-game chat bypasses Discord's AutoMod entirely (webhook posts aren't scanned), and
Discord messages bypass every in-game chat plugin, so a bridge is a hole in your moderation
unless both sides are covered.

Matching ignores case, spacing, punctuation and digit-for-letter swaps — `b a d w o r d` and
`b4dw0rd` are both caught, because those are the first things anyone tries. **The trade-off is
that a word also matches inside longer words**, so pick the list deliberately. Blocked messages
are logged with the word that matched, so moderators can see what happened.

**Flood control** (`inbound-messages-per-minute`, default 20) — caps how much one Discord user
can push into the game. Outbound needs no limit: it already goes through one ordered queue that
respects Discord's rate limits.

**Crash detection** (`relay-crashes`) — posts 💥 when the JVM exits without a clean shutdown.
Uses a shutdown hook, so it catches OOM kills too, not just logged exceptions.

---

## Configuration

`config/enderlink.json`

| Key | Default | |
|---|---|---|
| `webhook-url` | `""` | Channel webhook. Blank disables Minecraft → Discord. |
| `bot-token` | `""` | Bot token. Blank disables Discord → Minecraft. |
| `channel-id` | `""` | Channel the bot listens to. |
| `discord-to-minecraft` | `true` | Master switch for the inbound half. |
| `relay-chat` | `true` | In-game chat → Discord. |
| `relay-join-leave` | `true` | |
| `relay-deaths` | `true` | Uses the game's own wording. |
| `relay-advancements` | `true` | Only ones vanilla announces in chat. |
| `relay-server-status` | `true` | Online / shutting down. |
| `relay-crashes` | `true` | Unclean exits. |
| `relay-mentions` | `true` | `@name` → real Discord ping. |
| `server-name` | `"Minecraft Server"` | Name on event embeds. |
| `avatar-url` | mc-heads.net | `{uuid}` and `{name}` are substituted. |
| `discord-chat-format` | `§9[Discord] §b{name}§r: {message}` | How Discord looks in-game. |
| `max-message-length` | `256` | Truncation for inbound messages. |
| `blocked-words` | `[]` | Blocks matching messages both ways. Empty = off. |
| `inbound-messages-per-minute` | `20` | Per-user inbound cap. `0` = off. |
| `command-prefix` | `"!"` | |
| `enable-list-command` | `true` | |
| `discord-invite` | `""` | Shown by `/discord`. |
| `show-player-count` | `true` | Bot activity. |
| `enable-linking` | `false` | |
| `whitelist-on-link` | `false` | |
| `link-code-minutes` | `10` | Code lifetime. |
| `management-channel-id` | `""` | Blank disables console commands. |
| `management-role-id` | `""` | Blank authorises nobody. |

> The default avatar URL calls **mc-heads.net**, a third-party skin renderer. Discord fetches it,
> not your server. Repoint or blank it if you'd rather not involve a third party.

---

## Design notes

- **No relay loop** — webhook posts are ignored inbound, so the bridge can't talk to itself.
- **No `@everyone` from in-game** — outbound posts allow-list resolved IDs only.
- **No chat-colour injection** — section signs from Discord are neutralised.
- **Never blocks the server thread** — outbound goes through one ordered background queue.
- **Rate limits respected**; dropped gateway connections resume with backoff.
- **Fails loudly** — a bad token or missing intent names the exact portal setting to fix.

**Limitations:** one channel · Spigot unsupported (Paper's chat and advancement APIs diverged
too far for one jar).

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| `EnderLink is idle` | Config blank. Set `webhook-url` at minimum. |
| `Discord rejected the webhook post (401/404)` | Webhook URL wrong or deleted. |
| `Discord refused our intents` | MESSAGE CONTENT INTENT is off. |
| `Discord rejected the bot token` | Bad or regenerated token. |
| Discord messages never arrive in-game | Bot not in the server, can't see the channel, or `channel-id` is a server/message ID. |
| Mod doesn't load | Fabric API missing, or Java below 25. |

---

## Development

```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew build          # all three platform jars
./gradlew :fabric:runServer
```

**Test without Discord.** `tools/mock-discord.py` impersonates a webhook and prints what the mod
sends — no Discord account, no risk to a live server:

```bash
python3 tools/mock-discord.py       # then point webhook-url at 127.0.0.1:8787/webhook
```

```
[Minecraft Server] ✅ Server is **online**
   ↳ @everyone blocked
```

The inbound half can't be faked locally — that needs a real bot token.

**Layout.** `core/` holds the Discord transport, config, linking and commands with zero
Minecraft, Fabric, NeoForge or Bukkit types, compiled to Java 21. Each platform is a thin
adapter behind one five-method interface,
[`BridgePlatform`](core/src/main/java/net/enderlink/core/BridgePlatform.java). Only Fabric needs
a mixin, because it's the only platform without an advancement event.

Minecraft 26.1+ ships unobfuscated: no `mappings` dependency, plain `implementation`, and the
output task is `jar` rather than `remapJar`.

---

Issues and pull requests welcome — ports to other versions especially. MIT licensed.
