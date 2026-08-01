package net.enderlink.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

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

    /**
     * How long the crash hook waits for a clean shutdown to be signalled before concluding the
     * server really did die. Long enough for Minecraft's own shutdown hook to reach
     * SERVER_STOPPING on a SIGTERM; short enough not to fight a container's kill timeout.
     */
    private static final long CLEAN_SHUTDOWN_GRACE_MILLIS = 10_000L;

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
        updatePresence(null, null);
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

            // Do NOT declare a crash yet. A SIGTERM — which is how systemd, docker and every
            // hosting panel stop a server — runs this hook and Minecraft's own shutdown hook at
            // the same time, so `cleanShutdown` is simply not set yet. Checking immediately made
            // every ordinary stop post a false "server crashed", which is worse than useless:
            // an alert that cries wolf on every restart gets muted, and then the real crash is
            // missed too.
            //
            // So wait for the clean-shutdown signal and bail the moment it arrives. A genuine
            // crash never sets it, so it still gets reported — just a few seconds later.
            long deadline = System.currentTimeMillis() + CLEAN_SHUTDOWN_GRACE_MILLIS;
            while (!cleanShutdown && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (cleanShutdown) {
                return;
            }
            LOGGER.warn("Server exited without a clean shutdown — reporting a crash to Discord");
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
        updatePresence(name, null);
    }

    public void playerLeft(String name, String uuid) {
        if (config.relayJoinLeave) {
            sender.sendLeave(name, uuid);
        }
        updatePresence(null, name);
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

        // A DM is a private conversation with the bot, never a chat source. Relaying it would let
        // anyone who can DM the bot broadcast to every player on the server without being in the
        // Discord channel at all — or in the Discord server.
        if (message.direct()) {
            reply(message, "I only handle `" + config.commandPrefix + "link` and `"
                    + config.commandPrefix + "unlink` here. Chat goes in the server's channel.");
            return;
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
            case "help", "commands" -> {
                reply(message, helpText(management));
                return true;
            }
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

    /**
     * Lists only what is actually available. Advertising a command that is switched off is worse
     * than listing nothing — the person tries it, gets silence, and concludes the bridge is
     * broken. Management commands appear only in the management channel, so the public channel
     * never learns that remote console access exists.
     */
    private String helpText(boolean management) {
        String p = config.commandPrefix;
        StringBuilder out = new StringBuilder("**EnderLink** — bridging this channel with the "
                + "Minecraft server.\n\n");

        if (config.enableListCommand) {
            out.append('`').append(p).append("list` — who's online\n");
        }
        out.append('`').append(p).append("tps` — server tick rate\n");
        out.append('`').append(p).append("uptime` — how long the server has been up\n");

        if (config.enableLinking) {
            out.append('`').append(p).append("link <code>` — link your Minecraft account "
                    + "(run `/link` in-game to get a code)\n");
            out.append('`').append(p).append("unlink` — remove that link\n");
        }

        if (management) {
            out.append("\n**Management channel**\n")
               .append('`').append(p).append("<server command>` — runs on the server console, "
                       + "e.g. `").append(p).append("whitelist list`. Requires the management role.\n");
        }

        if (config.relayChat) {
            out.append("\nAnything else you type here goes to in-game chat.");
        } else {
            out.append("\nAnything else you type here goes to in-game chat; in-game chat is not "
                    + "relayed back.");
        }
        return out.toString();
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

        // The webhook can only write to the chat channel, so management and DM replies go through
        // the bot instead. Getting this wrong for a DM would be a leak, not just a nuisance: the
        // answer to a private link attempt would land in the public channel. The bot is also the
        // fallback for chat replies when no webhook is configured — otherwise an inbound-only
        // setup would accept commands and answer into the void.
        if (gateway != null && (isManagement || message.direct() || !config.outboundEnabled())) {
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
     * <p>Neither join nor quit events can be trusted to line up with the player list. Fabric
     * fires its join event from partway through {@code placeNewPlayer}, <em>before</em> the
     * player is added to the list, so a plain count reads one too low — the bot sat at "0/20"
     * with someone online. Quit events have the opposite ambiguity, and the three platforms do
     * not agree with each other.
     *
     * <p>So rather than trust the list or guess an offset, the player the event is about is
     * forced in or out by name. That is correct on every platform and under either ordering.
     */
    private void updatePresence(String ensureIncluded, String ensureExcluded) {
        if (gateway == null || !config.showPlayerCount) {
            return;
        }
        platform.runOnMainThread(() -> {
            Set<String> online = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            online.addAll(platform.onlinePlayerNames());
            if (ensureExcluded != null) {
                online.remove(ensureExcluded);
            }
            if (ensureIncluded != null) {
                online.add(ensureIncluded);
            }
            gateway.updatePresence(online.size(), platform.maxPlayers());
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
