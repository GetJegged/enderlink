package net.enderlink.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything the bridge needs from the server it is running on.
 *
 * <p>This is the entire surface a new platform has to implement. Fabric, NeoForge and Paper
 * share no server API at all — Bukkit is a different world from Mojang's own classes — but all
 * of them can answer these five questions, so the ~1,000 lines of Discord code behind it never
 * needs to know which one it is talking to.
 *
 * <p>Deliberately narrow. Anything added here has to be implemented again on every platform, so
 * new functionality belongs in {@link EnderLinkCore} wherever it possibly can.
 */
public interface BridgePlatform {
    /** Directory the config file lives in. Created if absent. */
    Path configDir();

    /**
     * Runs a task on the server's main thread. Discord arrives on a network thread, and no
     * platform here tolerates its world or player state being touched from one.
     */
    void runOnMainThread(Runnable task);

    /** Shows a message to every online player. Uses section-sign colour codes. */
    void broadcast(String legacyMessage);

    /** Names of currently online players. Called on the main thread. */
    List<String> onlinePlayerNames();

    /** Configured player cap, for the "N/M online" status. */
    int maxPlayers();
}
