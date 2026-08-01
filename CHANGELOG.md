# Changelog

## 1.0.0

First release. Two-way Discord bridge for Minecraft 26.2 on **Fabric and Paper**.

**Platforms**
- Split into `core` (no server API anywhere), `fabric` and `paper` modules
- One `BridgePlatform` interface — five methods — is the whole surface a new platform needs
- `core` compiles to Java 21 so its classes load on any server able to run either platform
- Paper needs no mixin: Bukkit has a real advancement event
- Spigot is not supported; Paper's chat and advancement APIs have diverged too far for one jar

**Minecraft → Discord**
- Player chat, posted with the player's own name and skin head as the avatar
- Join and leave
- Deaths, using the game's own wording
- Advancements, matching vanilla's task / goal / challenge phrasing
- Server online and shutting down

**Mentions and commands**
- `@name` and `@role` typed in Minecraft become real Discord pings, allow-listed by resolved id
  so `@everyone` remains impossible
- Roles are read once over the REST API; users are learned from channel activity, which avoids
  requiring the privileged `GUILD_MEMBERS` intent
- `!list` in Discord replies with who is online
- `/discord` in-game prints the configured invite link
- Bot activity shows the live player count

**Discord → Minecraft**
- Channel messages relayed into in-game chat
- Server nicknames preferred over display names
- User mentions resolved to readable names; custom emoji collapsed to `:name:`
- Image-only posts announced as `[attachment]`

**Behaviour worth calling out**
- Webhook posts are ignored on the way back in, so the bridge cannot loop with itself
- `allowed_mentions` is empty on every outbound post — `@everyone` typed in-game cannot ping
  Discord
- Section signs from Discord are neutralised before reaching in-game chat
- Outbound sending is queued off the server thread; a slow Discord cannot lag the game
- Gateway sessions resume after a drop, with exponential backoff and heartbeat-based detection
  of dead-but-open sockets
- A rejected token or missing MESSAGE CONTENT intent disables the inbound half with a message
  naming the setting to fix, rather than retrying forever
