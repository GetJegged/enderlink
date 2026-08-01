package net.sculklink.fabric;

import net.sculklink.core.BridgePlatform;
import net.sculklink.core.SculkLinkCore;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserWhiteListEntry;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Fabric glue. Translates Fabric's events into {@link SculkLinkCore} calls and implements
 * {@link BridgePlatform} — nothing Discord-related lives here.
 */
public final class SculkLinkFabric implements DedicatedServerModInitializer, BridgePlatform {
    private static SculkLinkFabric instance;

    private SculkLinkCore core;
    /** Volatile: written on the server thread, read on the gateway thread when Discord speaks. */
    private volatile MinecraftServer server;

    /** Accessor for the advancement mixin, which has no other way to reach the bridge. */
    public static SculkLinkFabric get() {
        return instance;
    }

    @Override
    public void onInitializeServer() {
        instance = this;
        core = new SculkLinkCore(this);

        registerLifecycleEvents();
        registerPlayerEvents();
        registerCommands();
    }

    // ---- BridgePlatform ------------------------------------------------------------------------

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public void runOnMainThread(Runnable task) {
        MinecraftServer current = this.server;
        if (current != null) {
            current.execute(task);
        }
    }

    @Override
    public void broadcast(String legacyMessage) {
        MinecraftServer current = this.server;
        if (current != null) {
            current.getPlayerList().broadcastSystemMessage(Component.literal(legacyMessage), false);
        }
    }

    @Override
    public List<String> onlinePlayerNames() {
        MinecraftServer current = this.server;
        if (current == null) {
            return List.of();
        }
        return current.getPlayerList().getPlayers().stream()
                .map(ServerPlayer::getScoreboardName)
                .toList();
    }

    @Override
    public int maxPlayers() {
        MinecraftServer current = this.server;
        return current == null ? 0 : current.getPlayerList().getMaxPlayers();
    }

    /**
     * Runs a command as console and captures what it prints.
     *
     * <p>The output is collected by swapping in a {@link CommandSource} that appends to a buffer
     * instead of writing to the log — that is what lets a Discord reply carry the real result
     * rather than just "command sent".
     */
    @Override
    public String executeConsoleCommand(String command) {
        MinecraftServer current = this.server;
        if (current == null) {
            return null;
        }

        StringBuilder output = new StringBuilder();
        CommandSource collector = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.append(message.getString()).append('\n');
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };

        try {
            current.getCommands().performPrefixedCommand(
                    current.createCommandSourceStack().withSource(collector), command);
        } catch (Exception e) {
            output.append("Error: ").append(e.getMessage());
        }
        return output.toString().strip();
    }

    @Override
    public boolean setWhitelisted(String playerName, String uuid, boolean whitelisted) {
        MinecraftServer current = this.server;
        if (current == null) {
            return false;
        }
        try {
            // 26.2 whitelists a NameAndId rather than a GameProfile.
            NameAndId entry = new NameAndId(UUID.fromString(uuid), playerName);
            if (whitelisted) {
                current.getPlayerList().getWhiteList().add(new UserWhiteListEntry(entry));
            } else {
                current.getPlayerList().getWhiteList().remove(entry);
            }
            return true;
        } catch (Exception e) {
            SculkLinkCore.LOGGER.warn("Could not update whitelist for {}: {}", playerName, e.getMessage());
            return false;
        }
    }

    @Override
    public double tps() {
        MinecraftServer current = this.server;
        if (current == null) {
            return -1;
        }
        long averageNanos = current.getAverageTickTimeNanos();
        if (averageNanos <= 0) {
            return 20.0;
        }
        // A tick is 50ms; anything faster than that still caps at 20 TPS.
        return Math.min(20.0, 1_000_000_000.0 / averageNanos);
    }

    // ---- Event registration ---------------------------------------------------------------------

    private void registerLifecycleEvents() {
        ServerLifecycleEvents.SERVER_STARTED.register(startedServer -> {
            this.server = startedServer;
            core.serverStarted();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(stoppingServer -> core.serverStopping());

        ServerLifecycleEvents.SERVER_STOPPED.register(stoppedServer -> {
            core.shutdown();
            this.server = null;
        });
    }

    private void registerPlayerEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, packetSender, joinedServer) -> {
            ServerPlayer player = handler.player;
            core.playerJoined(player.getScoreboardName(), player.getUUID().toString());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, disconnectedServer) -> {
            ServerPlayer player = handler.player;
            core.playerLeft(player.getScoreboardName(), player.getUUID().toString());
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, chatSender, params) ->
                core.playerChat(chatSender.getScoreboardName(), chatSender.getUUID().toString(),
                        message.signedContent()));

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                // Ask the DamageSource, NOT the combat tracker. Vanilla reads the tracker from
                // inside die(); this event fires at the end of die(), by which point the tracker
                // has been reset and returns the bare "<player> died" fallback. That produced a
                // Discord message that disagreed with the one players saw in chat.
                core.playerDied(damageSource.getLocalizedDeathMessage(player).getString(),
                        player.getScoreboardName(), player.getUUID().toString());
            }
        });
    }

    /**
     * Registers {@code /discord}, which prints the server's invite link. Skipped entirely when
     * no invite is configured, so the command never exists to advertise a blank link.
     */
    private void registerCommands() {
        // Both commands always register and explain themselves when switched off. Omitting them
        // entirely — which is what this used to do — means an operator sees "Unknown command"
        // with no hint that a config key controls it. Paper always registers them (plugin.yml
        // does not do conditionals), so this also keeps the three platforms behaving alike.
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("discord").executes(context -> {
                String invite = core.config().discordInvite;
                context.getSource().sendSuccess(() -> Component.literal(invite.isBlank()
                        ? "§cNo Discord invite is configured (set `discord-invite`)."
                        : "§9Join us on Discord: §b" + invite), false);
                return 1;
            }));

            dispatcher.register(Commands.literal("link").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                if (!core.linkingEnabled()) {
                    player.sendSystemMessage(Component.literal(
                            "§cAccount linking is disabled (set `enable-linking`)."));
                    return 0;
                }
                String code = core.createLinkCode(player.getUUID().toString(),
                        player.getScoreboardName());
                // Sent only to the player who ran it — a code visible in public chat could be
                // redeemed by whoever reads it first.
                player.sendSystemMessage(Component.literal(
                        "§9Send §b" + core.config().commandPrefix + "link " + code
                                + "§9 in Discord to link your account."));
                return 1;
            }));
        });
    }

    // ---- Called from the advancement mixin ----------------------------------------------------------

    /** Invoked when a player completes an advancement that vanilla would announce in chat. */
    public void onAdvancement(ServerPlayer player, AdvancementHolder advancement) {
        DisplayInfo display = advancement.value().display().orElse(null);
        if (display == null || !display.shouldAnnounceChat()) {
            return;
        }
        core.advancementEarned(player.getScoreboardName(), player.getUUID().toString(),
                verbFor(display.getType()), display.getTitle().getString());
    }

    /** Matches vanilla's own phrasing for each advancement frame type. */
    private static String verbFor(AdvancementType type) {
        return switch (type) {
            case CHALLENGE -> "completed the challenge";
            case GOAL -> "reached the goal";
            default -> "made the advancement";
        };
    }
}
