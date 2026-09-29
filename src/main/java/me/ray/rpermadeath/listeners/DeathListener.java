package me.ray.rpermadeath.listeners;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.managers.BountyManager;
import me.ray.rpermadeath.managers.DeathManager;
import me.ray.rpermadeath.managers.DeathState;
import me.ray.rpermadeath.managers.LuckPermsManager;
import me.ray.rpermadeath.managers.MenuManager;
import me.ray.rpermadeath.replay.ReplayManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DeathListener implements Listener {

    private final RPermadeath plugin;
    private final DeathManager deathManager;
    private final LuckPermsManager luckPermsManager;
    private final ReplayManager replayManager;
    private final BountyManager bountyManager;
    @SuppressWarnings("unused")
    private final MenuManager menuManager;

    private final Map<UUID, UUID> lastDamagerMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDamageTime = new ConcurrentHashMap<>();
    private static final long DAMAGE_TIMEOUT = 10_000L;

    private boolean lightningOnDeath;
    private boolean globalSound;
    private String msgStored;
    private String msgGlobal;
    private String msgGlobalPurgatory;

    public DeathListener(
            RPermadeath plugin,
            DeathManager deathManager,
            LuckPermsManager luckPermsManager,
            ReplayManager replayManager,
            BountyManager bountyManager,
            MenuManager menuManager
    ) {
        this.plugin = plugin;
        this.deathManager = deathManager;
        this.luckPermsManager = luckPermsManager;
        this.replayManager = replayManager;
        this.bountyManager = bountyManager;
        this.menuManager = menuManager;
        reloadConfig();

        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            lastDamageTime.entrySet().removeIf(entry -> now - entry.getValue() > 30_000L);
            lastDamagerMap.keySet().retainAll(lastDamageTime.keySet());
        }, 600L, 600L);
    }

    public void reloadConfig() {
        this.lightningOnDeath = plugin.getConfig().getBoolean("effects.lightning-on-death", true);
        this.globalSound = plugin.getConfig().getBoolean("effects.global-sound", true);
        this.msgStored = plugin.getMessages().legacy("death.recorded");
        this.msgGlobal = plugin.getMessages().legacy("death.announcement");
        this.msgGlobalPurgatory = plugin.getMessages().legacy("purgatory.announcement");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        try {
            if (!(event.getEntity() instanceof Player victim)) return;
            if (!(event.getDamager() instanceof Player damager)) return;
            if (victim.getUniqueId().equals(damager.getUniqueId())) return;

            lastDamagerMap.put(victim.getUniqueId(), damager.getUniqueId());
            lastDamageTime.put(victim.getUniqueId(), System.currentTimeMillis());
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao processar dano de jogador: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnvironmentalDamage(EntityDamageEvent event) {
        try {
            if (!(event.getEntity() instanceof Player victim)) return;

            if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                    || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                    || event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE) {
                return;
            }

            UUID victimId = victim.getUniqueId();
            if (lastDamagerMap.containsKey(victimId) && lastDamageTime.containsKey(victimId)) {
                long timeSinceLastDamage = System.currentTimeMillis() - lastDamageTime.get(victimId);
                if (timeSinceLastDamage <= DAMAGE_TIMEOUT) {
                    lastDamageTime.put(victimId, System.currentTimeMillis());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao processar dano ambiental: " + e.getMessage());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        try {
            Player victim = event.getEntity();

            if (!deathManager.shouldProcessDeath(victim.getLocation())) {
                return;
            }
            if (victim.hasPermission("rpermadeath.bypass")) {
                return;
            }

            Player killer = victim.getKiller();
            if (killer == null) {
                UUID lastDamagerId = lastDamagerMap.get(victim.getUniqueId());
                Long lastDamageTimestamp = lastDamageTime.get(victim.getUniqueId());

                if (lastDamagerId != null && lastDamageTimestamp != null) {
                    long timeSinceLastDamage = System.currentTimeMillis() - lastDamageTimestamp;
                    if (timeSinceLastDamage <= DAMAGE_TIMEOUT) {
                        killer = Bukkit.getPlayer(lastDamagerId);
                    }
                }
            }

            lastDamagerMap.remove(victim.getUniqueId());
            lastDamageTime.remove(victim.getUniqueId());

            if (killer == null) {
                return;
            }

            boolean shouldSendToPurgatory = plugin.getConfig().getBoolean("purgatory.enabled", true)
                    && luckPermsManager.hasConfiguredCriminalGroup(victim);
            DeathState deathState = shouldSendToPurgatory ? DeathState.PURGATORY : null;

            if (deathState == DeathState.PURGATORY) {
                deathManager.markDead(victim, killer, victim.getLocation(), DeathState.PURGATORY);
            } else {
                deathManager.markDead(victim, killer, victim.getLocation());
            }

            bountyManager.claimBounty(killer, victim);
            bountyManager.addBounty(killer);
            replayManager.startDeathRecording(victim, killer);

            if (lightningOnDeath) {
                victim.getWorld().strikeLightningEffect(victim.getLocation());
            }

            if (globalSound) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    online.playSound(online.getLocation(), org.bukkit.Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.5f);
                }
            }

            if (!msgStored.isEmpty()) {
                victim.sendMessage(msgStored);
            }

            String broadcastTemplate = deathState == DeathState.PURGATORY && !msgGlobalPurgatory.isEmpty()
                    ? msgGlobalPurgatory
                    : msgGlobal;

            if (!broadcastTemplate.isEmpty()) {
                String finalMsg = broadcastTemplate.replace("{player}", victim.getName());

                victim.sendMessage(finalMsg);
                killer.sendMessage(finalMsg);
                Bukkit.getConsoleSender().sendMessage(finalMsg);

                String plain = finalMsg.replaceAll("§.", "");
                if (plugin.getDiscordWebhook() != null) {
                    plugin.getDiscordWebhook().sendDeathMessage(victim.getName(), killer.getName(), plain);
                }

                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("DEATH");
                out.writeUTF(victim.getName());
                out.writeUTF(killer.getName());
                out.writeUTF(plain);
                victim.sendPluginMessage(plugin, "rpermadeath:death", out.toByteArray());
            }

            luckPermsManager.grantTemporaryGroup(killer);
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao processar morte de jogador: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        try {
            Player player = event.getPlayer();
            if (deathManager.isDead(player.getUniqueId())) {
                DeathState state = deathManager.getDeathState(player.getUniqueId());

                if (state == DeathState.GHOST) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        plugin.getGhostManager().setGhost(player, true);
                        plugin.getMessages().send(player, "ghost.enter");
                    }, 2L);

                    if (deathManager.getRessSpawn() != null) {
                        event.setRespawnLocation(deathManager.getRessSpawn());
                    } else {
                        Location bedSpawn = player.getRespawnLocation();
                        if (bedSpawn != null) {
                            event.setRespawnLocation(bedSpawn);
                        }
                    }
                    return;
                }

                if (state == DeathState.SPECTATOR) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> player.setGameMode(org.bukkit.GameMode.SPECTATOR), 1L);
                    return;
                }

                handleAfterlifeRespawn(event, player, state);
                return;
            }

            Location bedSpawn = player.getRespawnLocation();
            if (bedSpawn != null) {
                event.setRespawnLocation(bedSpawn);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao processar respawn de jogador: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastDamagerMap.remove(uuid);
        lastDamageTime.remove(uuid);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!deathManager.isDead(player.getUniqueId())) return;

        DeathState state = deathManager.getDeathState(player.getUniqueId());
        if (state == DeathState.GHOST) return;

        if (state == DeathState.SPECTATOR) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.setGameMode(org.bukkit.GameMode.SPECTATOR);
                }
            }, 2L);
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> restoreAfterlifeState(player, state), 2L);
    }

    private void handleAfterlifeRespawn(PlayerRespawnEvent event, Player player, DeathState state) {
        if (shouldForceSpectator(state)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> player.setGameMode(org.bukkit.GameMode.SPECTATOR), 1L);
        }

        Location targetLoc = resolveAfterlifeLocation(state);
        if (targetLoc != null) {
            event.setRespawnLocation(targetLoc);

            String msg = plugin.getMessages().legacy(getTeleportMessagePath(state));
            if (!msg.isEmpty()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> player.sendMessage(msg), 1L);
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                player.getInventory().clear();
                player.setHealth(player.getMaxHealth());
                player.setFoodLevel(20);
                player.setSaturation(20f);
                for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
                    player.removePotionEffect(effect.getType());
                }
            }, 5L);
            return;
        }

        String msg = plugin.getMessages().legacy(getInvalidLocationMessagePath(state));
        if (!msg.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> player.sendMessage(msg), 1L);
        }
    }

    private void restoreAfterlifeState(Player player, DeathState state) {
        if (!player.isOnline()) return;

        if (shouldForceSpectator(state)) {
            player.setGameMode(org.bukkit.GameMode.SPECTATOR);
        }

        Location targetLoc = resolveAfterlifeLocation(state);
        if (targetLoc != null) {
            player.teleport(targetLoc);
        }
    }

    private boolean shouldForceSpectator(DeathState state) {
        if (state == DeathState.PURGATORY) {
            return plugin.getConfig().getBoolean("purgatory.force-spectator", false);
        }
        return plugin.getConfig().getBoolean("valhalla.force-spectator", false);
    }

    private Location resolveAfterlifeLocation(DeathState state) {
        if (state == DeathState.PURGATORY) {
            Location purgatorySpawn = deathManager.getPurgatorySpawn();
            if (purgatorySpawn != null && purgatorySpawn.getWorld() != null) {
                return purgatorySpawn;
            }

            String purgatoryWorldName = plugin.getConfig().getString("purgatory.world", "purgatorio");
            World purgatoryWorld = Bukkit.getWorld(purgatoryWorldName);
            return purgatoryWorld != null ? purgatoryWorld.getSpawnLocation() : null;
        }

        Location deathSpawn = deathManager.getDeathSpawn();
        if (deathSpawn != null && deathSpawn.getWorld() != null) {
            return deathSpawn;
        }

        String valhallaWorldName = plugin.getConfig().getString("valhalla.world", "world_the_end");
        World valhallaWorld = Bukkit.getWorld(valhallaWorldName);
        return valhallaWorld != null ? valhallaWorld.getSpawnLocation() : null;
    }

    private String getTeleportMessagePath(DeathState state) {
        return state == DeathState.PURGATORY ? "purgatory.teleport" : "death.teleport";
    }

    private String getInvalidLocationMessagePath(DeathState state) {
        return state == DeathState.PURGATORY ? "purgatory.location-invalid" : "death.location-invalid";
    }
}
