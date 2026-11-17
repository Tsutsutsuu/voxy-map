package com.voxymap;

import net.minecraft.client.Minecraft;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Sends in-game feedback to the project's DingTalk group robot.
 *
 * <p>The custom robot is configured with the "sign" (加签) security mode, so every request carries
 * {@code timestamp} + {@code sign} (HMAC-SHA256 of {@code timestamp\nSECRET}). The request runs on a
 * daemon thread and the result is delivered back on the render thread. No log, world data or config
 * is sent unless the caller explicitly appends it.</p>
 *
 * <p>The webhook and signing secret are <b>not</b> in the source tree: they are read from the
 * local-only {@code /assets/voxymap/feedback.properties} resource, which is gitignored and packed
 * into release jars by the local build. A build without it simply reports the feature as
 * unavailable; it never blocks the map or the rest of the config screen.</p>
 */
public final class FeedbackSender {
    private static final String WEBHOOK;
    private static final String SECRET;

    static {
        String webhook = "";
        String secret = "";
        try (InputStream in = FeedbackSender.class
                .getResourceAsStream("/assets/voxymap/feedback.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                webhook = p.getProperty("webhook", "").trim();
                secret = p.getProperty("secret", "").trim();
            }
        } catch (Throwable ignored) {
            // A missing/broken resource disables sending; it must never break class loading.
        }
        WEBHOOK = webhook;
        SECRET = secret;
    }

    /** One message per client per 30 s: enough for a real report, too slow for spam. */
    private static final long MIN_INTERVAL_MS = 30_000L;
    private static final int MAX_MESSAGE_CHARS = 3_000;
    private static final int MAX_LOG_CHARS = 7_000;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static long lastSendMs;

    private FeedbackSender() {
    }

    /** {@code rate} means the local 30 s cooldown rejected the send before it left the client. */
    public record Result(boolean ok, String detail) {
    }

    public static void send(String userText, boolean attachLog, boolean attachMods, String versionLine,
                            Consumer<Result> callback) {
        if (WEBHOOK.isEmpty() || SECRET.isEmpty()) {
            callback.accept(new Result(false, "unconfigured"));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSendMs < MIN_INTERVAL_MS) {
            callback.accept(new Result(false, "rate"));
            return;
        }
        lastSendMs = now;

        String payload = buildPayload(userText, attachLog, attachMods, versionLine);
        Thread worker = new Thread(() -> {
            Result result;
            try {
                long timestamp = System.currentTimeMillis();
                String sign = sign(timestamp);
                String url = WEBHOOK + "&timestamp=" + timestamp + "&sign=" + sign;
                String json = "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + jsonEscape(payload) + "\"}}";
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = CLIENT.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                boolean ok = response.statusCode() == 200 && response.body().contains("\"errcode\":0");
                result = new Result(ok, ok ? "sent" : "http " + response.statusCode());
            } catch (Throwable t) {
                result = new Result(false, t.getClass().getSimpleName());
            }
            Result delivered = result;
            Minecraft.getInstance().execute(() -> callback.accept(delivered));
        }, "voxymap-feedback");
        worker.setDaemon(true);
        worker.start();
    }

    /** Builds the exact text that will be posted, also used for the clipboard fallback. */
    public static String buildPayload(String userText, boolean attachLog, boolean attachMods, String versionLine) {
        StringBuilder out = new StringBuilder();
        out.append("[Voxy Map 反馈]\n");
        String text = userText == null ? "" : userText.trim();
        if (text.length() > MAX_MESSAGE_CHARS) text = text.substring(0, MAX_MESSAGE_CHARS) + "\n…";
        out.append(text);
        out.append("\n\n--- 版本 ---\n").append(versionLine);
        if (attachMods) {
            String mods = AboutInfo.modList();
            if (!mods.isEmpty()) {
                out.append("\n\n--- 模组列表 ---\n").append(mods);
            }
        }
        if (attachLog) {
            String tail = readLogTail(MAX_LOG_CHARS);
            if (tail != null && !tail.isEmpty()) {
                out.append("\n\n--- latest.log (末尾) ---\n").append(tail);
            }
        }
        return out.toString();
    }

    /** Last {@code maxChars} characters of {@code logs/latest.log}, or null when unavailable. */
    private static String readLogTail(int maxChars) {
        try {
            Path log = Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve("latest.log");
            if (!Files.isRegularFile(log)) return null;
            byte[] all = Files.readAllBytes(log);
            int byteLimit = Math.min(all.length, maxChars * 3);
            int start = all.length - byteLimit;
            String text = new String(all, start, byteLimit, StandardCharsets.UTF_8);
            return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String sign(long timestamp) throws Exception {
        String stringToSign = timestamp + "\n" + SECRET;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String base64 = Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        return URLEncoder.encode(base64, StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 32);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
