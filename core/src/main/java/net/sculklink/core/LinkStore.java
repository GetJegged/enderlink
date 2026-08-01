package net.sculklink.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft account &lt;-&gt; Discord account links, persisted to {@code links.json}.
 *
 * <p>The flow deliberately starts <b>in Minecraft</b>: a player runs {@code /link}, gets a
 * short-lived code, and types {@code !link CODE} in Discord. Starting there means the server has
 * already authenticated the Minecraft side, so the code only has to prove the Discord side.
 * Starting in Discord would let anyone claim any username.
 *
 * <p>Codes are single-use, expire, and come from {@link SecureRandom} — a guessable code would
 * let someone link themselves to another player's account, and with {@code whitelist-on-link}
 * that is a way onto the server.
 */
public final class LinkStore {
    /** No vowels, no 0/O/1/I — unambiguous when read aloud or retyped. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A code waiting to be redeemed in Discord. */
    private record Pending(String uuid, String playerName, long expiresAt) { }

    private final Path path;
    private final long codeLifetimeMillis;

    /** discordId -> minecraft uuid, and the reverse, both persisted. */
    private final Map<String, String> discordToMinecraft = new ConcurrentHashMap<>();
    private final Map<String, String> minecraftToDiscord = new ConcurrentHashMap<>();
    private final Map<String, String> names = new ConcurrentHashMap<>();

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public LinkStore(Path configDir, int codeLifetimeMinutes) {
        this.path = configDir.resolve("links.json");
        this.codeLifetimeMillis = Math.max(1, codeLifetimeMinutes) * 60_000L;
        load();
    }

    // ---- Linking -------------------------------------------------------------------------------

    /** Issues a code for a player to redeem in Discord. Replaces any previous code they held. */
    public String createCode(String uuid, String playerName) {
        pending.values().removeIf(p -> p.uuid().equals(uuid));
        purgeExpired();

        String code;
        do {
            StringBuilder builder = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            code = builder.toString();
        } while (pending.containsKey(code));

        pending.put(code, new Pending(uuid, playerName, System.currentTimeMillis() + codeLifetimeMillis));
        return code;
    }

    /**
     * Redeems a code on behalf of a Discord user.
     *
     * @return the linked player's name, or null if the code is unknown or expired
     */
    public String redeem(String code, String discordId) {
        purgeExpired();
        Pending entry = pending.remove(code == null ? "" : code.trim().toUpperCase(Locale.ROOT));
        if (entry == null || entry.expiresAt() < System.currentTimeMillis()) {
            return null;
        }

        // A Discord account links to exactly one Minecraft account and vice versa; re-linking
        // replaces the old pairing rather than accumulating stale entries.
        String previousMinecraft = discordToMinecraft.remove(discordId);
        if (previousMinecraft != null) {
            minecraftToDiscord.remove(previousMinecraft);
            names.remove(previousMinecraft);
        }
        String previousDiscord = minecraftToDiscord.remove(entry.uuid());
        if (previousDiscord != null) {
            discordToMinecraft.remove(previousDiscord);
        }

        discordToMinecraft.put(discordId, entry.uuid());
        minecraftToDiscord.put(entry.uuid(), discordId);
        names.put(entry.uuid(), entry.playerName());
        save();
        return entry.playerName();
    }

    /** @return the unlinked player's name, or null if that Discord account was not linked */
    public String unlink(String discordId) {
        String uuid = discordToMinecraft.remove(discordId);
        if (uuid == null) {
            return null;
        }
        minecraftToDiscord.remove(uuid);
        String name = names.remove(uuid);
        save();
        return name;
    }

    public String minecraftUuidFor(String discordId) {
        return discordToMinecraft.get(discordId);
    }

    public String playerNameFor(String discordId) {
        String uuid = discordToMinecraft.get(discordId);
        return uuid == null ? null : names.get(uuid);
    }

    public boolean isLinked(String uuid) {
        return minecraftToDiscord.containsKey(uuid);
    }

    public int size() {
        return discordToMinecraft.size();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> p.expiresAt() < now);
    }

    // ---- Persistence ------------------------------------------------------------------------------

    /** On-disk shape. Kept separate from the live maps so the file stays readable and editable. */
    private static final class Data {
        Map<String, String> discordToMinecraft = new HashMap<>();
        Map<String, String> names = new HashMap<>();
    }

    private void load() {
        if (!Files.exists(path)) {
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Data data = GSON.fromJson(json, TypeToken.get(Data.class).getType());
            if (data == null || data.discordToMinecraft == null) {
                return;
            }
            data.discordToMinecraft.forEach((discordId, uuid) -> {
                discordToMinecraft.put(discordId, uuid);
                minecraftToDiscord.put(uuid, discordId);
            });
            if (data.names != null) {
                names.putAll(data.names);
            }
            SculkLinkCore.LOGGER.info("Loaded {} Discord account link(s)", discordToMinecraft.size());
        } catch (IOException | RuntimeException e) {
            // Never overwrite a file we could not understand — it is the only copy of the links.
            SculkLinkCore.LOGGER.error("Could not read {} ({}); linking will start empty and the "
                    + "file will not be overwritten until a successful link.", path, e.getMessage());
        }
    }

    private synchronized void save() {
        Data data = new Data();
        data.discordToMinecraft.putAll(discordToMinecraft);
        data.names.putAll(names);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SculkLinkCore.LOGGER.error("Could not write {}: {}", path, e.getMessage());
        }
    }
}
