package com.mcqqbridge.qq;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class QQBotClient {

    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final String API_BASE = "https://api.sgroup.qq.com";
    private static final int INTENT_PUBLIC_MESSAGES = 1 << 25;

    private final String appId;
    private final String appSecret;
    private final Logger logger;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    private volatile String accessToken;
    private volatile long tokenExpiresAt;
    private volatile WebSocket webSocket;
    private volatile String sessionId;
    private volatile int lastSeq;
    private volatile boolean running;
    private volatile String cachedGatewayUrl;
    private volatile java.util.concurrent.ScheduledFuture<?> heartbeatTask;
    private int consecutiveErrors;

    private Consumer<QQGroupMessage> onGroupMessage;
    private Consumer<String> onGroupOpenIdDetected;

    public QQBotClient(String appId, String appSecret, Logger logger) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "QQBot-Scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void setOnGroupMessage(Consumer<QQGroupMessage> handler) {
        this.onGroupMessage = handler;
    }

    public void setOnGroupOpenIdDetected(Consumer<String> handler) {
        this.onGroupOpenIdDetected = handler;
    }

    public void start() {
        running = true;
        scheduler.execute(this::connectLoop);
    }

    public void stop() {
        running = false;
        cancelHeartbeat();
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        scheduler.shutdownNow();
    }

    private void connectLoop() {
        while (running) {
            try {
                refreshToken();
                String wsUrl = getGatewayUrl();
                if (wsUrl == null) {
                    throw new RuntimeException("Failed to get gateway URL");
                }
                logger.info("[QQBot] Connecting to WebSocket: " + wsUrl);
                consecutiveErrors = 0;
                connectWebSocket(wsUrl).get();
            } catch (Exception e) {
                consecutiveErrors++;
                logger.warning("[QQBot] Connection error: " + e.getMessage());
            }
            if (running) {
                long delay = Math.min(5000L * (1L << Math.min(consecutiveErrors, 5)), 60000L);
                logger.info("[QQBot] Reconnecting in " + (delay / 1000) + " seconds...");
                try { Thread.sleep(delay); } catch (InterruptedException ignored) { break; }
            }
        }
    }

    private void refreshToken() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("appId", appId);
        body.addProperty("clientSecret", appSecret);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

        if (json.has("access_token")) {
            accessToken = json.get("access_token").getAsString();
            long expiresIn = json.get("expires_in").getAsLong();
            tokenExpiresAt = System.currentTimeMillis() + (expiresIn - 60) * 1000;
            logger.info("[QQBot] Token refreshed, expires in " + expiresIn + "s");
        } else {
            throw new RuntimeException("Failed to get token: " + response.body());
        }
    }

    private void ensureToken() throws Exception {
        if (accessToken == null || System.currentTimeMillis() >= tokenExpiresAt) {
            refreshToken();
        }
    }

    private String getGatewayUrl() throws Exception {
        if (cachedGatewayUrl != null) {
            return cachedGatewayUrl;
        }
        ensureToken();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/gateway/bot"))
                .header("Authorization", "QQBot " + accessToken)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

        if (json.has("url") && !json.get("url").isJsonNull()) {
            cachedGatewayUrl = json.get("url").getAsString();
            return cachedGatewayUrl;
        } else {
            String errMsg = json.has("message") ? json.get("message").getAsString() : response.body();
            logger.warning("[QQBot] Gateway API error: " + errMsg);
            cachedGatewayUrl = null;
            return null;
        }
    }

    private CompletableFuture<Void> connectWebSocket(String url) {
        CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        AtomicReference<WebSocket> wsRef = new AtomicReference<>();

        WebSocket.Builder wsBuilder = httpClient.newWebSocketBuilder();
        wsBuilder.buildAsync(URI.create(url), new WebSocket.Listener() {
            private StringBuilder buffer = new StringBuilder();
            private int heartbeatInterval = 30;

            @Override
            public void onOpen(WebSocket ws) {
                wsRef.set(ws);
                webSocket = ws;
                logger.info("[QQBot] WebSocket connected");
                ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    String msg = buffer.toString();
                    buffer.setLength(0);
                    handleWsMessage(msg);
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                logger.info("[QQBot] WebSocket closed: " + statusCode + " " + reason);
                webSocket = null;
                cancelHeartbeat();
                closeFuture.complete(null);
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                logger.warning("[QQBot] WebSocket error: " + error.getMessage());
                webSocket = null;
                cancelHeartbeat();
                closeFuture.complete(null);
            }

            private void handleWsMessage(String raw) {
                JsonObject msg = JsonParser.parseString(raw).getAsJsonObject();
                int op = msg.get("op").getAsInt();

                switch (op) {
                    case 10: // Hello
                        heartbeatInterval = msg.getAsJsonObject("d").get("heartbeat_interval").getAsInt() / 1000;
                        sendIdentify(wsRef.get());
                        startHeartbeat(wsRef.get(), heartbeatInterval);
                        break;
                    case 11: // Heartbeat ACK
                        break;
                    case 0: // Dispatch
                        int seq = msg.get("s").getAsInt();
                        if (seq > 0) lastSeq = seq;
                        String t = msg.has("t") && !msg.get("t").isJsonNull() ? msg.get("t").getAsString() : "";
                        handleDispatch(t, msg.getAsJsonObject("d"));
                        break;
                    case 7: // Reconnect
                        logger.info("[QQBot] Server requested reconnect");
                        wsRef.get().sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
                        break;
                    case 9: // Invalid Session
                        logger.warning("[QQBot] Invalid session, resetting");
                        sessionId = null;
                        lastSeq = 0;
                        wsRef.get().sendClose(WebSocket.NORMAL_CLOSURE, "invalid session");
                        break;
                }
            }

            private void handleDispatch(String event, JsonObject d) {
                if ("READY".equals(event)) {
                    sessionId = d.get("session_id").getAsString();
                    logger.info("[QQBot] Identified successfully, session: " + sessionId);
                } else if ("GROUP_AT_MESSAGE_CREATE".equals(event) || "GROUP_MESSAGE_CREATE".equals(event)) {
                    String groupOpenId = d.has("group_openid") ? d.get("group_openid").getAsString() : "";
                    String content = d.has("content") ? d.get("content").getAsString() : "";
                    String nickname = "Unknown";
                    if (d.has("author")) {
                        JsonObject author = d.getAsJsonObject("author");
                        if (author.has("member_nick")) {
                            nickname = author.get("member_nick").getAsString();
                        } else if (author.has("nick")) {
                            nickname = author.get("nick").getAsString();
                        } else if (author.has("username")) {
                            nickname = author.get("username").getAsString();
                        } else if (author.has("name")) {
                            nickname = author.get("name").getAsString();
                        }
                    }
                    String msgId = d.has("id") ? d.get("id").getAsString() : "";

                    if (!groupOpenId.isEmpty() && onGroupOpenIdDetected != null) {
                        onGroupOpenIdDetected.accept(groupOpenId);
                    }

                    // Skip messages with no meaningful text (stickers, images, etc.)
                    if (content.isBlank()) {
                        content = "[图片/表情消息]";
                    }

                    if (onGroupMessage != null) {
                        logger.info("[QQBot] Group message from " + nickname + " (" + event + "): " + content);
                        onGroupMessage.accept(new QQGroupMessage(groupOpenId, nickname, content, msgId));
                    }
                }
            }
        }).thenAccept(ws -> wsRef.set(ws))
          .exceptionally(ex -> {
              logger.warning("[QQBot] WebSocket build failed: " + ex.getMessage());
              closeFuture.complete(null);
              return null;
          });

        return closeFuture;
    }

    private void cancelHeartbeat() {
        java.util.concurrent.ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
    }

    private void sendIdentify(WebSocket ws) {
        try {
            ensureToken();
        } catch (Exception e) {
            logger.warning("[QQBot] Failed to ensure token for identify: " + e.getMessage());
            return;
        }
        JsonObject d = new JsonObject();
        d.addProperty("token", "QQBot " + accessToken);
        d.addProperty("intents", INTENT_PUBLIC_MESSAGES);
        com.google.gson.JsonArray shard = new com.google.gson.JsonArray();
        shard.add(0);
        shard.add(1);
        d.add("shard", shard);

        JsonObject payload = new JsonObject();
        payload.addProperty("op", 2);
        payload.add("d", d);

        ws.sendText(payload.toString(), true);
        logger.info("[QQBot] Sent identify");
    }

    private void startHeartbeat(WebSocket ws, int intervalSec) {
        cancelHeartbeat();
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (ws != null && !ws.isOutputClosed()) {
                JsonObject payload = new JsonObject();
                payload.addProperty("op", 1);
                payload.addProperty("d", lastSeq);
                ws.sendText(payload.toString(), true);
            }
        }, intervalSec, intervalSec, TimeUnit.SECONDS);
    }

    public void sendGroupMessage(String groupOpenId, String content) {
        if (groupOpenId == null || groupOpenId.isEmpty()) {
            logger.warning("[QQBot] Cannot send message: group_openid not set");
            return;
        }
        scheduler.execute(() -> {
            try {
                ensureToken();
                JsonObject body = new JsonObject();
                body.addProperty("content", content);
                body.addProperty("msg_type", 0);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(API_BASE + "/v2/groups/" + groupOpenId + "/messages"))
                        .header("Authorization", "QQBot " + accessToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    logger.warning("[QQBot] Send message failed: " + response.statusCode() + " " + response.body());
                }
            } catch (Exception e) {
                logger.warning("[QQBot] Send message error: " + e.getMessage());
            }
        });
    }

    public void sendGroupImage(String groupOpenId, byte[] png) {
        if (groupOpenId == null || groupOpenId.isEmpty()) {
            logger.warning("[QQBot] Cannot send image: group_openid not set");
            return;
        }
        if (png == null || png.length == 0) {
            logger.warning("[QQBot] Cannot send image: empty image data");
            return;
        }
        scheduler.execute(() -> {
            try {
                ensureToken();
                String fileInfo = uploadGroupFile(groupOpenId, png);
                if (fileInfo == null) {
                    return;
                }
                JsonObject media = new JsonObject();
                media.addProperty("file_info", fileInfo);
                JsonObject body = new JsonObject();
                body.addProperty("msg_type", 7);
                body.add("media", media);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(API_BASE + "/v2/groups/" + groupOpenId + "/messages"))
                        .header("Authorization", "QQBot " + accessToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .timeout(Duration.ofSeconds(10))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    logger.warning("[QQBot] Send image failed: " + response.statusCode() + " " + response.body());
                }
            } catch (Exception e) {
                logger.warning("[QQBot] Send image error: " + e.getMessage());
            }
        });
    }

    private String uploadGroupFile(String groupOpenId, byte[] png) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("file_type", 1);
        body.addProperty("file_data", java.util.Base64.getEncoder().encodeToString(png));
        body.addProperty("srv_send_msg", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/v2/groups/" + groupOpenId + "/files"))
                .header("Authorization", "QQBot " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            logger.warning("[QQBot] Upload image failed: " + response.statusCode() + " " + response.body());
            return null;
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("file_info")) {
            logger.warning("[QQBot] Upload image response missing file_info: " + response.body());
            return null;
        }
        return json.get("file_info").getAsString();
    }
}
