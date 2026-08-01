package net.enderlink;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Everything the operator can tune, read from {@code config/enderlink.json}.
 *
 * <p>The file is written back out after loading so that a config from an older version of
 * the mod gains any newly-added keys (with their defaults) instead of silently ignoring them.
 */
public final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---- Discord credentials -------------------------------------------------------------

    /**
     * Channel webhook URL, used for everything this mod posts TO Discord. A webhook is what
     * lets each message carry the player's own name and skin as its avatar, which a plain bot
     * post cannot do. Leave blank to disable all Minecraft -> Discord traffic.
     */
    @SerializedName("webhook-url")
    public String webhookUrl = "";

    /**
     * Bot token, used only for reading Discord -> Minecraft. Leave blank to run one-way.
     * Treat this like a password: anyone holding it controls the bot.
     */
    @SerializedName("bot-token")
    public String botToken = "";

    /** Numeric ID of the channel to mirror. Required for Discord -> Minecraft. */
    @SerializedName("channel-id")
    public String channelId = "";

    // ---- What gets relayed ---------------------------------------------------------------

    /** Master switch for reading Discord messages into in-game chat. */
    @SerializedName("discord-to-minecraft")
    public boolean discordToMinecraft = true;

    /**
     * Relay in-game player chat to Discord. This is the headline feature of a chat bridge, so
     * it is on by default; busy servers that only want event notifications turn it off.
     */
    @SerializedName("relay-chat")
    public boolean relayChat = true;

    @SerializedName("relay-join-leave")
    public boolean relayJoinLeave = true;

    @SerializedName("relay-deaths")
    public boolean relayDeaths = true;

    @SerializedName("relay-advancements")
    public boolean relayAdvancements = true;

    @SerializedName("relay-server-status")
    public boolean relayServerStatus = true;

    // ---- Presentation --------------------------------------------------------------------

    /** Name shown on the status/death/advancement embeds. */
    @SerializedName("server-name")
    public String serverName = "Minecraft Server";

    /**
     * Avatar for relayed player messages. {@code {uuid}} and {@code {name}} are substituted.
     * mc-heads.net renders the player's actual skin head from their UUID.
     */
    @SerializedName("avatar-url")
    public String avatarUrl = "https://mc-heads.net/avatar/{uuid}/64";

    /**
     * How a Discord message looks in-game. {@code {name}} is the Discord display name and
     * {@code {message}} the content. Uses section-sign colour codes.
     */
    @SerializedName("discord-chat-format")
    public String discordChatFormat = "§9[Discord] §b{name}§r: {message}";

    /** Discord messages longer than this are truncated before hitting in-game chat. */
    @SerializedName("max-message-length")
    public int maxMessageLength = 256;

    // ---- Derived helpers -----------------------------------------------------------------

    public boolean outboundEnabled() {
        return !webhookUrl.isBlank();
    }

    public boolean inboundEnabled() {
        return discordToMinecraft && !botToken.isBlank() && !channelId.isBlank();
    }

    // ---- Load / save ---------------------------------------------------------------------

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("enderlink.json");
    }

    /**
     * Reads the config, creating it with defaults if absent. A malformed file is never
     * overwritten — the operator's typo is more valuable than a fresh default, and silently
     * discarding a file that contains a bot token would be hostile.
     */
    public static BridgeConfig load() {
        Path path = configPath();
        BridgeConfig config = new BridgeConfig();

        if (Files.exists(path)) {
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                BridgeConfig parsed = GSON.fromJson(json, BridgeConfig.class);
                if (parsed != null) {
                    config = parsed;
                }
            } catch (IOException | JsonSyntaxException e) {
                EnderLink.LOGGER.error(
                        "Could not read {} ({}). Running with defaults — the file was left untouched, fix it and restart.",
                        path, e.getMessage());
                return config;
            }
        }

        config.save();
        return config;
    }

    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            EnderLink.LOGGER.error("Could not write {}: {}", path, e.getMessage());
        }
    }
}
