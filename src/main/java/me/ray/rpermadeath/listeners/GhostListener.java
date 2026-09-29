package me.ray.rpermadeath.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.managers.GhostManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public class GhostListener implements Listener {

    private final RPermadeath plugin;
    private final GhostManager ghostManager;

    public GhostListener(RPermadeath plugin, GhostManager ghostManager) {
        this.plugin = plugin;
        this.ghostManager = ghostManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        try {
            ghostManager.updateGhost(event.getPlayer());
            ghostManager.refreshVisibility(event.getPlayer());
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onJoin: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        try {
            if (ghostManager.isGhost(event.getPlayer())) {
                event.setCancelled(true);
                plugin.getMessages().send(event.getPlayer(), "ghost.chat-blocked");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onChat: " + e.getMessage());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        try {
            if (ghostManager.isGhost(event.getPlayer())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onInteract: " + e.getMessage());
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        try {
            if (ghostManager.isGhost(event.getPlayer())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onBreak: " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        try {
            if (ghostManager.isGhost(event.getPlayer())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onPlace: " + e.getMessage());
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        try {
            if (event.getEntity() instanceof Player && ghostManager.isGhost((Player) event.getEntity())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onDamage: " + e.getMessage());
        }
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        try {
            if (event.getDamager() instanceof Player && ghostManager.isGhost((Player) event.getDamager())) {
                event.setCancelled(true);
                return;
            }
            
            if (event.getDamager() instanceof org.bukkit.entity.Projectile) {
                org.bukkit.entity.Projectile proj = (org.bukkit.entity.Projectile) event.getDamager();
                if (proj.getShooter() instanceof Player && ghostManager.isGhost((Player) proj.getShooter())) {
                    event.setCancelled(true);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onAttack: " + e.getMessage());
        }
    }

    @EventHandler
    public void onFoodChange(org.bukkit.event.entity.FoodLevelChangeEvent event) {
        try {
            if (event.getEntity() instanceof Player && ghostManager.isGhost((Player) event.getEntity())) {
                event.setCancelled(true);
                event.setFoodLevel(20);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onFoodChange: " + e.getMessage());
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        try {
            if (event.getEntity() instanceof Player && ghostManager.isGhost((Player) event.getEntity())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onPickup: " + e.getMessage());
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        try {
            if (ghostManager.isGhost(event.getPlayer())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onDrop: " + e.getMessage());
        }
    }
    
    @EventHandler
    public void onTarget(EntityTargetEvent event) {
        try {
            if (event.getTarget() instanceof Player && ghostManager.isGhost((Player) event.getTarget())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onTarget: " + e.getMessage());
        }
    }
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        try {
            if (event.getWhoClicked() instanceof Player && ghostManager.isGhost((Player) event.getWhoClicked())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onInventoryClick: " + e.getMessage());
        }
    }
    
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        try {
            if (ghostManager.isGhost(event.getPlayer())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onInteractEntity: " + e.getMessage());
        }
    }

    @EventHandler
    public void onArmorStandManipulate(org.bukkit.event.player.PlayerArmorStandManipulateEvent event) {
        try {
            if (ghostManager.isGhost(event.getPlayer())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onArmorStandManipulate: " + e.getMessage());
        }
    }

    @EventHandler
    public void onInteractAtEntity(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
        try {
            if (ghostManager.isGhost(event.getPlayer())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onInteractAtEntity: " + e.getMessage());
        }
    }

    @EventHandler
    public void onVehicleEnter(org.bukkit.event.vehicle.VehicleEnterEvent event) {
        try {
            if (event.getEntered() instanceof Player && ghostManager.isGhost((Player) event.getEntered())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onVehicleEnter: " + e.getMessage());
        }
    }

    @EventHandler
    public void onVehicleDamage(org.bukkit.event.vehicle.VehicleDamageEvent event) {
        try {
            if (event.getAttacker() instanceof Player && ghostManager.isGhost((Player) event.getAttacker())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onVehicleDamage: " + e.getMessage());
        }
    }
    
    @EventHandler
    public void onVehicleDestroy(org.bukkit.event.vehicle.VehicleDestroyEvent event) {
        try {
            if (event.getAttacker() instanceof Player && ghostManager.isGhost((Player) event.getAttacker())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onVehicleDestroy: " + e.getMessage());
        }
    }

    @EventHandler
    public void onHangingBreak(org.bukkit.event.hanging.HangingBreakByEntityEvent event) {
        try {
            if (event.getRemover() instanceof Player && ghostManager.isGhost((Player) event.getRemover())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no GhostListener.onHangingBreak: " + e.getMessage());
        }
    }
}
