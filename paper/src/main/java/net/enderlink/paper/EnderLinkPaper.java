package net.enderlink.paper;

import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.enderlink.core.BridgePlatform;
import net.enderlink.core.EnderLinkCore;
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

/**
 * Paper glue. The same {@link EnderLinkCore} as Fabric, behind a completely different server API
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
public final class EnderLinkPaper extends JavaPlugin implements BridgePlatform, Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private EnderLinkCore core;

    @Override
    public void onEnable() {
        core = new EnderLinkCore(this);
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

    // ---- Bukkit events -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        core.playerJoined(player.getName(), player.getUniqueId().toString());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Bukkit still lists the player while the quit event runs.
        core.playerLeft(player.getName(), player.getUniqueId().toString(), true);
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
        if (!command.getName().equalsIgnoreCase("discord")) {
            return false;
        }
        String invite = core.config().discordInvite;
        sender.sendMessage(LEGACY.deserialize(invite.isBlank()
                ? "§cNo Discord invite is configured."
                : "§9Join us on Discord: §b" + invite));
        return true;
    }
}
