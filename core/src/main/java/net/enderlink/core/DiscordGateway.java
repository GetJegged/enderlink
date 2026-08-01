package net.enderlink.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discord -> Minecraft, over Discord's gateway (WebSocket) API.
 *
 * <p>This is the real-time half of the bridge. It speaks gateway v10 directly on the JDK's
 * {@link WebSocket} rather than pulling in a Discord library, which keeps the jar dependency-free
 * but means the connection lifecycle is ours to run: heartbeat on the interval Discord names,
 * resume a dropped session where it left off, and back off when reconnecting.
 *
 * <p>Two failure modes are treated as fatal rather than retried, because retrying them just
 * spams the log forever: a rejected token (4004) and missing intents (4014). Both are fixed in
 * the Discord developer portal, not by trying again.
 */
public final class DiscordGateway {
    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";

    /** GUILD_MESSAGES (1 << 9) | MESSAGE_CONTENT (1 << 15). */
    private static final int INTENTS = 512 | 32768;

    private static final int OP_DISPATCH = 0;
    private static final int OP_HEARTBEAT = 1;
    private static final int OP_IDENTIFY = 2;
    private static final int OP_RESUME = 6;
    private static final int OP_RECONNECT = 7;
    private static final int OP_INVALID_SESSION = 9;
    private static final int OP_HELLO = 10;
    private static final int OP_HEARTBEAT_ACK = 11;

    private static final int CLOSE_AUTH_FAILED = 4004;
    private static final int CLOSE_INVALID_INTENTS = 4013;
    private static final int CLOSE_DISALLOWED_INTENTS = 4014;

    /** {@code <@123>}, {@code <@!123>} — a user mention as it arrives on the wire. */
    private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d+)>");
    /** {@code <:name:123>} / {@code <a:name:123>} — custom emoji. */
    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?:([A-Za-z0-9_]+):\\d+>");

    /**
     * A message as it matters to the bridge. Carries the author's role ids because the
     * management channel has to answer "is this person allowed to run commands?", and role ids
     * arrive free on guild messages — asking Discord separately would need a privileged intent.
     */
    public record IncomingMessage(String channelId, String authorId, String authorName,
                                  String content, Set<String> roleIds) { }

    /** Called on the gateway thread; the handler is responsible for hopping to the server thread. */
    @FunctionalInterface
    public interface MessageHandler {
        void onDiscordMessage(IncomingMessage message);
    }

    private final BridgeConfig config;
    private final MessageHandler handler;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);

    // Connection state. Only touched from the listener and scheduler threads.
    private volatile WebSocket socket;
    private volatile String sessionId;
    private volatile String resumeUrl;
    private volatile Integer lastSequence;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile boolean awaitingAck;
    private volatile int reconnectAttempts;

    // Gateway frames can be split across several onText callbacks; reassemble here.
    private final StringBuilder textBuffer = new StringBuilder();

    // Name -> id, so "@blake" typed in Minecraft can become a real ping. Users are learned from
    // messages as they arrive rather than fetched, because listing guild members needs the
    // privileged GUILD_MEMBERS intent and this does not. The trade-off is that a user has to
    // have spoken (or been mentioned) once before they can be pinged from in-game.
    private final Map<String, String> userIds = new ConcurrentHashMap<>();
    private final Map<String, String> roleIds = new ConcurrentHashMap<>();
    private volatile String guildId;

    // WebSocket.sendText must not be called again until the previous send completes, and we
    // send from two threads (heartbeats and handshakes), so all writes go through one chain.
    private final Object sendLock = new Object();
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    public DiscordGateway(BridgeConfig config, MessageHandler handler) {
        this.config = config;
        this.handler = handler;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "enderlink-gateway");
            t.setDaemon(true);
            return t;
        });
    }

    // ---- Lifecycle -------------------------------------------------------------------------

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        connect(GATEWAY_URL);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        cancelHeartbeat();

        WebSocket ws = socket;
        if (ws != null) {
            // 1000 tells Discord this was deliberate, so the session is closed rather than
            // left around for a resume that will never come.
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping");
        }
        scheduler.shutdownNow();
    }

    // ---- Connection ------------------------------------------------------------------------

    private void connect(String url) {
        if (!running.get()) {
            return;
        }
        EnderLinkCore.LOGGER.info("Connecting to Discord gateway…");

        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create(url), new Listener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        EnderLinkCore.LOGGER.warn("Gateway connection failed: {}", error.getMessage());
                        scheduleReconnect();
                    } else {
                        socket = ws;
                    }
                });
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }

        // Several paths can decide to reconnect (a close frame, a socket error, a failed
        // handshake). Letting two of them through would open two sockets at once, which
        // duplicates every relayed message and interleaves fragments into one text buffer.
        if (!reconnectPending.compareAndSet(false, true)) {
            return;
        }
        cancelHeartbeat();

        // 1s, 2s, 4s … capped at 60s, so a long Discord outage settles into one attempt a
        // minute instead of hammering.
        long delay = Math.min(1L << Math.min(reconnectAttempts, 6), 60L);
        reconnectAttempts++;
        EnderLinkCore.LOGGER.info("Reconnecting to Discord in {}s", delay);

        try {
            scheduler.schedule(() -> {
                reconnectPending.set(false);
                String url = (sessionId != null && resumeUrl != null)
                        ? resumeUrl + "?v=10&encoding=json"
                        : GATEWAY_URL;
                connect(url);
            }, delay, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // stop() already shut the scheduler down.
            reconnectPending.set(false);
        }
    }

    private void fatal(String message) {
        EnderLinkCore.LOGGER.error("EnderLink inbound disabled: {}", message);
        running.set(false);
        cancelHeartbeat();
    }

    // ---- Heartbeat ---------------------------------------------------------------------------

    private void startHeartbeat(long intervalMillis) {
        cancelHeartbeat();
        awaitingAck = false;

        // Discord asks for a random offset on the first beat so that a fleet of clients
        // reconnecting after an outage does not beat in lockstep.
        long firstDelay = (long) (intervalMillis * 0.5);

        try {
            heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
                if (awaitingAck) {
                    // Last beat was never acknowledged: the socket is open but dead. 4000 is
                    // a non-1000 code, which tells Discord to keep the session resumable.
                    // Stop beating first, or this fires again every interval while the close
                    // is in flight.
                    EnderLinkCore.LOGGER.warn("Discord did not acknowledge our heartbeat, reconnecting");
                    cancelHeartbeat();
                    WebSocket ws = socket;
                    if (ws != null) {
                        ws.sendClose(4000, "heartbeat not acknowledged");
                    }
                    return;
                }
                awaitingAck = true;
                JsonObject beat = new JsonObject();
                beat.addProperty("op", OP_HEARTBEAT);
                if (lastSequence == null) {
                    beat.add("d", com.google.gson.JsonNull.INSTANCE);
                } else {
                    beat.addProperty("d", lastSequence);
                }
                send(beat);
            }, firstDelay, intervalMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Shutting down.
        }
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
    }

    // ---- Sending ------------------------------------------------------------------------------

    private void send(JsonObject payload) {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        String text = payload.toString();
        synchronized (sendLock) {
            sendChain = sendChain
                    .handle((result, error) -> null)   // a failed send must not poison the chain
                    .thenCompose(ignored -> ws.sendText(text, true));
        }
    }

    private void sendIdentify() {
        JsonObject properties = new JsonObject();
        properties.addProperty("os", System.getProperty("os.name", "linux"));
        properties.addProperty("browser", "enderlink");
        properties.addProperty("device", "enderlink");

        JsonObject d = new JsonObject();
        d.addProperty("token", config.botToken);
        d.addProperty("intents", INTENTS);
        d.add("properties", properties);

        JsonObject payload = new JsonObject();
        payload.addProperty("op", OP_IDENTIFY);
        payload.add("d", d);
        send(payload);
    }

    private void sendResume() {
        JsonObject d = new JsonObject();
        d.addProperty("token", config.botToken);
        d.addProperty("session_id", sessionId);
        d.addProperty("seq", lastSequence == null ? 0 : lastSequence);

        JsonObject payload = new JsonObject();
        payload.addProperty("op", OP_RESUME);
        payload.add("d", d);
        send(payload);
    }

    // ---- Frame handling ------------------------------------------------------------------------

    private void handleFrame(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            EnderLinkCore.LOGGER.warn("Unparseable gateway frame: {}", e.getMessage());
            return;
        }

        if (root.has("s") && !root.get("s").isJsonNull()) {
            lastSequence = root.get("s").getAsInt();
        }

        int op = root.has("op") ? root.get("op").getAsInt() : -1;
        switch (op) {
            case OP_HELLO -> {
                long interval = root.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                startHeartbeat(interval);
                if (sessionId != null) {
                    sendResume();
                } else {
                    sendIdentify();
                }
            }
            case OP_HEARTBEAT -> {
                // Discord can ask for an immediate beat outside the normal interval.
                awaitingAck = false;
                JsonObject beat = new JsonObject();
                beat.addProperty("op", OP_HEARTBEAT);
                if (lastSequence == null) {
                    beat.add("d", com.google.gson.JsonNull.INSTANCE);
                } else {
                    beat.addProperty("d", lastSequence);
                }
                send(beat);
            }
            case OP_HEARTBEAT_ACK -> awaitingAck = false;
            case OP_RECONNECT -> {
                WebSocket ws = socket;
                if (ws != null) {
                    ws.sendClose(4000, "gateway asked us to reconnect");
                }
            }
            case OP_INVALID_SESSION -> {
                boolean resumable = root.has("d") && root.get("d").isJsonPrimitive()
                        && root.get("d").getAsBoolean();
                if (!resumable) {
                    sessionId = null;
                    resumeUrl = null;
                    lastSequence = null;
                }
                WebSocket ws = socket;
                if (ws != null) {
                    ws.sendClose(4000, "invalid session");
                }
            }
            case OP_DISPATCH -> handleDispatch(root);
            default -> { /* Nothing else is relevant to a read-only bridge. */ }
        }
    }

    private void handleDispatch(JsonObject root) {
        String type = root.has("t") && !root.get("t").isJsonNull() ? root.get("t").getAsString() : "";
        JsonObject d = root.has("d") && root.get("d").isJsonObject() ? root.getAsJsonObject("d") : null;
        if (d == null) {
            return;
        }

        switch (type) {
            case "READY" -> {
                sessionId = optString(d, "session_id");
                resumeUrl = optString(d, "resume_gateway_url");
                reconnectAttempts = 0;
                String botName = d.has("user") ? optString(d.getAsJsonObject("user"), "username") : "bot";
                EnderLinkCore.LOGGER.info("Connected to Discord as {}", botName);
            }
            case "RESUMED" -> {
                reconnectAttempts = 0;
                EnderLinkCore.LOGGER.info("Resumed Discord session");
            }
            case "MESSAGE_CREATE" -> handleMessage(d);
            default -> { }
        }
    }

    private void handleMessage(JsonObject d) {
        // Two channels are interesting: the bridged chat channel and, if configured, the
        // separate management channel.
        String channelId = optString(d, "channel_id");
        boolean isChat = config.channelId.equals(channelId);
        boolean isManagement = !config.managementChannelId.isBlank()
                && config.managementChannelId.equals(channelId);
        if (!isChat && !isManagement) {
            return;
        }

        // Ignore anything posted by a webhook — our own outbound messages arrive back here,
        // and relaying them would put the bridge in a loop with itself.
        if (d.has("webhook_id")) {
            return;
        }

        JsonObject author = d.has("author") && d.get("author").isJsonObject()
                ? d.getAsJsonObject("author") : null;
        if (author == null) {
            return;
        }
        if (author.has("bot") && author.get("bot").getAsBoolean()) {
            return;
        }

        learnNames(d, author);

        String content = optString(d, "content");
        content = resolveMentions(content, d);

        // A bare image or file post has empty content but is still worth announcing in-game.
        if (content.isBlank()) {
            if (d.has("attachments") && d.getAsJsonArray("attachments").size() > 0) {
                content = "[attachment]";
            } else {
                return;
            }
        }

        handler.onDiscordMessage(new IncomingMessage(
                channelId, optString(author, "id"), displayNameOf(d, author), content, rolesOf(d)));
    }

    /** Role ids the author holds in this guild, as carried on the message's member object. */
    private static Set<String> rolesOf(JsonObject message) {
        if (!message.has("member") || !message.get("member").isJsonObject()) {
            return Set.of();
        }
        JsonObject member = message.getAsJsonObject("member");
        if (!member.has("roles") || !member.get("roles").isJsonArray()) {
            return Set.of();
        }
        Set<String> roles = new HashSet<>();
        for (JsonElement element : member.getAsJsonArray("roles")) {
            if (element.isJsonPrimitive()) {
                roles.add(element.getAsString());
            }
        }
        return roles;
    }

    /**
     * Records every name/id pair visible in a message so it can be pinged from Minecraft later.
     * The first message also reveals the guild, which is when roles get fetched.
     */
    private void learnNames(JsonObject message, JsonObject author) {
        remember(author);
        if (message.has("member") && message.get("member").isJsonObject()) {
            String nick = optString(message.getAsJsonObject("member"), "nick");
            String id = optString(author, "id");
            if (!nick.isBlank() && !id.isBlank()) {
                userIds.put(nick.toLowerCase(Locale.ROOT), id);
            }
        }
        if (message.has("mentions") && message.get("mentions").isJsonArray()) {
            for (JsonElement element : message.getAsJsonArray("mentions")) {
                if (element.isJsonObject()) {
                    remember(element.getAsJsonObject());
                }
            }
        }

        String gid = optString(message, "guild_id");
        if (!gid.isBlank() && guildId == null) {
            guildId = gid;
            fetchRoles(gid);
        }
    }

    private void remember(JsonObject user) {
        String id = optString(user, "id");
        if (id.isBlank()) {
            return;
        }
        for (String key : new String[] {"username", "global_name"}) {
            String name = optString(user, key);
            if (!name.isBlank()) {
                userIds.put(name.toLowerCase(Locale.ROOT), id);
            }
        }
    }

    /**
     * Roles, unlike members, can be listed over REST without a privileged intent — so role
     * pings work for every role immediately rather than only after someone uses one.
     */
    private void fetchRoles(String gid) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/v10/guilds/" + gid + "/roles"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + config.botToken)
                .header("User-Agent", "EnderLink (Minecraft Fabric mod, 1.0.0)")
                .GET()
                .build();

        // Async on purpose. `scheduler` is the single thread that also sends heartbeats, so a
        // blocking REST call here could stall one long enough for Discord to treat the connection
        // as a zombie and drop it.
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        EnderLinkCore.LOGGER.warn("Could not list Discord roles (HTTP {}) — role pings "
                                + "from in-game will not work", response.statusCode());
                        return;
                    }
                    try {
                        for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonArray()) {
                            JsonObject role = element.getAsJsonObject();
                            String name = optString(role, "name");
                            String id = optString(role, "id");
                            if (!name.isBlank() && !id.isBlank() && !"@everyone".equals(name)) {
                                roleIds.put(name.toLowerCase(Locale.ROOT), id);
                            }
                        }
                        EnderLinkCore.LOGGER.info("Loaded {} Discord roles for mentions", roleIds.size());
                    } catch (RuntimeException e) {
                        EnderLinkCore.LOGGER.warn("Could not parse Discord roles: {}", e.getMessage());
                    }
                })
                .exceptionally(error -> {
                    EnderLinkCore.LOGGER.warn("Could not list Discord roles: {}", error.getMessage());
                    return null;
                });
    }

    /**
     * Posts to an arbitrary channel as the bot. The webhook can only write to the one channel it
     * belongs to, so management-channel replies have to go through the bot's REST API instead.
     */
    public void sendChannelMessage(String channelId, String content) {
        if (config.botToken.isBlank() || channelId.isBlank()) {
            return;
        }
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());

        JsonObject body = new JsonObject();
        body.addProperty("content", DiscordSender.truncate(content, 1900));
        body.add("allowed_mentions", allowed);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/v10/channels/" + channelId + "/messages"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + config.botToken)
                .header("Content-Type", "application/json")
                .header("User-Agent", "EnderLink (Minecraft Fabric mod, 1.0.0)")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(),
                        java.nio.charset.StandardCharsets.UTF_8))
                .build();

        // Async for the same reason as fetchRoles: never block the heartbeat thread.
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenAccept(response -> {
                    if (response.statusCode() >= 300) {
                        EnderLinkCore.LOGGER.warn("Could not post to channel {} (HTTP {}) — does the "
                                + "bot have Send Messages there?", channelId, response.statusCode());
                    }
                })
                .exceptionally(error -> {
                    EnderLinkCore.LOGGER.warn("Could not post to channel {}: {}", channelId, error.getMessage());
                    return null;
                });
    }

    /** Discord user id for a name typed in Minecraft, or null if never seen. */
    public String lookupUser(String name) {
        return userIds.get(name.toLowerCase(Locale.ROOT));
    }

    /** Discord role id for a name typed in Minecraft, or null. */
    public String lookupRole(String name) {
        return roleIds.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Sets the bot's activity to the current player count. This is a gateway op, not a REST
     * call, so it is not rate limited the way editing a channel topic would be.
     */
    public void updatePresence(int online, int max) {
        if (socket == null) {
            return;
        }
        JsonObject activity = new JsonObject();
        activity.addProperty("name", online + "/" + max + " online");
        activity.addProperty("type", 0);        // "Playing …"

        JsonArray activities = new JsonArray();
        activities.add(activity);

        JsonObject d = new JsonObject();
        d.add("since", com.google.gson.JsonNull.INSTANCE);
        d.add("activities", activities);
        d.addProperty("status", "online");
        d.addProperty("afk", false);

        JsonObject payload = new JsonObject();
        payload.addProperty("op", 3);
        payload.add("d", d);
        send(payload);
    }

    /** Server nickname if they have one, else their Discord display name, else the username. */
    private static String displayNameOf(JsonObject message, JsonObject author) {
        if (message.has("member") && message.get("member").isJsonObject()) {
            String nick = optString(message.getAsJsonObject("member"), "nick");
            if (!nick.isBlank()) {
                return nick;
            }
        }
        String global = optString(author, "global_name");
        return global.isBlank() ? optString(author, "username") : global;
    }

    /**
     * Turns wire-format markup into something readable in chat: {@code <@1234>} becomes
     * {@code @Blake} and {@code <:pog:1234>} becomes {@code :pog:}. Without this, mentions
     * reach players as raw numeric IDs.
     */
    private static String resolveMentions(String content, JsonObject message) {
        if (content.isEmpty()) {
            return content;
        }

        String result = content;
        if (message.has("mentions") && message.get("mentions").isJsonArray()) {
            for (JsonElement element : message.getAsJsonArray("mentions")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject user = element.getAsJsonObject();
                String id = optString(user, "id");
                String global = optString(user, "global_name");
                String name = global.isBlank() ? optString(user, "username") : global;
                if (!id.isBlank() && !name.isBlank()) {
                    result = result.replaceAll("<@!?" + Pattern.quote(id) + ">",
                            Matcher.quoteReplacement("@" + name));
                }
            }
        }

        result = USER_MENTION.matcher(result).replaceAll("@unknown");
        result = CUSTOM_EMOJI.matcher(result).replaceAll(":$1:");
        return result;
    }

    private static String optString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    // ---- WebSocket listener ---------------------------------------------------------------------

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            synchronized (textBuffer) {
                textBuffer.setLength(0);
            }
            // Demand is not automatic: every callback consumes one unit, so each one must ask
            // for the next or the connection goes silent.
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String complete = null;
            synchronized (textBuffer) {
                textBuffer.append(data);
                if (last) {
                    complete = textBuffer.toString();
                    textBuffer.setLength(0);
                }
            }

            if (complete != null) {
                try {
                    handleFrame(complete);
                } catch (Exception e) {
                    EnderLinkCore.LOGGER.error("Error handling gateway frame", e);
                }
            }

            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            cancelHeartbeat();
            EnderLinkCore.LOGGER.info("Discord gateway closed ({}): {}", statusCode, reason);

            switch (statusCode) {
                case CLOSE_AUTH_FAILED -> {
                    fatal("Discord rejected the bot token. Check `bot-token` in config/enderlink.json.");
                    return null;
                }
                case CLOSE_DISALLOWED_INTENTS, CLOSE_INVALID_INTENTS -> {
                    fatal("Discord refused our intents. Enable the MESSAGE CONTENT INTENT for this bot at "
                            + "discord.com/developers -> your app -> Bot -> Privileged Gateway Intents.");
                    return null;
                }
                default -> { }
            }

            // 4007/4009 mean the session is gone; anything else may still be resumable.
            if (statusCode == 4007 || statusCode == 4009) {
                sessionId = null;
                resumeUrl = null;
                lastSequence = null;
            }

            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            cancelHeartbeat();
            EnderLinkCore.LOGGER.warn("Discord gateway error: {}",
                    error == null ? "unknown" : error.getMessage());
            scheduleReconnect();
        }
    }

    /** Used only for the startup log line, so the operator can see which half is live. */
    public String describe() {
        return String.format(Locale.ROOT, "inbound=%s outbound=%s",
                config.inboundEnabled() ? "on" : "off",
                config.outboundEnabled() ? "on" : "off");
    }
}
