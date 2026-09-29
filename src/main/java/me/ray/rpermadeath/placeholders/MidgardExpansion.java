package me.ray.rpermadeath.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.managers.DeathInfo;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.time.format.DateTimeFormatter;

public class MidgardExpansion extends PlaceholderExpansion {

    private final RPermadeath plugin;

    public MidgardExpansion(RPermadeath plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "rpermadeath";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Ray";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        try {
            // %rpermadeath_is_dead%
            if (params.equalsIgnoreCase("is_dead")) {
                return plugin.getDeathManager().isDead(player.getUniqueId()) ? "true" : "false";
            }

            // %rpermadeath_bounty%
            if (params.equalsIgnoreCase("bounty")) {
                double bounty = plugin.getBountyManager().getBounty(player.getUniqueId());
                return String.format("%,.0f", bounty);
            }
            
            // %rpermadeath_bounty_raw%
            if (params.equalsIgnoreCase("bounty_raw")) {
                return String.valueOf(plugin.getBountyManager().getBounty(player.getUniqueId()));
            }

            // Placeholders para jogadores mortos
            if (plugin.getDeathManager().isDead(player.getUniqueId())) {
                DeathInfo info = plugin.getDeathManager().getDeathInfo(player.getUniqueId());
                if (info != null) {
                    // %rpermadeath_killer%
                    if (params.equalsIgnoreCase("killer")) {
                        return info.getKillerName();
                    }
                    
                    // %rpermadeath_death_date%
                    if (params.equalsIgnoreCase("death_date")) {
                        return info.getDeathTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
                    }
                }
            }
        } catch (Exception e) {
            return "Error";
        }

        return null;
    }
}
