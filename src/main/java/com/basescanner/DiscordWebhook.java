package com.basescanner;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Sends messages to a Discord webhook via HTTP POST.
 * Runs on a background thread so it never freezes the game.
 */
public class DiscordWebhook {

    private static final String WEBHOOK_URL =
        "https://discord.com/api/webhooks/1510734722014249052/V1NLz1GfLBNBTfdWFE-Ibo6b-6wgUB69QAymXbbmwCI97jCJTc4zpoAJBOpHK55I3MB3";

    /**
     * Sends a base found alert to Discord.
     * @param factionName  faction name from hover text
     * @param nearX        player X when the claim was spotted
     * @param nearZ        player Z when the claim was spotted
     * @param dimension    "OVERWORLD" or "END"
     */
    public static void sendBaseFound(String factionName, int nearX, int nearZ, String dimension) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String msg = "🔵 **BASE CLAIM FOUND**\\n"
                   + "Faction: `" + factionName + "`\\n"
                   + "Near Coords: `X=" + nearX + " Z=" + nearZ + "`\\n"
                   + "Dimension: `" + dimension + "`\\n"
                   + "Time: `" + time + "`\\n"
                   + "*(within 6 chunks of actual base)*";
        sendRaw(msg);
    }

    public static void sendStatus(String message) {
        sendRaw("📡 **Scanner** — " + message);
    }

    // ---------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------

    private static void sendRaw(final String content) {
        new Thread(() -> {
            try {
                URL url = new URL(WEBHOOK_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "BaseScanner/1.0");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                // Sanitize for JSON string
                String safe = content
                    .replace("\\n", "\n") // keep intentional newlines
                    .replace("\"", "'");  // avoid breaking JSON

                String json = "{\"content\":\"" + safe + "\"}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code != 200 && code != 204) {
                    System.err.println("[BaseScanner] Webhook returned HTTP " + code);
                }
                conn.disconnect();

            } catch (Exception e) {
                System.err.println("[BaseScanner] Webhook error: " + e.getMessage());
            }
        }, "BaseScanner-Discord").start();
    }
}
