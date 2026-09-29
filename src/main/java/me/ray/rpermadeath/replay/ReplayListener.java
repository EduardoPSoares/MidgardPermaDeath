package me.ray.rpermadeath.replay;

import me.ray.rpermadeath.RPermadeath;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.Action;

import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;

/**
 * Listener para eventos relacionados ao replay
 */
public class ReplayListener implements Listener {
    private final RPermadeath plugin;
    private final Map<UUID, ReplayPlayer> activePlayers;
    
    public ReplayListener(RPermadeath plugin) {
        this.plugin = plugin;
        this.activePlayers = new ConcurrentHashMap<>();
    }

    public void stopAllReplays() {
        // Create a copy to avoid ConcurrentModificationException
        for (ReplayPlayer replayPlayer : new java.util.ArrayList<>(activePlayers.values())) {
            replayPlayer.stop();
        }
        activePlayers.clear();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            // Segurança: Se o servidor crashou enquanto o player assistia replay,
            // ele pode entrar em modo criativo ou em local estranho.
            // Como não persistimos o estado de "assistindo replay", assumimos que
            // ao entrar, ninguém está assistindo.
            
            Player player = event.getPlayer();
            
            // Se o jogador estiver em criativo e não tiver permissão, força survival
            if (player.getGameMode() == org.bukkit.GameMode.CREATIVE && !player.hasPermission("rpermadeath.admin")) {
                player.setGameMode(org.bukkit.GameMode.SURVIVAL);
                plugin.getLogger().warning("Jogador " + player.getName() + " entrou em Criativo sem permissão (possível crash durante replay). Forçando Survival.");
            }
            
            // Tenta restaurar inventário de backup se existir (Crash Recovery)
            plugin.getReplayManager().restorePlayerInventory(player);
            
            // Se estiver voando sem permissão
            if (player.isFlying() && !player.hasPermission("rpermadeath.admin")) {
                player.setFlying(false);
                player.setAllowFlight(false);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao processar entrada de jogador (ReplayListener): " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            UUID uuid = event.getPlayer().getUniqueId();
            // Remove do cache de inventário
            plugin.getReplayManager().clearCache(uuid);
            
            // Finaliza gravação se houver
            plugin.getReplayManager().stopRecording(uuid);
            
            // Se estiver assistindo replay, para
            ReplayPlayer replayPlayer = activePlayers.remove(uuid);
            if (replayPlayer != null) {
                replayPlayer.stop();
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao processar saída de jogador (ReplayListener): " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        try {
            Player player = event.getPlayer();
            ReplayPlayer replayPlayer = activePlayers.get(player.getUniqueId());
            
            if (replayPlayer != null && replayPlayer.isPlaying()) {
                // Se o mundo novo não for um mundo de replay (começa com replay_), para
                if (!player.getWorld().getName().startsWith("replay_")) {
                    try {
                        replayPlayer.stop();
                    } finally {
                        activePlayers.remove(player.getUniqueId());
                    }
                    plugin.getMessages().send(player, "replay.world-change-stop");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao processar mudança de mundo (ReplayListener): " + e.getMessage());
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        try {
            // Fix para evitar execução dupla (Main Hand + Off Hand)
            if (event.getHand() != EquipmentSlot.HAND) return;

            Player player = event.getPlayer();
            ReplayPlayer replayPlayer = activePlayers.get(player.getUniqueId());
            
            if (replayPlayer != null && replayPlayer.isPlaying()) {
                event.setCancelled(true);
                
                if (event.getAction() == Action.PHYSICAL) return;
                
                // Verifica se está em um mundo de replay pelo nome
                if (!player.getWorld().getName().startsWith("replay_")) {
                    // Se não estiver no mundo certo, força parada
                    replayPlayer.stop();
                    return;
                }
                
                int slot = player.getInventory().getHeldItemSlot();

                // Se for um slot de controle (0, 2, 3, 4, 5, 6, 8), usa o controle
                if (slot >= 0 && slot <= 8) {
                    replayPlayer.handleHotbarClick(slot);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao processar interação (ReplayListener): " + e.getMessage());
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        try {
            Player player = event.getPlayer();
            ReplayPlayer replayPlayer = activePlayers.get(player.getUniqueId());
            
            if (replayPlayer != null && replayPlayer.isPlaying()) {
                event.setCancelled(true);
                
                if (event.getHand() != EquipmentSlot.HAND) return;

                // Clicar em NPC sempre abre o menu de inspeção + áudio
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao processar interação com entidade (ReplayListener): " + e.getMessage());
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            
            // Se estiver assistindo replay, cancela dano
            ReplayPlayer replayPlayer = activePlayers.get(player.getUniqueId());
            if (replayPlayer != null && replayPlayer.isPlaying()) {
                event.setCancelled(true);
                return;
            }

            plugin.getReplayManager().markDamaged(player.getUniqueId());
            
            String damagerName = "Desconhecido";
            // Se foi um ataque de player, marca o atacante como swinging
            if (event instanceof EntityDamageByEntityEvent) {
                EntityDamageByEntityEvent damageEvent = (EntityDamageByEntityEvent) event;
                if (damageEvent.getDamager() instanceof Player) {
                    damagerName = damageEvent.getDamager().getName();
                    plugin.getReplayManager().markSwinging(damageEvent.getDamager().getUniqueId());
                } else if (damageEvent.getDamager() instanceof org.bukkit.entity.Projectile) {
                    org.bukkit.projectiles.ProjectileSource shooter = ((org.bukkit.entity.Projectile) damageEvent.getDamager()).getShooter();
                    if (shooter instanceof Player) {
                        damagerName = ((Player) shooter).getName() + " (Projétil)";
                        plugin.getReplayManager().markSwinging(((Player) shooter).getUniqueId());
                    } else if (shooter instanceof org.bukkit.entity.Entity) {
                         damagerName = ((org.bukkit.entity.Entity) shooter).getName() + " (Projétil)";
                    } else {
                        damagerName = damageEvent.getDamager().getName();
                    }
                } else {
                    damagerName = damageEvent.getDamager().getName();
                }
            } else {
                damagerName = event.getCause().name();
            }
            
            plugin.getReplayManager().recordDamage(player, damagerName, event.getFinalDamage());
        }
    }
    
    @EventHandler
    public void onFoodLevelChange(org.bukkit.event.entity.FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            ReplayPlayer replayPlayer = activePlayers.get(player.getUniqueId());
            if (replayPlayer != null && replayPlayer.isPlaying()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        // Captura qualquer animação de braço (ataque)
        plugin.getReplayManager().markSwinging(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (isWatchingReplay(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (isWatchingReplay(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDropItem(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (isWatchingReplay(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player && isWatchingReplay((Player) event.getWhoClicked())) {
            Player player = (Player) event.getWhoClicked();
            
            // Permite cliques na hotbar (slots 0-8) para os controles funcionarem
            if (event.getClickedInventory() != null && 
                event.getClickedInventory().equals(player.getInventory()) && 
                event.getSlot() >= 0 && event.getSlot() <= 8) {
                
                // Cancela apenas para não mover o item
                event.setCancelled(true);
                
                // Executa a ação do controle
                ReplayPlayer replayPlayer = activePlayers.get(player.getUniqueId());
                if (replayPlayer != null) {
                    // Verifica se está em um mundo de replay pelo nome
                    if (!player.getWorld().getName().startsWith("replay_")) {
                        return;
                    }
                    replayPlayer.handleHotbarClick(event.getSlot());
                }
                return;
            }
            
            // Cancela todos os outros cliques no inventário
            event.setCancelled(true);
            
            // Verifica se é o menu de controle de áudio
            if (event.getView().title() != null) {
                String title = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                        .serialize(event.getView().title());
                String audioSelectionTitle = plugin.getMessages().legacy("replay.audio.selection-title");
                String audioPlayerTitlePrefix = plugin.getMessages().legacy("replay.audio.player-title-prefix");
                if (title.equals(audioSelectionTitle)
                        || title.startsWith(audioPlayerTitlePrefix)) {
                    ReplayPlayer replayPlayer = activePlayers.get(player.getUniqueId());
                    if (replayPlayer != null) {
                        replayPlayer.handleAudioMenuClick(event.getSlot(), event.getCurrentItem());
                    }
                }
            }
        }
    }

    @EventHandler
    public void onEntityPickupItem(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player && isWatchingReplay((Player) event.getEntity())) {
            event.setCancelled(true);
        }
    }
    

    
    
    /**
     * Registra um replay ativo
     */
    public void registerReplayPlayer(ReplayPlayer replayPlayer) {
        activePlayers.put(replayPlayer.getViewer().getUniqueId(), replayPlayer);
    }
    
    /**
     * Remove registro de replay
     */
    public void unregisterReplayPlayer(Player player) {
        activePlayers.remove(player.getUniqueId());
    }
    
    /**
     * Obtém replay ativo de um jogador
     */
    public ReplayPlayer getReplayPlayer(Player player) {
        return activePlayers.get(player.getUniqueId());
    }
    
    /**
     * Verifica se jogador está assistindo replay
     */
    public boolean isWatchingReplay(Player player) {
        ReplayPlayer replayPlayer = activePlayers.get(player.getUniqueId());
        return replayPlayer != null && replayPlayer.isPlaying();
    }

    public void startReplay(Player viewer, ReplayRecording recording) {
        if (isWatchingReplay(viewer)) {
            plugin.getMessages().send(viewer, "replay.already-watching");
            return;
        }
        
        ReplayPlayer replayPlayer = new ReplayPlayer(plugin, viewer, recording);
        registerReplayPlayer(replayPlayer);
        
        // Resource pack já foi enviado no MenuListener antes de chegar aqui
        
        replayPlayer.start();
        plugin.getMessages().send(viewer, "replay.starting");
    }

    public boolean isBeingReplayed(UUID targetId) {
        for (ReplayPlayer rp : activePlayers.values()) {
            if (rp.getRecording().getDeathPlayerId().equals(targetId)) {
                return true;
            }
        }
        return false;
    }
}
