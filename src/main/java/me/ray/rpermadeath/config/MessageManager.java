package me.ray.rpermadeath.config;

import me.ray.rpermadeath.RPermadeath;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MessageManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final RPermadeath plugin;
    private File file;
    private YamlConfiguration messages;

    public MessageManager(RPermadeath plugin) {
        this.plugin = plugin;
    }

    public void load() {
        if (file == null) {
            file = new File(plugin.getDataFolder(), "messages.yml");
        }
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        reload();
    }

    public void reload() {
        if (file == null) {
            load();
            return;
        }

        messages = YamlConfiguration.loadConfiguration(file);

        try (InputStream stream = plugin.getResource("messages.yml")) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8)
                );
                messages.setDefaults(defaults);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Erro ao carregar defaults de messages.yml: " + e.getMessage());
        }

        messages.options().copyDefaults(true);
        save();
    }

    public void save() {
        if (messages == null || file == null) {
            return;
        }

        try {
            messages.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Erro ao salvar messages.yml: " + e.getMessage());
        }
    }

    public String raw(String path) {
        return messages != null ? messages.getString(path, "") : "";
    }

    public String legacy(String path, Object... replacements) {
        return colorize(applyPlaceholders(raw(path), replacements));
    }

    public Component component(String path, Object... replacements) {
        return LEGACY.deserialize(applyPlaceholders(raw(path), replacements));
    }

    public List<String> legacyList(String path, Object... replacements) {
        List<String> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }

        for (String line : messages.getStringList(path)) {
            result.add(colorize(applyPlaceholders(line, replacements)));
        }
        return result;
    }

    public List<Component> componentList(String path, Object... replacements) {
        List<Component> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }

        for (String line : messages.getStringList(path)) {
            result.add(LEGACY.deserialize(applyPlaceholders(line, replacements)));
        }
        return result;
    }

    public void send(CommandSender sender, String path, Object... replacements) {
        String message = legacy(path, replacements);
        if (!message.isBlank()) {
            sender.sendMessage(message);
        }
    }

    public String plain(String path, Object... replacements) {
        return stripLegacy(legacy(path, replacements));
    }

    public String applyPlaceholders(String template, Object... replacements) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        String result = template;
        for (Map.Entry<String, String> entry : toReplacementMap(replacements).entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private Map<String, String> toReplacementMap(Object... replacements) {
        Map<String, String> map = new LinkedHashMap<>();
        if (replacements == null) {
            return map;
        }

        for (int i = 0; i + 1 < replacements.length; i += 2) {
            String key = String.valueOf(replacements[i]);
            String value = replacements[i + 1] == null ? "" : String.valueOf(replacements[i + 1]);
            map.put(key, value);
        }
        return map;
    }

    private String colorize(String message) {
        return message.replace("&", "\u00A7");
    }

    private String stripLegacy(String message) {
        return message.replaceAll("\u00A7.", "");
    }
}
