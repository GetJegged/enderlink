package net.enderlink.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import net.enderlink.core.BridgePlatform;
import net.enderlink.core.EnderLinkCore;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserWhiteListEntry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * NeoForge glue. Same {@link EnderLinkCore} and the same underlying Minecraft classes as the
 * Fabric module — 26.x ships unobfuscated, so only the event plumbing differs.
 *
 * <p>No mixin here either: NeoForge has {@link AdvancementEvent.AdvancementEarnEvent}, so the
 * bytecode surgery Fabric needs to spot advancements is unnecessary.
 */
@Mod(value = "enderlink", dist = Dist.DEDICATED_SERVER)
public final class EnderLinkNeoForge implements BridgePlatform {
    private final EnderLinkCore core;
    /** Volatile: written on the server thread, read on the gateway thread when Discord speaks. */
    private volatile MinecraftServer server;

    public EnderLinkNeoForge(ModContainer container) {
        // Built at mod construction, not at server start: RegisterCommandsEvent fires first and
        // needs the config, and loading it twice would mean two reads and two writes of the same
        // file. FMLPaths is resolved long before either event.
        this.core = new EnderLinkCore(this);
        NeoForge.EVENT_BUS.register(this);
    }

    // ---- BridgePlatform ------------------------------------------------------------------------

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
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

    /** Runs a command as console, capturing its output through a substituted command source. */
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
            CommandSourceStack stack = current.createCommandSourceStack().withSource(collector);
            current.getCommands().performPrefixedCommand(stack, command);
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
            EnderLinkCore.LOGGER.warn("Could not update whitelist for {}: {}", playerName, e.getMessage());
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

    // ---- NeoForge events ---------------------------------------------------------------------------

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        this.server = event.getServer();
        core.serverStarted();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        core.serverStopping();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        core.shutdown();
        this.server = null;
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            core.playerJoined(player.getScoreboardName(), player.getUUID().toString());
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            core.playerLeft(player.getScoreboardName(), player.getUUID().toString());
        }
    }

    // LOWEST so every other listener has had its say first. ServerChatEvent and LivingDeathEvent
    // are both cancellable, and relaying a message a chat filter cancelled — or a death a totem
    // prevented — would put things in Discord that never happened in game.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        core.playerChat(player.getScoreboardName(), player.getUUID().toString(), event.getRawText());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Ask the DamageSource, not the combat tracker — see the Fabric module for why the
            // tracker returns a bare "<player> died" by the time a death event fires.
            core.playerDied(event.getSource().getLocalizedDeathMessage(player).getString(),
                    player.getScoreboardName(), player.getUUID().toString());
        }
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        AdvancementHolder advancement = event.getAdvancement();
        DisplayInfo display = advancement.value().display().orElse(null);
        // Recipe and hidden advancements are not announced in chat; matching vanilla here is
        // what stops Discord filling up with every recipe unlock.
        if (display == null || !display.shouldAnnounceChat()) {
            return;
        }
        core.advancementEarned(player.getScoreboardName(), player.getUUID().toString(),
                verbFor(display.getType()), display.getTitle().getString());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        String invite = core.config().discordInvite;

        if (!invite.isBlank()) {
            dispatcher.register(Commands.literal("discord").executes(context -> {
                context.getSource().sendSuccess(
                        () -> Component.literal("§9Join us on Discord: §b" + invite), false);
                return 1;
            }));
        }

        if (core.linkingEnabled()) {
            dispatcher.register(Commands.literal("link").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                String code = core.createLinkCode(player.getUUID().toString(),
                        player.getScoreboardName());
                // Sent only to the player who ran it — a code visible in public chat could be
                // redeemed by whoever reads it first.
                player.sendSystemMessage(Component.literal(
                        "§9Send §b" + core.config().commandPrefix + "link " + code
                                + "§9 in Discord to link your account."));
                return 1;
            }));
        }
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
