# Changelog

## 1.0.0

First release. Two-way Discord bridge for Minecraft 26.2 on Fabric.

**Minecraft → Discord**
- Player chat, posted with the player's own name and skin head as the avatar
- Join and leave
- Deaths, using the game's own wording
- Advancements, matching vanilla's task / goal / challenge phrasing
- Server online and shutting down

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
