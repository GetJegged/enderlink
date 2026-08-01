package net.enderlink;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Two-way bridge between a Fabric server and one Discord channel.
 *
 * <p>Outbound (Minecraft -> Discord) goes through a channel webhook so each message can carry
 * the player's own name and skin. Inbound (Discord -> Minecraft) needs a bot on the gateway,
 * because webhooks can only write. The two halves are independent: configure either one alone
 * and the other simply stays off.
 */
public final class EnderLink implements DedicatedServerModInitializer {
    public static final String MOD_ID = "enderlink";
    public static final Logger LOGGER = LoggerFactory.getLogger("EnderLink");

    private static EnderLink instance;

    private BridgeConfig config;
    private DiscordSender sender;
    private DiscordGateway gateway;
    /** Volatile: written on the server thread, read on the gateway thread when Discord speaks. */
    private volatile MinecraftServer server;

    /** Accessor for the advancement mixin, which has no other way to reach the bridge. */
    public static EnderLink get() {
        return instance;
    }

    @Override
    public void onInitializeServer() {
        instance = this;
        config = BridgeConfig.load();
        sender = new DiscordSender(config);

        if (config.inboundEnabled()) {
            gateway = new DiscordGateway(config, this::onDiscordMessage);
            // Outbound mentions need the gateway's learned name->id map, so this is only wired
            // up when the inbound half exists.
            sender.setMentionResolver(new DiscordSender.MentionResolver() {
                @Override
                public String userId(String name) {
                    return gateway.lookupUser(name);
                }

                @Override
                public String roleId(String name) {
                    return gateway.lookupRole(name);
                }
            });
        }

        registerLifecycleEvents();
        registerPlayerEvents();
        registerCommands();

        if (!config.outboundEnabled() && !config.inboundEnabled()) {
            LOGGER.warn("EnderLink is idle — fill in config/enderlink.json and restart. "
                    + "Outbound needs `webhook-url`; inbound needs `bot-token` and `channel-id`.");
        }
    }

    // ---- Registration ------------------------------------------------------------------------

    private void registerLifecycleEvents() {
        ServerLifecycleEvents.SERVER_STARTED.register(startedServer -> {
            this.server = startedServer;

            if (gateway != null) {
                gateway.start();
                LOGGER.info("EnderLink active ({})", gateway.describe());
            }
            if (config.relayServerStatus) {
                sender.sendServerStarted();
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(stoppingServer -> {
            if (config.relayServerStatus) {
                sender.sendServerStopping();
            }
            if (gateway != null) {
                gateway.stop();
            }
        });

        // Draining the send queue happens here rather than in SERVER_STOPPING so the shutdown
        // message queued above actually gets delivered before the JVM goes away.
        ServerLifecycleEvents.SERVER_STOPPED.register(stoppedServer -> {
            this.server = null;
            sender.shutdown();
        });
    }

    private void registerPlayerEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, packetSender, joinedServer) -> {
            if (config.relayJoinLeave) {
                ServerPlayer player = handler.player;
                sender.sendJoin(player.getScoreboardName(), player.getUUID().toString());
            }
            updatePresence(joinedServer, 0);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, disconnectedServer) -> {
            if (config.relayJoinLeave) {
                ServerPlayer player = handler.player;
                sender.sendLeave(player.getScoreboardName(), player.getUUID().toString());
            }
            // DISCONNECT fires before the player list has actually shrunk, so discount them.
            updatePresence(disconnectedServer, -1);
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, chatSender, params) -> {
            if (config.relayChat) {
                sender.sendPlayerChat(
                        chatSender.getScoreboardName(),
                        chatSender.getUUID().toString(),
                        message.signedContent());
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!config.relayDeaths || !(entity instanceof ServerPlayer player)) {
                return;
            }
            // Reuse the game's own wording so Discord reads exactly like in-game chat did.
            String deathMessage = player.getCombatTracker().getDeathMessage().getString();
            sender.sendDeath(deathMessage, player.getScoreboardName(), player.getUUID().toString());
        });
    }

    /**
     * Registers {@code /discord}, which prints the server's invite link. Skipped entirely when
     * no invite is configured, so the command never exists to advertise a blank link.
     */
    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            if (config.discordInvite.isBlank()) {
                return;
            }
            dispatcher.register(Commands.literal("discord").executes(context -> {
                context.getSource().sendSuccess(
                        () -> Component.literal("§9Join us on Discord: §b" + config.discordInvite),
                        false);
                return 1;
            }));
        });
    }

    // ---- Called from the advancement mixin --------------------------------------------------

    /** Invoked when a player completes an advancement that vanilla would announce in chat. */
    public void onAdvancement(ServerPlayer player, AdvancementHolder advancement) {
        if (!config.relayAdvancements) {
            return;
        }

        DisplayInfo display = advancement.value().display().orElse(null);
        if (display == null || !display.shouldAnnounceChat()) {
            return;
        }

        sender.sendAdvancement(
                player.getScoreboardName(),
                player.getUUID().toString(),
                verbFor(display.getType()),
                display.getTitle().getString());
    }

    /** Matches vanilla's own phrasing for each advancement frame type. */
    private static String verbFor(AdvancementType type) {
        return switch (type) {
            case CHALLENGE -> "completed the challenge";
            case GOAL -> "reached the goal";
            default -> "made the advancement";
        };
    }

    // ---- Discord -> Minecraft -----------------------------------------------------------------

    /**
     * Called on the gateway thread. Nothing here may touch server state directly, so the
     * broadcast is handed to the server thread via {@link MinecraftServer#execute}.
     */
    private void onDiscordMessage(String authorName, String content) {
        MinecraftServer currentServer = this.server;
        if (currentServer == null) {
            return;
        }

        // Commands are answered in Discord rather than echoed into the game.
        if (config.enableListCommand && !config.commandPrefix.isBlank()
                && content.strip().equalsIgnoreCase(config.commandPrefix + "list")) {
            replyWithPlayerList(currentServer);
            return;
        }

        String safeName = sanitize(authorName);
        String safeContent = DiscordSender.truncate(sanitize(content), config.maxMessageLength);

        String line = applyFormat(config.discordChatFormat, safeName, safeContent);

        currentServer.execute(() ->
                currentServer.getPlayerList().broadcastSystemMessage(Component.literal(line), false));
    }

    /** Answers {@code !list} in Discord with who is online. */
    private void replyWithPlayerList(MinecraftServer currentServer) {
        // The player list may only be read on the server thread.
        currentServer.execute(() -> {
            List<ServerPlayer> players = currentServer.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                sender.sendPlain("Nobody is online right now.");
                return;
            }
            String names = players.stream()
                    .map(ServerPlayer::getScoreboardName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining(", "));
            sender.sendPlain("**" + players.size() + "/"
                    + currentServer.getPlayerList().getMaxPlayers() + " online:** " + names);
        });
    }

    /**
     * Publishes the player count as the bot's activity. {@code delta} adjusts for events that
     * fire either side of the player list actually changing.
     */
    private void updatePresence(MinecraftServer currentServer, int delta) {
        if (gateway == null || !config.showPlayerCount || currentServer == null) {
            return;
        }
        currentServer.execute(() -> {
            int online = Math.max(0, currentServer.getPlayerList().getPlayers().size() + delta);
            gateway.updatePresence(online, currentServer.getPlayerList().getMaxPlayers());
        });
    }

    /**
     * Fills the chat format in a single pass. Chained {@code String.replace} calls would let a
     * Discord nickname of literally "{message}" expand into the message body on the next call.
     */
    private static String applyFormat(String format, String name, String message) {
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

    /**
     * Strips section signs and newlines from Discord-supplied text. A section sign is a live
     * formatting escape in Minecraft chat, so without this anyone in Discord could recolour
     * their message — or blank it out entirely — by typing one.
     */
    private static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        return input.replace('§', '?')
                .replace('\n', ' ')
                .replace('\r', ' ');
    }
}
