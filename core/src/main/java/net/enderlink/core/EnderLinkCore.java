package net.enderlink.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The bridge itself, with no server API anywhere in it.
 *
 * <p>A platform module constructs one of these with a {@link BridgePlatform}, then calls the
 * {@code player*} / {@code server*} methods as its own events fire. Everything else — config,
 * Discord connections, message formatting, command handling, mention resolution — lives here
 * and is shared by every platform.
 */
public final class EnderLinkCore {
    public static final Logger LOGGER = LoggerFactory.getLogger("EnderLink");

    private final BridgePlatform platform;
    private final BridgeConfig config;
    private final DiscordSender sender;
    private final DiscordGateway gateway;

    public EnderLinkCore(BridgePlatform platform) {
        this.platform = platform;
        this.config = BridgeConfig.load(platform.configDir());
        this.sender = new DiscordSender(config);

        if (config.inboundEnabled()) {
            this.gateway = new DiscordGateway(config, this::onDiscordMessage);
            // Outbound mentions need the gateway's learned name->id map, so this only exists
            // when the inbound half does.
            this.sender.setMentionResolver(new DiscordSender.MentionResolver() {
                @Override
                public String userId(String name) {
                    return gateway.lookupUser(name);
                }

                @Override
                public String roleId(String name) {
                    return gateway.lookupRole(name);
                }
            });
        } else {
            this.gateway = null;
        }

        if (!config.outboundEnabled() && !config.inboundEnabled()) {
            LOGGER.warn("EnderLink is idle — fill in the config and restart. "
                    + "Outbound needs `webhook-url`; inbound needs `bot-token` and `channel-id`.");
        }
    }

    public BridgeConfig config() {
        return config;
    }

    // ---- Server lifecycle ---------------------------------------------------------------------

    /** Call once the server is actually accepting players. */
    public void serverStarted() {
        if (gateway != null) {
            gateway.start();
            LOGGER.info("EnderLink active ({})", gateway.describe());
        }
        if (config.relayServerStatus) {
            sender.sendServerStarted();
        }
    }

    /** Call at the start of a clean shutdown. */
    public void serverStopping() {
        if (config.relayServerStatus) {
            sender.sendServerStopping();
        }
        if (gateway != null) {
            gateway.stop();
        }
    }

    /**
     * Call once the server is fully stopped. Draining happens here rather than in
     * {@link #serverStopping()} so the shutdown message is actually delivered before exit.
     */
    public void shutdown() {
        sender.shutdown();
    }

    // ---- Player events ------------------------------------------------------------------------

    public void playerJoined(String name, String uuid) {
        if (config.relayJoinLeave) {
            sender.sendJoin(name, uuid);
        }
        updatePresence(0);
    }

    /**
     * @param stillListed whether the leaving player is still counted by
     *     {@link BridgePlatform#onlinePlayerNames()} — platforms differ on whether their quit
     *     event fires before or after the player list shrinks.
     */
    public void playerLeft(String name, String uuid, boolean stillListed) {
        if (config.relayJoinLeave) {
            sender.sendLeave(name, uuid);
        }
        updatePresence(stillListed ? -1 : 0);
    }

    public void playerChat(String name, String uuid, String message) {
        if (config.relayChat) {
            sender.sendPlayerChat(name, uuid, message);
        }
    }

    public void playerDied(String deathMessage, String name, String uuid) {
        if (config.relayDeaths) {
            sender.sendDeath(deathMessage, name, uuid);
        }
    }

    public void advancementEarned(String name, String uuid, String kind, String title) {
        if (config.relayAdvancements) {
            sender.sendAdvancement(name, uuid, kind, title);
        }
    }

    // ---- Discord -> Minecraft -------------------------------------------------------------------

    /** Called on the gateway thread. Anything touching server state hops threads first. */
    private void onDiscordMessage(String authorName, String content) {
        if (config.enableListCommand && !config.commandPrefix.isBlank()
                && content.strip().equalsIgnoreCase(config.commandPrefix + "list")) {
            replyWithPlayerList();
            return;
        }

        String line = applyFormat(config.discordChatFormat,
                sanitize(authorName),
                DiscordSender.truncate(sanitize(content), config.maxMessageLength));

        platform.runOnMainThread(() -> platform.broadcast(line));
    }

    private void replyWithPlayerList() {
        platform.runOnMainThread(() -> {
            List<String> names = platform.onlinePlayerNames();
            if (names.isEmpty()) {
                sender.sendPlain("Nobody is online right now.");
                return;
            }
            sender.sendPlain("**" + names.size() + "/" + platform.maxPlayers() + " online:** "
                    + String.join(", ", names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()));
        });
    }

    /** Publishes the player count as the bot's activity. */
    private void updatePresence(int delta) {
        if (gateway == null || !config.showPlayerCount) {
            return;
        }
        platform.runOnMainThread(() -> {
            int online = Math.max(0, platform.onlinePlayerNames().size() + delta);
            gateway.updatePresence(online, platform.maxPlayers());
        });
    }

    // ---- Text handling ----------------------------------------------------------------------------

    /**
     * Strips section signs and newlines from Discord-supplied text. A section sign is a live
     * formatting escape in Minecraft chat, so without this anyone in Discord could recolour
     * their message — or blank it out entirely — by typing one.
     */
    static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        return input.replace('§', '?').replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * Fills the chat format in a single pass. Chained {@code String.replace} calls would let a
     * Discord nickname of literally "{message}" expand into the message body on the next call.
     */
    static String applyFormat(String format, String name, String message) {
        StringBuilder out = new StringBuilder(format.length() + name.length() + message.length());
        int i = 0;
        while (i < format.length()) {
            if (format.startsWith("{name}", i)) {
                out.append(name);
                i += "{name}".length();
            } else if (format.startsWith("{message}", i)) {
                out.append(message);
                i += "{message}".length();
            } else {
                out.append(format.charAt(i));
                i++;
            }
        }
        return out.toString();
    }
}
