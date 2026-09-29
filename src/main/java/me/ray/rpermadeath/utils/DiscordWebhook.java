package me.ray.rpermadeath.utils;

import me.ray.rpermadeath.RPermadeath;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class DiscordWebhook {

    private final RPermadeath plugin;
    private String webhookUrl;
    private boolean enabled;

    public DiscordWebhook(RPermadeath plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        this.enabled = plugin.getConfig().getBoolean("discord.enabled", false);
        this.webhookUrl = plugin.getConfig().getString("discord.webhook-url", "");
    }

    public void sendDeathMessage(String playerName, String killerName, String deathMessage) {
        if (!enabled || webhookUrl.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String json = buildJson(playerName, killerName, deathMessage);
                send(json);
            } catch (Throwable e) {
                plugin.getLogger().warning("Falha ao enviar webhook para o Discord: " + e.getMessage());
            }
        });
    }

    private String buildJson(String playerName, String killerName, String deathMessage) {
        // Simples JSON builder para evitar dependências externas pesadas
        // Escapar strings é importante
        String safePlayer = escape(playerName);
        String safeKiller = escape(killerName);
        String safeMsg = escape(deathMessage);
        
        String title = plugin.getConfig().getString("discord.title", "☠ Death Registered");
        String color = plugin.getConfig().getString("discord.color", "16711680"); // Vermelho
        
        return "{"
                + "\"embeds\": [{"
                + "\"title\": \"" + escape(title) + "\","
                + "\"description\": \"" + safeMsg + "\","
                + "\"color\": " + color + ","
                + "\"fields\": ["
                + "{\"name\": \"Vítima\", \"value\": \"" + safePlayer + "\", \"inline\": true},"
                + "{\"name\": \"Assassino\", \"value\": \"" + safeKiller + "\", \"inline\": true}"
                + "],"
                + "\"footer\": {\"text\": \"rPermadeath\"},"
                + "\"timestamp\": \"" + java.time.Instant.now().toString() + "\""
                + "}]"
                + "}";
    }
    
    private String escape(String str) {
        if (str == null) return "";
        return str.replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void send(String json) throws IOException {
        URL url = java.net.URI.create(webhookUrl).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = json.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                plugin.getLogger().warning("Discord Webhook retornou código: " + responseCode);
            }
        } finally {
            connection.disconnect();
        }
    }
}
