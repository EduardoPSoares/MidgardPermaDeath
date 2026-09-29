package me.ray.rpermadeath.listeners;

import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.managers.DeathManager;
import me.ray.rpermadeath.managers.WorldGuardManager;
import me.ray.rpermadeath.replay.ReplayListener;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;

public class SpectatorListener implements Listener {

    private final RPermadeath plugin;
    private final DeathManager deathManager;
    private final WorldGuardManager worldGuardManager;
    private final ReplayListener replayListener;

    private List<String> valhallaAllowedRegions;
    private List<String> purgatoryAllowedRegions;

    public SpectatorListener(RPermadeath plugin, DeathManager deathManager, WorldGuardManager worldGuardManager, ReplayListener replayListener) {
        this.plugin = plugin;
        this.deathManager = deathManager;
        this.worldGuardManager = worldGuardManager;
        this.replayListener = replayListener;
        reloadConfig();
    }

    public void reloadConfig() {
        this.valhallaAllowedRegions = plugin.getConfig().getStringList("valhalla.allowed-regions");
        this.purgatoryAllowedRegions = plugin.getConfig().getStringList("purgatory.allowed-regions");
    }

    private boolean isGhost(Player player) {
        return plugin.getGhostManager() != null && plugin.getGhostManager().isGhost(player);
    }

    private List<String> getAllowedRegions(Player player) {
        return switch (deathManager.getDeathState(player.getUniqueId())) {
            case PURGATORY -> purgatoryAllowedRegions;
            default -> valhallaAllowedRegions;
        };
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        try {
            if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                    && event.getFrom().getBlockY() == event.getTo().getBlockY()
                    && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
                return;
            }

            Player player = event.getPlayer();
            if (!deathManager.isDead(player.getUniqueId())) {
                return;
            }
            if (isGhost(player)) return;
            if (replayListener.isWatchingReplay(player)) return;

            List<String> allowedRegions = getAllowedRegions(player);
            if (allowedRegions.isEmpty()) {
                return;
            }

            Location to = event.getTo();
            if (to == null) return;

            if (!worldGuardManager.isAllowed(to, allowedRegions)) {
                event.setCancelled(true);
                plugin.getMessages().send(player, "spectator.move-blocked");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no SpectatorListener.onMove: " + e.getMessage());
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        try {
            Player player = event.getPlayer();

            if (!deathManager.isDead(player.getUniqueId())) {
                return;
            }
            if (isGhost(player)) return;
            if (replayListener.isWatchingReplay(player)) return;

            List<String> allowedRegions = getAllowedRegions(player);
            if (allowedRegions.isEmpty()) {
                return;
            }

            Location to = event.getTo();
            if (to == null) return;

            if (!worldGuardManager.isAllowed(to, allowedRegions)) {
                event.setCancelled(true);
                plugin.getMessages().send(player, "spectator.teleport-blocked");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no SpectatorListener.onTeleport: " + e.getMessage());
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (deathManager.isDead(event.getPlayer().getUniqueId())) {
            if (isGhost(event.getPlayer())) return;
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (deathManager.isDead(event.getPlayer().getUniqueId())) {
            if (isGhost(event.getPlayer())) return;
            if (replayListener.isWatchingReplay(event.getPlayer())) return;
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (deathManager.isDead(player.getUniqueId())) {
                if (isGhost(player)) return;
                if (replayListener.isWatchingReplay(player)) return;
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            if (deathManager.isDead(player.getUniqueId())) {
                if (isGhost(player)) return;
                if (replayListener.isWatchingReplay(player)) return;
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (deathManager.isDead(player.getUniqueId())) {
                if (isGhost(player)) return;
                if (replayListener.isWatchingReplay(player)) return;
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (deathManager.isDead(player.getUniqueId())) {
                if (isGhost(player)) return;
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (deathManager.isDead(player.getUniqueId())) {
                if (isGhost(player)) return;
                event.setCancelled(true);
                player.setFoodLevel(20);
            }
        }
    }
}
