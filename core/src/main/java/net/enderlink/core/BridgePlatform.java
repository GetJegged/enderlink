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

    // ---- Optional capabilities -------------------------------------------------------------------
    // Defaulted so a new platform can be brought up without implementing everything at once, and
    // so a platform that genuinely cannot do one of these degrades to a clear message rather than
    // a crash.

    /**
     * Runs a command as the server console and returns its output.
     *
     * @return command output, or {@code null} if this platform cannot run commands
     */
    default String executeConsoleCommand(String command) {
        return null;
    }

    /**
     * Adds or removes a whitelist entry.
     *
     * @return false if unsupported or the change failed
     */
    default boolean setWhitelisted(String playerName, String uuid, boolean whitelisted) {
        return false;
    }

    /** Recent ticks per second, or a negative value if the platform does not expose it. */
    default double tps() {
        return -1;
    }
}
