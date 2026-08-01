package net.sculklink.paper;

import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.sculklink.core.BridgePlatform;
import net.sculklink.core.SculkLinkCore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Paper glue. The same {@link SculkLinkCore} as Fabric, behind a completely different server API
 * — Bukkit shares nothing with Mojang's own classes.
 *
 * <p>Notably there is no mixin here: Bukkit has a real {@link PlayerAdvancementDoneEvent}, so the
 * bytecode surgery Fabric needs to spot advancements is simply unnecessary.
 *
 * <p>This targets <b>Paper</b> and its forks rather than Spigot. Paper has replaced the legacy
 * chat and advancement APIs with its own ({@link AsyncChatEvent},
 * {@link io.papermc.paper.advancement.AdvancementDisplay}), and {@code Advancement#getDisplay()}
 * now returns a Paper type — so a jar compiled for one cannot bind against the other for those
 * two events.
 */
public final class SculkLinkPaper extends JavaPlugin implements BridgePlatform, Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private SculkLinkCore core;

    @Override
    public void onEnable() {
        core = new SculkLinkCore(this);
        getServer().getPluginManager().registerEvents(this, this);

        // Bukkit has no "server fully started" event, so defer a tick — by then the server is
        // accepting players, which is what the "online" announcement is supposed to mean.
        getServer().getScheduler().runTask(this, () -> core.serverStarted());
    }

    @Override
    public void onDisable() {
        core.serverStopping();
        core.shutdown();
    }

    // ---- BridgePlatform ------------------------------------------------------------------------

    @Override
    public Path configDir() {
        return getDataFolder().toPath();
    }

    @Override
    public void runOnMainThread(Runnable task) {
        // Chat arrives on an async thread here, so this is not merely a Discord-side concern.
        if (getServer().isPrimaryThread()) {
            task.run();
        } else if (isEnabled()) {
            getServer().getScheduler().runTask(this, task);
        }
    }

    @Override
    public void broadcast(String legacyMessage) {
        getServer().broadcast(LEGACY.deserialize(legacyMessage));
    }

    @Override
    public List<String> onlinePlayerNames() {
        return getServer().getOnlinePlayers().stream().map(Player::getName).toList();
    }

    @Override
    public int maxPlayers() {
        return getServer().getMaxPlayers();
    }

    /**
     * Runs a command as console. Bukkit writes command output to the console sender rather than
     * returning it, and there is no supported way to swap that sender out, so unlike the Fabric
     * side this can only report whether the command was accepted.
     */
    @Override
    public String executeConsoleCommand(String command) {
        try {
            boolean accepted = getServer().dispatchCommand(getServer().getConsoleSender(), command);
            return accepted
                    ? "Ran `" + command + "` — see server console for output."
                    : "Unknown command: " + command;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public boolean setWhitelisted(String playerName, String uuid, boolean whitelisted) {
        try {
            getServer().getOfflinePlayer(UUID.fromString(uuid)).setWhitelisted(whitelisted);
            return true;
        } catch (Exception e) {
            getSLF4JLogger().warn("Could not update whitelist for {}: {}", playerName, e.getMessage());
            return false;
        }
    }

    @Override
    public double tps() {
        double[] tps = getServer().getTPS();
        return tps.length == 0 ? -1 : tps[0];
    }

    // ---- Bukkit events -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        core.playerJoined(player.getName(), player.getUniqueId().toString());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        core.playerLeft(player.getName(), player.getUniqueId().toString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        core.playerChat(player.getName(), player.getUniqueId().toString(), plain(event.message()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Component message = event.deathMessage();
        if (message == null) {
            return;
        }
        String text = plain(message);
        if (!text.isBlank()) {
            Player player = event.getEntity();
            core.playerDied(text, player.getName(), player.getUniqueId().toString());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        AdvancementDisplay display = event.getAdvancement().getDisplay();
        // Recipe and hidden advancements have no display or are not announced. Matching vanilla
        // here is what stops Discord filling up with every recipe unlock.
        if (display == null || !display.doesAnnounceToChat()) {
            return;
        }
        Player player = event.getPlayer();
        core.advancementEarned(player.getName(), player.getUniqueId().toString(),
                verbFor(display.frame()), plain(display.title()));
    }

    /** Matches vanilla's own phrasing for each advancement frame type. */
    private static String verbFor(AdvancementDisplay.Frame frame) {
        return switch (frame) {
            case CHALLENGE -> "completed the challenge";
            case GOAL -> "reached the goal";
            default -> "made the advancement";
        };
    }

    /**
     * Adventure component to plain text. Goes via the legacy serializer and strips the codes
     * rather than using PlainTextComponentSerializer, which is not on paper-api's compile
     * classpath — this needs no dependency that might be absent at runtime.
     */
    private static String plain(Component component) {
        return LEGACY.serialize(component).replaceAll("§[0-9A-Fa-fK-Ok-orR]", "");
    }

    // ---- /discord --------------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("discord")) {
            String invite = core.config().discordInvite;
            sender.sendMessage(LEGACY.deserialize(invite.isBlank()
                    ? "§cNo Discord invite is configured."
                    : "§9Join us on Discord: §b" + invite));
            return true;
        }

        if (command.getName().equalsIgnoreCase("link")) {
            if (!core.linkingEnabled()) {
                sender.sendMessage(LEGACY.deserialize("§cAccount linking is disabled."));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(LEGACY.deserialize("§cOnly players can link an account."));
                return true;
            }
            String code = core.createLinkCode(player.getUniqueId().toString(), player.getName());
            // Sent only to the player who ran it — a code visible in public chat could be
            // redeemed by whoever reads it first.
            player.sendMessage(LEGACY.deserialize("§9Send §b" + core.config().commandPrefix
                    + "link " + code + "§9 in Discord to link your account."));
            return true;
        }

        return false;
    }
}
