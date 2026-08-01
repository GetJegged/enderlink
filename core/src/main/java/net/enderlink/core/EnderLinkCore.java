package net.enderlink.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

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
    private final LinkStore links;

    private final long startedAt = System.currentTimeMillis();
    /** Set by {@link #serverStopping()}; a shutdown hook running without it means a crash. */
    private volatile boolean cleanShutdown;

    public EnderLinkCore(BridgePlatform platform) {
        this.platform = platform;
        this.config = BridgeConfig.load(platform.configDir());
        this.sender = new DiscordSender(config);
        this.links = new LinkStore(platform.configDir(), config.linkCodeMinutes);

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
        if (!config.managementChannelId.isBlank() && config.managementRoleId.isBlank()) {
            LOGGER.warn("A management channel is set but `management-role-id` is not, so console "
                    + "commands will be refused. Set the role id to enable them.");
        }

        registerCrashHook();
    }

    /** Exposed for the in-game {@code /link} command. */
    public LinkStore links() {
        return links;
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
        cleanShutdown = true;
        if (config.relayServerStatus) {
            sender.sendServerStopping();
        }
        if (gateway != null) {
            gateway.stop();
        }
    }

    /**
     * Detects a death that never reached {@link #serverStopping()} — a crash, an OOM kill, or a
     * {@code kill -9}'s gentler cousins. A shutdown hook catches all of those, which is why this
     * is more dependable than watching for a specific exception.
     *
     * <p>The post is sent synchronously: the queue's worker thread is a daemon and the JVM will
     * not wait for it, so anything enqueued here would simply vanish.
     */
    private void registerCrashHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (cleanShutdown || !config.relayCrashes || !config.outboundEnabled()) {
                return;
            }
            LOGGER.warn("Server exiting without a clean shutdown — reporting a crash to Discord");
            sender.sendCrashBlocking();
        }, "enderlink-crash-hook"));
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
        updatePresence(null);
    }

    public void playerLeft(String name, String uuid) {
        if (config.relayJoinLeave) {
            sender.sendLeave(name, uuid);
        }
        // Excluding the leaver by name is exact on every platform. The alternative — assuming the
        // quit event fires before or after the player list shrinks — differs between Fabric,
        // NeoForge and Bukkit, and guessing wrong leaves the count permanently off by one.
        updatePresence(name);
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
    private void onDiscordMessage(DiscordGateway.IncomingMessage message) {
        String content = message.content().strip();
        boolean management = !config.managementChannelId.isBlank()
                && config.managementChannelId.equals(message.channelId());

        if (!config.commandPrefix.isBlank() && content.startsWith(config.commandPrefix)) {
            String body = content.substring(config.commandPrefix.length()).strip();
            String verb = body.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            String args = body.contains(" ") ? body.substring(body.indexOf(' ') + 1).strip() : "";

            if (handleCommand(verb, args, message, management)) {
                return;
            }
        }

        // The management channel is for commands, not chat — relaying it into the game would
        // put admin chatter in front of every player.
        if (management) {
            return;
        }

        String line = applyFormat(config.discordChatFormat,
                sanitize(message.authorName()),
                DiscordSender.truncate(sanitize(content), config.maxMessageLength));

        platform.runOnMainThread(() -> platform.broadcast(line));
    }

    /** @return true if the message was a command and has been dealt with */
    private boolean handleCommand(String verb, String args,
                                  DiscordGateway.IncomingMessage message, boolean management) {
        switch (verb) {
            case "list" -> {
                if (!config.enableListCommand) {
                    return false;
                }
                platform.runOnMainThread(() -> {
                    List<String> names = platform.onlinePlayerNames();
                    reply(message, names.isEmpty()
                            ? "Nobody is online right now."
                            : "**" + names.size() + "/" + platform.maxPlayers() + " online:** "
                                    + String.join(", ",
                                            names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()));
                });
                return true;
            }
            case "uptime" -> {
                reply(message, "Server has been up for **" + formatUptime() + "**.");
                return true;
            }
            case "tps" -> {
                platform.runOnMainThread(() -> {
                    double tps = platform.tps();
                    reply(message, tps < 0
                            ? "This server does not report TPS."
                            : String.format(Locale.ROOT, "TPS: **%.2f**", Math.min(tps, 20.0)));
                });
                return true;
            }
            case "link" -> {
                if (!config.enableLinking) {
                    return false;
                }
                redeemLink(message, args);
                return true;
            }
            case "unlink" -> {
                if (!config.enableLinking) {
                    return false;
                }
                String name = links.unlink(message.authorId());
                reply(message, name == null
                        ? "You are not linked to a Minecraft account."
                        : "Unlinked from **" + name + "**.");
                return true;
            }
            default -> {
                // Anything else in the management channel is treated as a server command.
                if (management) {
                    runManagementCommand(verb + (args.isEmpty() ? "" : " " + args), message);
                    return true;
                }
                return false;
            }
        }
    }

    // ---- Management channel ------------------------------------------------------------------------

    /**
     * Runs a server command on behalf of a Discord user. This is remote console access, so it
     * fails closed: with no management role configured, nobody is authorised — a blank setting
     * must not mean "everyone".
     */
    private void runManagementCommand(String command, DiscordGateway.IncomingMessage message) {
        if (config.managementRoleId.isBlank()) {
            reply(message, "Console commands are disabled: no `management-role-id` is set.");
            return;
        }
        if (!message.roleIds().contains(config.managementRoleId)) {
            LOGGER.warn("Refused console command from {} (missing management role): {}",
                    message.authorName(), command);
            reply(message, "You do not have the management role.");
            return;
        }

        LOGGER.info("Console command from Discord user {}: {}", message.authorName(), command);
        platform.runOnMainThread(() -> {
            String output = platform.executeConsoleCommand(command);
            if (output == null) {
                reply(message, "This platform cannot run console commands.");
            } else if (output.isBlank()) {
                reply(message, "Ran `" + command + "` (no output).");
            } else {
                reply(message, "```\n" + DiscordSender.truncate(output, 1500) + "\n```");
            }
        });
    }

    // ---- Account linking ---------------------------------------------------------------------------

    /** Called by a platform when a player runs {@code /link} in-game. */
    public String createLinkCode(String uuid, String playerName) {
        return links.createCode(uuid, playerName);
    }

    public boolean linkingEnabled() {
        return config.enableLinking;
    }

    private void redeemLink(DiscordGateway.IncomingMessage message, String code) {
        if (code.isBlank()) {
            reply(message, "Run `/link` in-game first, then send `"
                    + config.commandPrefix + "link <code>` here.");
            return;
        }

        String playerName = links.redeem(code, message.authorId());
        if (playerName == null) {
            reply(message, "That code is not valid or has expired. Run `/link` in-game for a new one.");
            return;
        }

        String uuid = links.minecraftUuidFor(message.authorId());
        if (config.whitelistOnLink) {
            platform.runOnMainThread(() -> {
                boolean ok = platform.setWhitelisted(playerName, uuid, true);
                reply(message, ok
                        ? "Linked to **" + playerName + "** and added to the whitelist."
                        : "Linked to **" + playerName + "**, but the whitelist could not be updated.");
            });
        } else {
            reply(message, "Linked to **" + playerName + "**.");
        }
    }

    // ---- Replies -------------------------------------------------------------------------------------

    /**
     * Answers in whichever channel the command came from — management replies can contain command
     * output and must not leak into the public chat channel.
     */
    private void reply(DiscordGateway.IncomingMessage message, String text) {
        boolean isManagement = !config.managementChannelId.isBlank()
                && config.managementChannelId.equals(message.channelId());

        // The webhook can only write to the chat channel, so management replies go through the
        // bot. The bot is also the fallback for chat replies when no webhook is configured —
        // otherwise an inbound-only setup would accept commands and answer into the void.
        if (gateway != null && (isManagement || !config.outboundEnabled())) {
            gateway.sendChannelMessage(message.channelId(), text);
        } else {
            sender.sendPlain(text);
        }
    }

    private String formatUptime() {
        long seconds = (System.currentTimeMillis() - startedAt) / 1000L;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    /**
     * Publishes the player count as the bot's activity.
     *
     * @param leaving name to discount, or null — see {@link #playerLeft}
     */
    private void updatePresence(String leaving) {
        if (gateway == null || !config.showPlayerCount) {
            return;
        }
        platform.runOnMainThread(() -> {
            long online = platform.onlinePlayerNames().stream()
                    .filter(name -> leaving == null || !name.equalsIgnoreCase(leaving))
                    .count();
            gateway.updatePresence((int) online, platform.maxPlayers());
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
