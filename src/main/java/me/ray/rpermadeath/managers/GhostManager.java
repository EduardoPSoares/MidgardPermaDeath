package me.ray.rpermadeath.managers;

import me.ray.rpermadeath.RPermadeath;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

public class GhostManager {

    private final RPermadeath plugin;
    private final DeathManager deathManager;
    private final LuckPermsManager luckPermsManager;
    private final Set<UUID> ghosts = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public GhostManager(RPermadeath plugin, DeathManager deathManager, LuckPermsManager luckPermsManager) {
        this.plugin = plugin;
        this.deathManager = deathManager;
        this.luckPermsManager = luckPermsManager;
        startActionBarTask();
    }

    public void setGhost(Player player, boolean isGhost) {
        if (isGhost) {
            addGhost(player);
        } else {
            removeGhost(player);
        }
    }

    public void addGhost(Player player) {
        try {
            ghosts.add(player.getUniqueId());

            player.setGameMode(GameMode.ADVENTURE);
            player.setAllowFlight(true);
            player.setFlying(true);
            player.setInvulnerable(true);
            player.setCollidable(false);
            player.setSilent(true);
            player.setCanPickupItems(false);

            if (luckPermsManager != null) {
                luckPermsManager.setPrefix(player, "&7[Ghost] ", 100);
                luckPermsManager.addPermission(player, "mmocore.actionbar", false);
                luckPermsManager.addPermission(player, "mmocore.action-bar", false);
            }

            hideFromAll(player);

            plugin.getLogger().info("[Ghost] " + player.getName() + " entrou no modo fantasma (voice chat sera mutado automaticamente pelo VoiceChatIntegration).");
        } catch (Exception e) {
            plugin.getLogger().severe("[Ghost] Erro ao adicionar fantasma " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void removeGhost(Player player) {
        try {
            ghosts.remove(player.getUniqueId());
            player.setFlying(false);
            player.setAllowFlight(false);
            player.setGameMode(GameMode.SURVIVAL);
            player.setCollidable(true);
            player.setSilent(false);
            player.setCanPickupItems(true);
            player.setInvulnerable(false);

            if (luckPermsManager != null) {
                luckPermsManager.removePrefix(player, "&7[Ghost] ", 100);
                luckPermsManager.removePermission(player, "mmocore.actionbar", false);
                luckPermsManager.removePermission(player, "mmocore.action-bar", false);
            }

            showToAll(player);

            plugin.getLogger().info("[Ghost] " + player.getName() + " saiu do modo fantasma (voice chat restaurado automaticamente).");
        } catch (Exception e) {
            plugin.getLogger().severe("[Ghost] Erro ao remover fantasma " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public boolean isGhost(Player player) {
        return ghosts.contains(player.getUniqueId());
    }

    public boolean isGhost(UUID uuid) {
        return ghosts.contains(uuid);
    }

    public void updateGhost(Player player) {
        if (deathManager.getDeathState(player.getUniqueId()) == DeathState.GHOST) {
            addGhost(player);
        } else if (ghosts.contains(player.getUniqueId())) {
            removeGhost(player);
        }
    }

    public void refreshVisibility(Player viewer) {
        try {
            boolean viewerIsGhost = deathManager.getDeathState(viewer.getUniqueId()) == DeathState.GHOST;
            boolean viewerIsAdmin = viewer.hasPermission("rpermadeath.admin.seeghosts");

            for (UUID ghostId : ghosts) {
                Player ghost = Bukkit.getPlayer(ghostId);
                if (ghost == null) continue;

                if (viewerIsAdmin || viewerIsGhost) {
                    if (!viewer.canSee(ghost)) viewer.showPlayer(plugin, ghost);
                } else {
                    if (viewer.canSee(ghost)) viewer.hidePlayer(plugin, ghost);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao atualizar visibilidade para " + viewer.getName() + ": " + e.getMessage());
        }
    }

    private void hideFromAll(Player ghost) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(ghost.getUniqueId())) continue;

            if (p.hasPermission("rpermadeath.admin.seeghosts")) {
                if (!p.canSee(ghost)) p.showPlayer(plugin, ghost);
                continue;
            }

            if (deathManager.getDeathState(p.getUniqueId()) != DeathState.GHOST) {
                if (p.canSee(ghost)) p.hidePlayer(plugin, ghost);
            } else {
                if (!p.canSee(ghost)) p.showPlayer(plugin, ghost);
            }
        }
    }

    private void showToAll(Player player) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.canSee(player)) p.showPlayer(plugin, player);
        }
    }

    private void startActionBarTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID uuid : ghosts) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    player.sendActionBar(plugin.getMessages().component("ghost.actionbar"));
                }
            }
        }, 0L, 40L);
    }

    public void restoreAll() {
        for (UUID uuid : new java.util.ArrayList<>(ghosts)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                try {
                    removeGhost(player);
                } catch (Exception e) {
                    plugin.getLogger().warning("[Ghost] Erro ao restaurar " + player.getName() + " no shutdown: " + e.getMessage());
                }
            }
        }
        ghosts.clear();
    }
}
