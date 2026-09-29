package me.ray.rpermadeath.listeners;

import me.ray.rpermadeath.RPermadeath;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class MaintenanceFixListener implements Listener {

    private final RPermadeath plugin;

    public MaintenanceFixListener(RPermadeath plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Ignora se for Ghost (eles precisam ser imortais)
        if (plugin.getGhostManager().isGhost(player)) return;

        // Remove invulnerability if player is not in Creative or Spectator mode
        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            if (player.isInvulnerable()) {
                player.setInvulnerable(false);
                plugin.getLogger().info("Imortalidade removida de " + player.getName() + " (Fix Manutencao)");
            }
        }
    }
}
