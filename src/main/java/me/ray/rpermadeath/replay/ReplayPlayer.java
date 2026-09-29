package me.ray.rpermadeath.replay;

import com.mojang.authlib.GameProfile;
import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.replay.audio.ReplayVoicechatPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Gerencia a reprodução de um replay, criando e controlando NPCs
 */
public class ReplayPlayer {
    private final RPermadeath plugin;
    private final Player viewer;
    private final ReplayRecording recording;
    private final Map<UUID, ServerPlayer> npcMap;
    private final Map<UUID, net.minecraft.world.entity.decoration.ArmorStand> skullMap;
    private final Map<UUID, org.bukkit.entity.Interaction> interactionMap;
    private final Location spectateLocation;
    private Location originalLocation;
    private ItemStack[] originalInventory;
    private ItemStack[] originalArmor;
    private double originalHealth;
    private int originalFood;
    private float originalSaturation;
    private float originalExp;
    private int originalLevel;
    private GameMode originalGameMode;
    private Collection<PotionEffect> originalPotionEffects;
    private BossBar bossBar;
    
    private int currentFrameIndex;
    private BukkitTask playbackTask;
    private BukkitTask deathMarkerTask;
    private boolean isPaused;
    private float playbackSpeed;
    private boolean isSessionActive;
    private int calculatedRecordInterval; // Intervalo calculado baseado nos dados do replay

    // Voice Chat Audio
    private final Map<UUID, Object> audioChannels; // UUID jogador -> AudioChannel (fallback estático)
    private boolean audioEnabled;

    // Envio direto de pacotes posicionais via reflection (contorna o broadcast chain do LocationalAudioChannel)
    private final Map<UUID, UUID> speakerChannelIds = new HashMap<>();
    private final Map<UUID, Long> speakerSequenceNumbers = new HashMap<>();
    private java.lang.reflect.Constructor<?> cachedLocationSoundPacketCtor;
    private java.lang.reflect.Constructor<?> cachedLocationalSoundPacketImplCtor;
    private boolean audioReflectionInitialized = false;
    private boolean audioReflectionAvailable = false;

    // Indicadores visuais de voz (TextDisplay NMS clientside acima do NPC quando falando)
    private final Map<UUID, net.minecraft.world.entity.Display.TextDisplay> voiceIndicatorMap;

    // Controle de áudio por jogador no replay
    private final Set<UUID> mutedPlayers;
    private final Map<UUID, Float> playerVolumes; // 0.0 a 1.0 por jogador
    private float globalVolume; // 0.0 a 1.0
    private static final float[] VOLUME_LEVELS = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};

    // Delta-check para pacotes NPC: evita enviar pacotes quando posição/rotação não mudou
    private final Map<UUID, double[]> lastSentPos = new HashMap<>(); // [x, y, z]
    private final Map<UUID, float[]> lastSentRot = new HashMap<>(); // [yaw, pitch]
    // Encoder/Decoder para manipulação de volume
    private de.maxhenkel.voicechat.api.opus.OpusDecoder opusDecoder;
    private de.maxhenkel.voicechat.api.opus.OpusEncoder opusEncoder;
    
    // Controles da hotbar (simplificados)
    private static final int SLOT_TELEPORT = 0;
    private static final int SLOT_AUDIO = 1;
    private static final int SLOT_REWIND = 2;
    private static final int SLOT_RESTART = 3;
    private static final int SLOT_PLAY_PAUSE = 4;
    private static final int SLOT_SPEED = 5;
    private static final int SLOT_FORWARD = 6;
    private static final int SLOT_EXIT = 8;

    // Título fixo para menus de controle de áudio/inspeção do replay
    public boolean isSessionActive() {
        return isSessionActive;
    }
    
    private ReplayWorldManager.ReplayWorldInfo replayWorldInfo;

    public ReplayPlayer(RPermadeath plugin, Player viewer, ReplayRecording recording) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.recording = recording;
        this.npcMap = new HashMap<>();
        this.skullMap = new HashMap<>();
        this.interactionMap = new HashMap<>();
        this.currentFrameIndex = 0;
        this.isPaused = false;
        this.playbackSpeed = 1.0f;
        this.isSessionActive = false;
        this.audioChannels = new HashMap<>();
        this.audioEnabled = plugin.getConfig().getBoolean("replay.voice-chat.enabled", true);
        this.voiceIndicatorMap = new HashMap<>();
        this.mutedPlayers = new HashSet<>();
        this.playerVolumes = new HashMap<>();
        this.globalVolume = 1.0f;
        
        // Calcula o intervalo real de gravação para reprodução fiel
        if (recording.getFrameCount() > 1) {
            long durationMs = recording.getDurationMillis();
            // Média de ms por frame
            double msPerFrame = (double) durationMs / (recording.getFrameCount() - 1);
            // Converte para ticks (1 tick = 50ms)
            this.calculatedRecordInterval = (int) Math.round(msPerFrame / 50.0);
            // Garante mínimo de 1
            if (this.calculatedRecordInterval < 1) this.calculatedRecordInterval = 1;
        } else {
            // Fallback para config se não tiver frames suficientes para calcular
            this.calculatedRecordInterval = Math.max(1, plugin.getConfig().getInt("replay.interval-ticks", 4));
        }
        
        // Posição de espectador será definida após criar o mundo
        this.spectateLocation = recording.getDeathLocation().clone().add(0, 10, 0);
        
        // Cria BossBar
        this.bossBar = Bukkit.createBossBar(
            message("replay.bossbar.title",
                    "icon", message("replay.bossbar.icon.playing"),
                    "current", "0:00",
                    "total", "0:00",
                    "speed", "1.0"),
            BarColor.YELLOW,
            BarStyle.SEGMENTED_10
        );
        this.bossBar.setProgress(0.0);
    }

    private void send(String path, Object... replacements) {
        plugin.getMessages().send(viewer, path, replacements);
    }

    private String message(String path, Object... replacements) {
        return plugin.getMessages().legacy(path, replacements);
    }

    private Component component(String path, Object... replacements) {
        return plugin.getMessages().component(path, replacements);
    }

    private List<Component> componentList(String path, Object... replacements) {
        return plugin.getMessages().componentList(path, replacements);
    }

    private String audioSelectionMenuTitle() {
        return message("replay.audio.selection-title");
    }

    private String audioPlayerMenuTitlePrefix() {
        return message("replay.audio.player-title-prefix");
    }

    private String audioPlayerMenuTitle(String targetName) {
        return audioPlayerMenuTitlePrefix() + targetName;
    }

    private String formatReplayTime(int totalSeconds) {
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private List<String> volumeLabels() {
        return plugin.getMessages().legacyList("replay.audio.volume-levels");
    }

    private String volumeOptionPrefix(boolean selected) {
        return message(selected ? "replay.audio.option-selected-prefix" : "replay.audio.option-default-prefix");
    }
    
    /**
     * Inicia o replay
     */
    public void start() {
        if (isSessionActive) return;
        
        if (recording.getDeathLocation().getWorld() == null) {
            send("replay.start.world-missing");
            return;
        }

        // Conta frames com áudio para diagnóstico
        int audioFrameCount = 0;
        for (int i = 0; i < recording.getFrameCount(); i++) {
            ReplayFrame f = recording.getFrame(i);
            if (f != null && f.hasAudioData()) audioFrameCount++;
        }
        plugin.getLogger().info("[Replay] Iniciando replay para " + viewer.getName() 
            + " | Frames: " + recording.getFrameCount()
            + " | Frames com áudio: " + audioFrameCount
            + " | Áudio: " + (audioEnabled ? "ativado" : "desativado")
            + " | VoiceChat API: " + (ReplayVoicechatPlugin.getServerApi() != null ? "disponível" : "INDISPONÍVEL"));

        send("replay.start.preparing-world");
        
        // Cria mundo temporário
        plugin.getReplayWorldManager().createReplayWorld(recording.getDeathLocation(), 50).thenAccept(info -> {
            this.replayWorldInfo = info;
            
            // Executa o resto na thread principal
            Bukkit.getScheduler().runTask(plugin, () -> {
                startSession();
            });
        }).exceptionally(e -> {
            send("replay.start.world-create-error", "error", e.getMessage());
            e.printStackTrace();
            // Remove da lista de replays ativos para não ficar orfão
            plugin.getReplayListener().unregisterReplayPlayer(viewer);
            return null;
        });
    }

    private void startSession() {
        try {
            if (replayWorldInfo == null || replayWorldInfo.world == null) {
                send("replay.start.world-create-fatal");
                isSessionActive = false;
                plugin.getReplayListener().unregisterReplayPlayer(viewer);
                return;
            }

            isSessionActive = true;
            
            // Salva local original
            this.originalLocation = viewer.getLocation();
            this.originalInventory = viewer.getInventory().getContents();
            this.originalArmor = viewer.getInventory().getArmorContents();
            this.originalHealth = viewer.getHealth();
            this.originalFood = viewer.getFoodLevel();
            this.originalSaturation = viewer.getSaturation();
            this.originalExp = viewer.getExp();
            this.originalLevel = viewer.getLevel();
            this.originalGameMode = viewer.getGameMode();
            this.originalPotionEffects = new ArrayList<>(viewer.getActivePotionEffects());
            
            // Backup de segurança em disco
            plugin.getReplayManager().savePlayerInventory(viewer);
            
            // Limpa estado do jogador
            viewer.setHealth(20);
            viewer.setFoodLevel(20);
            viewer.setSaturation(20);
            viewer.setExp(0);
            viewer.setLevel(0);
            for (PotionEffect effect : viewer.getActivePotionEffects()) {
                viewer.removePotionEffect(effect.getType());
            }
            viewer.setInvulnerable(true);
            
            // Não precisamos mais esconder jogadores, pois estamos em outro mundo
            // Mas precisamos garantir que o jogador não veja o chat global se não quisermos (opcional)
            
            // Configura WorldBorder para o espectador no novo mundo
            org.bukkit.WorldBorder border = replayWorldInfo.world.getWorldBorder();
            border.setCenter(replayWorldInfo.center);
            int radius = plugin.getConfig().getInt("replay.radius", 50);
            border.setSize(radius * 2);
            border.setWarningDistance(5);
            
            // Salva estado do jogador e coloca em modo ADVENTURE (Melhor para evitar modificações de inventário)
            viewer.setGameMode(GameMode.ADVENTURE);
            viewer.setAllowFlight(true);
            viewer.setFlying(true);
            
            // Teleporta para o novo mundo
            Location targetLoc = replayWorldInfo.transform(spectateLocation);
            if (targetLoc != null && targetLoc.getWorld() != null) {
                viewer.teleport(targetLoc);
            } else {
                throw new IllegalStateException("Local de teleporte inválido");
            }
            
            // Aguarda o cliente carregar o mundo antes de enviar pacotes de entidades e inventário
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!isSessionActive) return;

                // Visão Noturna para melhor visibilidade
                viewer.addPotionEffect(new PotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false));
                
                // Adiciona BossBar
                if (bossBar != null) {
                    bossBar.addPlayer(viewer);
                    bossBar.setVisible(true);
                }
                
                // Marca o local da morte com partículas
                showDeathMarker();
                
                // Configura hotbar
                setupHotbar();
                
                spawnInitialNPCs();
                pause();
                send("replay.start.ready");
            }, 20L);
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao iniciar sessão de replay para " + viewer.getName() + ": " + e.getMessage());
            e.printStackTrace();
            send("replay.start.error");
            stop(); // Tenta limpar e restaurar
        }
    }
    
    private void showDeathMarker() {
        if (recording.getDeathLocation() == null) return;
        
        Location deathLoc = recording.getDeathLocation();
        double x = deathLoc.getX();
        double y = deathLoc.getY();
        double z = deathLoc.getZ();
        
        if (replayWorldInfo != null) {
            x = replayWorldInfo.transformX(x);
            y = replayWorldInfo.transformY(y);
            z = replayWorldInfo.transformZ(z);
        }
        
        final double finalX = x;
        final double finalY = y;
        final double finalZ = z;
        
        // Cria um marcador visual (Beacon Beam fake usando partículas)
        deathMarkerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isSessionActive) {
                    this.cancel();
                    return;
                }
                
                // Pilar de partículas
                for (int i = 0; i < 20; i++) {
                    viewer.spawnParticle(org.bukkit.Particle.DUST, finalX, finalY + i, finalZ, 1, 0, 0, 0, 0, new org.bukkit.Particle.DustOptions(org.bukkit.Color.RED, 1));
                }
                
                // Círculo no chão
                for (int i = 0; i < 360; i += 20) {
                    double angle = Math.toRadians(i);
                    double px = finalX + Math.cos(angle) * 1.5;
                    double pz = finalZ + Math.sin(angle) * 1.5;
                    viewer.spawnParticle(org.bukkit.Particle.FLAME, px, finalY + 0.1, pz, 1, 0, 0, 0, 0);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    /**
     * Para o replay e limpa tudo
     */
    public void stop() {
        try {
            if (!isSessionActive) return;
            
            // Remove da lista de replays ativos PRIMEIRO para evitar race conditions
            plugin.getReplayListener().unregisterReplayPlayer(viewer);
            
            isSessionActive = false;
            
            if (playbackTask != null) {
                playbackTask.cancel();
                playbackTask = null;
            }
            
            if (deathMarkerTask != null) {
                deathMarkerTask.cancel();
                deathMarkerTask = null;
            }
            
            // Remove BossBar
            if (bossBar != null) {
                bossBar.removePlayer(viewer);
                bossBar.setVisible(false);
                bossBar = null; // Limpa a referência
            }
            
            // Remove todos NPCs e entidades
            try {
                // Só envia pacotes NMS se viewer estiver online (connection ativa)
                if (viewer != null && viewer.isOnline()) {
                    despawnAllNPCs();
                    despawnAllSkulls();
                    despawnAllVoiceIndicators();
                } else {
                    // Viewer offline — apenas limpa estruturas internas sem enviar pacotes
                    npcMap.clear();
                    skullMap.clear();
                    voiceIndicatorMap.clear();
                    lastSentPos.clear();
                    lastSentRot.clear();
                }
                clearAudioChannels();
                // Limpa interações orfãs que não foram removidas por despawnAllNPCs
                for (org.bukkit.entity.Interaction interaction : interactionMap.values()) {
                    if (interaction != null && !interaction.isDead()) {
                        interaction.remove();
                    }
                }
                interactionMap.clear();
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Erro ao remover NPCs/entidades: " + e.getMessage());
            }
            
            // Remove WorldBorder (não precisa pois o mundo será deletado)
            // viewer.setWorldBorder(null);

            // Restaura jogador
            boolean restored = false;
            if (viewer != null && viewer.isOnline()) {
                try {
                    viewer.setInvulnerable(false);
                    viewer.setGameMode(originalGameMode != null ? originalGameMode : GameMode.SURVIVAL);
                    viewer.getInventory().clear();
                    
                    // Restaura inventário
                    if (originalInventory != null) {
                        viewer.getInventory().setContents(originalInventory);
                    }
                    if (originalArmor != null) {
                        viewer.getInventory().setArmorContents(originalArmor);
                    }
                    
                    // Restaura status
                    viewer.setHealth(originalHealth > 0 ? originalHealth : 20);
                    viewer.setFoodLevel(originalFood);
                    viewer.setSaturation(originalSaturation);
                    viewer.setExp(originalExp);
                    viewer.setLevel(originalLevel);
                    
                    // Limpa efeitos atuais e restaura originais
                    for (PotionEffect effect : viewer.getActivePotionEffects()) {
                        viewer.removePotionEffect(effect.getType());
                    }
                    if (originalPotionEffects != null) {
                        viewer.addPotionEffects(originalPotionEffects);
                    }
                    
                    if (originalLocation != null && originalLocation.getWorld() != null) {
                        viewer.teleport(originalLocation);
                    } else {
                        // Fallback se o mundo original não existir mais (raro)
                        viewer.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
                    }
                    restored = true;
                } catch (Exception e) {
                    plugin.getLogger().severe("Erro ao restaurar status do jogador " + viewer.getName() + ": " + e.getMessage());
                    // Tenta restaurar do backup de disco como último recurso
                    plugin.getReplayManager().restorePlayerInventory(viewer);
                    restored = true; // Assumimos que o restorePlayerInventory lidou com isso ou falhou logando
                }
            }
            
            // Remove backup de disco APENAS se restauramos com sucesso
            // Se o jogador saiu (offline), mantemos o arquivo para restaurar no onPlayerJoin
            if (restored) {
                java.io.File file = new java.io.File(plugin.getDataFolder(), "userdata/replay_inv_" + viewer.getUniqueId() + ".yml");
                if (file.exists()) {
                    file.delete();
                }
            }
            
            // Deleta o mundo temporário
            if (replayWorldInfo != null && replayWorldInfo.world != null) {
                plugin.getReplayWorldManager().deleteReplayWorld(replayWorldInfo.world);
            }
            
        } catch (Exception e) {
            plugin.getLogger().severe("Erro fatal ao parar replay: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private double tickCounter = 0;
    private int lastRenderedFrameIndex = -1;

    /**
     * Reproduz ou retoma o replay
     */
    public void play() {
        isPaused = false;
        updatePlayPauseItem();
        
        // Verifica se a task está ativa
        boolean isTaskRunning = playbackTask != null && (Bukkit.getScheduler().isQueued(playbackTask.getTaskId()) || Bukkit.getScheduler().isCurrentlyRunning(playbackTask.getTaskId()));
        
        if (isTaskRunning) {
            return;
        }
        
        startPlaybackTask();
    }

    private void startPlaybackTask() {
        if (playbackTask != null) {
            playbackTask.cancel();
        }
        
        int recordInterval = this.calculatedRecordInterval;
        
        // Se estiver no fim, reinicia do zero
        if (currentFrameIndex >= recording.getFrameCount()) {
            currentFrameIndex = 0;
        }
        
        // Sincroniza tickCounter
        tickCounter = currentFrameIndex * recordInterval;
        lastRenderedFrameIndex = -1; // Força renderização
        
        playbackTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!isSessionActive) {
                if (playbackTask != null) {
                    playbackTask.cancel();
                    playbackTask = null;
                }
                return;
            }
            
            try {
                if (isPaused) {
                    return; // Apenas aguarda
                }
                
                // Avança o tempo
                tickCounter += playbackSpeed;
                
                // Audio Playback

                
                // Calcula frame atual e progresso para interpolação
                int frameIndex = (int) (tickCounter / recordInterval);
                double progress = (tickCounter % recordInterval) / (double) recordInterval;
                
                if (frameIndex >= recording.getFrameCount()) {
                    // Replay terminou
                    pause();
                    currentFrameIndex = recording.getFrameCount();
                    updateBossBar();
                    send("replay.playback.finished");
                    
                    // Cancela task para economizar recursos
                    if (playbackTask != null) {
                        playbackTask.cancel();
                        playbackTask = null;
                    }
                    return;
                }
                
                currentFrameIndex = frameIndex;

                // Renderiza frame com interpolação
                renderFrame(frameIndex, progress);
            } catch (Throwable e) {
                plugin.getLogger().severe("Erro na reprodução do replay: " + e.getMessage());
                e.printStackTrace();
                pause();
                send("replay.playback.internal-error");
            }
            
        }, 0L, 1L); // Roda a cada tick para suavidade máxima
    }
    
    /**
     * Atualiza a BossBar com informações do replay
     */
    private void updateBossBar() {
        if (bossBar == null) return;

        int recordInterval = this.calculatedRecordInterval;
        int totalFrames = recording.getFrameCount();
        
        int currentSeconds = (currentFrameIndex * recordInterval) / 20;
        int totalSeconds = (totalFrames * recordInterval) / 20;
        
        String icon = message(isPaused ? "replay.bossbar.icon.paused" : "replay.bossbar.icon.playing");
        String title = message(
                "replay.bossbar.title",
                "icon", icon,
                "current", formatReplayTime(currentSeconds),
                "total", formatReplayTime(totalSeconds),
                "speed", String.format(Locale.US, "%.1f", playbackSpeed)
        );
        
        bossBar.setTitle(title);
        bossBar.setProgress(Math.min(1.0, (double) currentFrameIndex / Math.max(1, totalFrames)));
        
        // Muda cor baseado na velocidade
        if (playbackSpeed >= 2.0f) {
            bossBar.setColor(BarColor.RED);
        } else if (playbackSpeed < 1.0f) {
            bossBar.setColor(BarColor.BLUE);
        } else {
            bossBar.setColor(BarColor.YELLOW);
        }
    }
    
    /**
     * Pausa o replay
     */
    public void pause() {
        isPaused = true;
        flushAudioChannels();
        updatePlayPauseItem();
        updateBossBar();
    }
    
    /**
     * Avança 5 segundos
     */
    public void forward() {
        int recordInterval = this.calculatedRecordInterval;
        int framesToJump = (5 * 20) / recordInterval;
        
        currentFrameIndex = Math.min(recording.getFrameCount() - 1, 
                                     currentFrameIndex + framesToJump);
        // Atualiza tickCounter para sincronizar com o novo frame
        tickCounter = currentFrameIndex * recordInterval;
        
        // Força reset visual para evitar bugs
        despawnAllNPCs();
        despawnAllSkulls();
        despawnAllVoiceIndicators();
        clearAudioChannels();
        lastRenderedFrameIndex = -1;
        
        renderFrame(currentFrameIndex, 0.0);
        updateBossBar();
        send("replay.controls.forward");
    }


    
    /**
     * Retrocede 5 segundos
     */
    public void rewind() {
        int recordInterval = this.calculatedRecordInterval;
        int framesToJump = (5 * 20) / recordInterval;
        
        currentFrameIndex = Math.max(0, currentFrameIndex - framesToJump);
        // Atualiza tickCounter para sincronizar com o novo frame
        tickCounter = currentFrameIndex * recordInterval;
        
        // Força reset visual para evitar bugs
        despawnAllNPCs();
        despawnAllSkulls();
        despawnAllVoiceIndicators();
        clearAudioChannels();
        lastRenderedFrameIndex = -1;
        
        renderFrame(currentFrameIndex, 0.0);
        updateBossBar();
        send("replay.controls.rewind");
    }
    
    /**
     * Reinicia o replay
     */
    public void restart() {
        currentFrameIndex = 0;
        tickCounter = 0;
        lastRenderedFrameIndex = -1;
        isPaused = false;
        
        // Força reset visual
        despawnAllNPCs();
        despawnAllSkulls();
        despawnAllVoiceIndicators();
        clearAudioChannels();
        
        // Se a task não estiver rodando, inicia
        boolean isTaskRunning = playbackTask != null && (Bukkit.getScheduler().isQueued(playbackTask.getTaskId()) || Bukkit.getScheduler().isCurrentlyRunning(playbackTask.getTaskId()));
        
        if (!isTaskRunning) {
            play();
        }
        
        renderFrame(0, 0.0);
        updateBossBar();
        send("replay.controls.restart");
    }
    
    /**
     * Teleporta o espectador para a posição do jogador focado
     */
    public void teleportToFocus() {
        ReplayFrame frame = recording.getFrame(currentFrameIndex);
        if (frame == null) return;
        
        ReplayFrame.PlayerSnapshot snapshot = frame.getPlayerSnapshots().get(recording.getDeathPlayerId());
        if (snapshot == null) {
            // Tenta pegar qualquer player se o principal não estiver no frame (ex: morreu)
            if (!frame.getPlayerSnapshots().isEmpty()) {
                snapshot = frame.getPlayerSnapshots().values().iterator().next();
            }
        }
        
        if (snapshot != null && snapshot.getLocation() != null) {
            double x = snapshot.getX();
            double y = snapshot.getY();
            double z = snapshot.getZ();
            
            if (replayWorldInfo != null) {
                x = replayWorldInfo.transformX(x);
                y = replayWorldInfo.transformY(y);
                z = replayWorldInfo.transformZ(z);
            }
            
            Location loc = new Location(replayWorldInfo.world, x, y, z, snapshot.getYaw(), snapshot.getPitch());
            viewer.teleport(loc);
            send("replay.controls.teleported");
        } else {
            send("replay.controls.player-not-found-frame");
        }
    }

    /**
     * Alterna velocidade (0.25x -> 0.5x -> 1x -> 2x -> 4x)
     */
    public void cycleSpeed() {
        if (playbackSpeed >= 4.0f) {
            playbackSpeed = 0.25f;
        } else if (playbackSpeed >= 2.0f) {
            playbackSpeed = 4.0f;
        } else if (playbackSpeed >= 1.0f) {
            playbackSpeed = 2.0f;
        } else if (playbackSpeed >= 0.5f) {
            playbackSpeed = 1.0f;
        } else {
            playbackSpeed = 0.5f;
        }
        
        if (!isPaused) {
            play(); // Reinicia com nova velocidade
        }
        updateSpeedItem();
        updateBossBar();
        send("replay.controls.speed", "speed", String.format(Locale.US, "%.2f", playbackSpeed));
    }
    
    /**
     * Configura os itens da hotbar (simplificados)
     */
    private void setupHotbar() {
        viewer.getInventory().clear();
        
        // Teleport to Player
        ItemStack tp = new ItemStack(Material.ENDER_EYE);
        tp.editMeta(meta -> {
            meta.displayName(component("replay.hotbar.teleport.name"));
            meta.lore(componentList("replay.hotbar.teleport.lore"));
        });
        viewer.getInventory().setItem(SLOT_TELEPORT, tp);

        ItemStack audio = new ItemStack(Material.JUKEBOX);
        audio.editMeta(meta -> {
            meta.displayName(component("replay.hotbar.audio.name"));
            meta.lore(componentList("replay.hotbar.audio.lore"));
        });
        viewer.getInventory().setItem(SLOT_AUDIO, audio);
        
        // Rewind
        ItemStack rewind = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        rewind.editMeta(meta -> {
            meta.displayName(component("replay.hotbar.rewind.name"));
            meta.lore(componentList("replay.hotbar.rewind.lore"));
        });
        viewer.getInventory().setItem(SLOT_REWIND, rewind);

        // Restart
        ItemStack restart = new ItemStack(Material.COMPASS);
        restart.editMeta(meta -> {
            meta.displayName(component("replay.hotbar.restart.name"));
            meta.lore(componentList("replay.hotbar.restart.lore"));
        });
        viewer.getInventory().setItem(SLOT_RESTART, restart);
        
        updatePlayPauseItem();
        updateSpeedItem();
        
        // Forward
        ItemStack forward = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        forward.editMeta(meta -> {
            meta.displayName(component("replay.hotbar.forward.name"));
            meta.lore(componentList("replay.hotbar.forward.lore"));
        });
        viewer.getInventory().setItem(SLOT_FORWARD, forward);

        // Exit
        ItemStack exit = new ItemStack(Material.BARRIER);
        exit.editMeta(meta -> {
            meta.displayName(component("replay.hotbar.exit.name"));
            meta.lore(componentList("replay.hotbar.exit.lore"));
        });
        viewer.getInventory().setItem(SLOT_EXIT, exit);
    }
    
    private void updatePlayPauseItem() {
        ItemStack playPause;
        if (isPaused) {
            playPause = new ItemStack(Material.LIME_DYE);
            playPause.editMeta(meta -> {
                meta.displayName(component("replay.hotbar.play.name"));
                meta.lore(componentList("replay.hotbar.play.lore"));
            });
        } else {
            playPause = new ItemStack(Material.ORANGE_DYE);
            playPause.editMeta(meta -> {
                meta.displayName(component("replay.hotbar.pause.name"));
                meta.lore(componentList("replay.hotbar.pause.lore"));
            });
        }
        viewer.getInventory().setItem(SLOT_PLAY_PAUSE, playPause);
    }
    
    /**
     * Atualiza item de velocidade
     */
    private void updateSpeedItem() {
        Material speedMaterial;
        if (playbackSpeed >= 2.0f) {
            speedMaterial = Material.FEATHER;
        } else if (playbackSpeed < 1.0f) {
            speedMaterial = Material.SUGAR;
        } else {
            speedMaterial = Material.CLOCK;
        }
        
        ItemStack speed = new ItemStack(speedMaterial);
        speed.editMeta(meta -> {
            meta.displayName(component("replay.hotbar.speed.name", "speed", String.format(Locale.US, "%.2f", playbackSpeed)));
        });
        viewer.getInventory().setItem(SLOT_SPEED, speed);
    }
    
    /**
     * Cria NPCs iniciais baseados no primeiro frame
     */
    private void spawnInitialNPCs() {
        if (recording.getFrameCount() == 0) return;
        
        ReplayFrame firstFrame = recording.getFrame(0);
        for (ReplayFrame.PlayerSnapshot snapshot : firstFrame.getPlayerSnapshots().values()) {
            createNPC(snapshot);
        }
    }
    
    /**
     * Cria um NPC para representar um jogador
     */
    private ServerPlayer createNPC(ReplayFrame.PlayerSnapshot snapshot) {
        try {
            if (snapshot.getLocation() == null || snapshot.getLocation().getWorld() == null) {
                return null;
            }
            
            ServerLevel level = ((CraftWorld) replayWorldInfo.world).getHandle();
            
            // Cria GameProfile com skin do jogador original mas UUID aleatório para evitar conflitos
            GameProfile profile = new GameProfile(UUID.randomUUID(), snapshot.getName());
            
            // Tenta obter skin do jogador original
            Player originalPlayer = Bukkit.getPlayer(snapshot.getUuid());
            if (originalPlayer != null) {
                GameProfile originalProfile = ((CraftPlayer) originalPlayer).getHandle().getGameProfile();
                originalProfile.getProperties().get("textures").forEach(property -> {
                    profile.getProperties().put("textures", property);
                });
            } else if (snapshot.getSkinTexture() != null && !snapshot.getSkinTexture().isEmpty()) {
                // Usa skin salva no snapshot (jogador offline)
                profile.getProperties().put("textures",
                    new com.mojang.authlib.properties.Property("textures", snapshot.getSkinTexture(), snapshot.getSkinSignature()));
            }
            
            // Cria o NPC
            ServerPlayer npc = new ServerPlayer(
                ((CraftServer) Bukkit.getServer()).getServer(),
                level,
                profile,
                ClientInformation.createDefault()
            );
            
            // Posiciona NPC
            double x = snapshot.getX();
            double y = snapshot.getY();
            double z = snapshot.getZ();
            
            if (replayWorldInfo != null) {
                x = replayWorldInfo.transformX(x);
                y = replayWorldInfo.transformY(y);
                z = replayWorldInfo.transformZ(z);
            }

            npc.setPos(
                x,
                y,
                z
            );
            npc.setYRot(snapshot.getYaw());
            npc.setXRot(snapshot.getPitch());
            
            // Cria entidade de interação (hitbox clicável)
            Location interactionLoc = new Location(replayWorldInfo.world, x, y, z, snapshot.getYaw(), snapshot.getPitch());
            org.bukkit.entity.Interaction interaction = (org.bukkit.entity.Interaction) interactionLoc.getWorld().spawnEntity(
                interactionLoc, 
                org.bukkit.entity.EntityType.INTERACTION
            );
            interaction.setInteractionHeight(1.8f);
            interaction.setInteractionWidth(0.6f);
            interaction.setResponsive(true);
            interactionMap.put(snapshot.getUuid(), interaction);
            
            // Envia pacotes para o viewer
            CraftPlayer craftViewer = (CraftPlayer) viewer;
            
            // 1. Adiciona o player à tab list
            // Precisamos criar a Entry manualmente para evitar NPE no npc.connection.latency()
            // Construtor: UUID, GameProfile, boolean listed, int latency, GameType gameMode, Component displayName, boolean showHat, int listOrder, RemoteChatSession.Data chatSession
            ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
                profile.getId(), // Usar o UUID do perfil (aleatório) e não o original
                profile,
                true,
                0,
                net.minecraft.world.level.GameType.SURVIVAL,
                net.minecraft.network.chat.Component.literal(snapshot.getName()),
                false,
                0,
                null
            );
            
            craftViewer.getHandle().connection.send(
                new ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                    entry
                )
            );
            
            // Remove da tablist após 20 ticks para não poluir
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (isSessionActive) {
                    craftViewer.getHandle().connection.send(
                        new ClientboundPlayerInfoRemovePacket(Collections.singletonList(profile.getId()))
                    );
                }
            }, 20L);
            
            // 2. Spawn o player como entidade
            craftViewer.getHandle().connection.send(
                new ClientboundAddEntityPacket(npc, 0, npc.blockPosition())
            );
            
            // 3. Envia metadata (skin layers, etc)
            java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> initialData = npc.getEntityData().getNonDefaultValues();
            if (initialData != null) {
                craftViewer.getHandle().connection.send(
                    new ClientboundSetEntityDataPacket(npc.getId(), initialData)
                );
            }
            
            // 4. Envia equipamentos iniciais
            List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>> initialEquipment = new ArrayList<>();
            
            ItemStack[] armor = snapshot.getArmor();
            initialEquipment.add(new com.mojang.datafixers.util.Pair<>(
                net.minecraft.world.entity.EquipmentSlot.FEET,
                (armor != null && armor.length > 0 && armor[0] != null) ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(armor[0]) : net.minecraft.world.item.ItemStack.EMPTY
            ));
            initialEquipment.add(new com.mojang.datafixers.util.Pair<>(
                net.minecraft.world.entity.EquipmentSlot.LEGS,
                (armor != null && armor.length > 1 && armor[1] != null) ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(armor[1]) : net.minecraft.world.item.ItemStack.EMPTY
            ));
            initialEquipment.add(new com.mojang.datafixers.util.Pair<>(
                net.minecraft.world.entity.EquipmentSlot.CHEST,
                (armor != null && armor.length > 2 && armor[2] != null) ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(armor[2]) : net.minecraft.world.item.ItemStack.EMPTY
            ));
            initialEquipment.add(new com.mojang.datafixers.util.Pair<>(
                net.minecraft.world.entity.EquipmentSlot.HEAD,
                (armor != null && armor.length > 3 && armor[3] != null) ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(armor[3]) : net.minecraft.world.item.ItemStack.EMPTY
            ));
            initialEquipment.add(new com.mojang.datafixers.util.Pair<>(
                net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                snapshot.getMainHand() != null ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(snapshot.getMainHand()) : net.minecraft.world.item.ItemStack.EMPTY
            ));
            initialEquipment.add(new com.mojang.datafixers.util.Pair<>(
                net.minecraft.world.entity.EquipmentSlot.OFFHAND,
                snapshot.getOffHand() != null ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(snapshot.getOffHand()) : net.minecraft.world.item.ItemStack.EMPTY
            ));
            
            craftViewer.getHandle().connection.send(
                new ClientboundSetEquipmentPacket(npc.getId(), initialEquipment)
            );
            
            npcMap.put(snapshot.getUuid(), npc);
            
            return npc;
            
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao criar NPC: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Renderiza um frame específico com interpolação
     */
    private void renderFrame(int frameIndex, double progress) {
        try {
            if (frameIndex < 0 || frameIndex >= recording.getFrameCount()) return;
            
            ReplayFrame frame = recording.getFrame(frameIndex);
            if (frame == null) return;
            
            // Próximo frame para interpolação
            ReplayFrame nextFrame = null;
            if (frameIndex + 1 < recording.getFrameCount()) {
                nextFrame = recording.getFrame(frameIndex + 1);
            }
            
            CraftPlayer craftViewer = (CraftPlayer) viewer;

            boolean isNewFrame = (frameIndex != lastRenderedFrameIndex);

            // Atualiza BossBar apenas em frames novos
            if (isNewFrame) {
                updateBossBar();
            }

            // Audio Playback - apenas em velocidade 1.0x e quando muda de frame
            if (isNewFrame && audioEnabled && playbackSpeed == 1.0f) {
                sendFrameAudio(frame);
            }

            // Renderiza Blocos e Sons apenas se mudamos de frame
            if (isNewFrame) {
                // Se pulamos frames (ex: fast forward), precisamos reproduzir os eventos dos frames pulados
                // para não perder sons ou partículas importantes.
                // Começa do próximo frame após o último renderizado, até o atual.
                int startFrame = lastRenderedFrameIndex + 1;
                
                // Se houve rewind (startFrame > frameIndex), apenas renderiza o atual
                if (startFrame > frameIndex) {
                    startFrame = frameIndex;
                }
                
                for (int i = startFrame; i <= frameIndex; i++) {
                    if (i < 0 || i >= recording.getFrameCount()) continue;
                    
                    ReplayFrame f = recording.getFrame(i);
                    if (f != null && f.getEvents() != null) {
                        for (me.ray.rpermadeath.replay.events.ReplayEvent event : f.getEvents()) {
                            if (event != null) {
                                event.play(viewer);
                            }
                        }
                    }
                }
                
                lastRenderedFrameIndex = frameIndex;
            }
            
            // Renderiza Player NPCs
            for (Map.Entry<UUID, ReplayFrame.PlayerSnapshot> entry : frame.getPlayerSnapshots().entrySet()) {
                ReplayFrame.PlayerSnapshot snapshot = entry.getValue();
                
                // Tenta pegar snapshot do próximo frame para interpolação
                ReplayFrame.PlayerSnapshot nextSnapshot = null;
                if (nextFrame != null) {
                    nextSnapshot = nextFrame.getPlayerSnapshots().get(entry.getKey());
                }
                
                // Filtra entidades fora do raio (usa primitivos para evitar alocação de Location)
                if (snapshot.getWorldName() == null ||
                    recording.getDeathLocation() == null || recording.getDeathLocation().getWorld() == null) {
                    continue;
                }

                int radius = plugin.getConfig().getInt("replay.radius", 50);
                double dx = snapshot.getX() - recording.getDeathLocation().getX();
                double dy = snapshot.getY() - recording.getDeathLocation().getY();
                double dz = snapshot.getZ() - recording.getDeathLocation().getZ();
                if (snapshot.getWorldName().equals(recording.getDeathLocation().getWorld().getName()) &&
                    (dx * dx + dy * dy + dz * dz) > (double) radius * radius) {
                    // Se o NPC existir, remove
                    if (npcMap.containsKey(entry.getKey())) {
                        ServerPlayer npcToRemove = npcMap.remove(entry.getKey());
                        removePlayerNPC(npcToRemove, entry.getKey());
                    }
                    hideVoiceIndicator(entry.getKey());
                    continue;
                }

                ServerPlayer npc = npcMap.get(entry.getKey());
                net.minecraft.world.entity.decoration.ArmorStand skull = skullMap.get(entry.getKey());

                // Verifica se o jogador está morto
                if (snapshot.getHealth() <= 0) {
                    // Se o NPC do jogador existir, remove
                    if (npc != null) {
                        removePlayerNPC(npc, entry.getKey());
                        npcMap.remove(entry.getKey());
                    }
                    hideVoiceIndicator(entry.getKey());
                    
                    // Cria ou atualiza a caveira
                    if (skull == null) {
                        skull = createSkullNPC(snapshot);
                    }
                    
                    if (skull != null) {
                        // Atualiza posição da caveira (pode ter caído no void ou algo assim, mas geralmente fica parada onde morreu)
                        // Mas como é replay, vamos manter onde morreu no frame
                        // Se quiser animar caindo, precisaria de física, mas vamos fixar na posição
                        // Na verdade, se o player morreu, a posição no snapshot deve ser a da morte.
                        // Vamos atualizar para garantir
                        Location loc = snapshot.getLocation();
                        double sx = loc.getX();
                        double sy = loc.getY();
                        double sz = loc.getZ();
                        
                        if (replayWorldInfo != null) {
                            sx = replayWorldInfo.transformX(sx);
                            sy = replayWorldInfo.transformY(sy);
                            sz = replayWorldInfo.transformZ(sz);
                        }

                        skull.setPos(sx, sy - 1.5, sz); // Ajuste de altura para ficar no chão? ArmorStand tem ~2 blocos.
                        // Se for small armor stand, o ajuste é diferente. Vamos usar normal e invisível.
                        // O head fica no topo.
                        
                        // Envia teleporte para garantir
                         net.minecraft.world.entity.PositionMoveRotation posRotSkull = new net.minecraft.world.entity.PositionMoveRotation(
                            new net.minecraft.world.phys.Vec3(sx, sy - 1.2, sz), // Ajuste fino
                            new net.minecraft.world.phys.Vec3(0, 0, 0),
                            loc.getYaw(),
                            0
                        );
                        craftViewer.getHandle().connection.send(
                            new ClientboundTeleportEntityPacket(
                                skull.getId(),
                                posRotSkull,
                                java.util.Set.of(),
                                true
                            )
                        );
                    }
                    continue; // Pula o resto da renderização do player vivo
                } else {
                    // Se o jogador está vivo, remove a caveira se existir (ex: rewind)
                    if (skull != null) {
                        removeSkullNPC(skull);
                        skullMap.remove(entry.getKey());
                    }
                }
                
                if (npc == null) {
                    npc = createNPC(snapshot);
                    if (npc == null) continue;
                }
                
                // Verifica invisibilidade e outros efeitos visuais
                boolean isInvisible = false;
                boolean isGlowing = false;
                if (snapshot.getPotionEffects() != null) {
                    for (PotionEffect effect : snapshot.getPotionEffects()) {
                        if (effect.getType().equals(org.bukkit.potion.PotionEffectType.INVISIBILITY)) {
                            isInvisible = true;
                        }
                        if (effect.getType().equals(org.bukkit.potion.PotionEffectType.GLOWING)) {
                            isGlowing = true;
                        }
                    }
                }
                
                if (isInvisible) {
                    npc.setInvisible(true);
                    // Força glowing para jogadores invisíveis para que o admin possa vê-los (outline)
                    npc.setGlowingTag(true); 
                } else {
                    npc.setInvisible(false);
                    npc.setGlowingTag(isGlowing);
                }
                
                // Efeito de dano (Apenas no início do frame)
                if (isNewFrame && snapshot.isHurt()) {
                    craftViewer.getHandle().connection.send(new ClientboundHurtAnimationPacket(npc));
                    // Toca som
                    viewer.playSound(viewer.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, playbackSpeed);
                }

                // Animação de ataque (Apenas no início do frame)
                if (isNewFrame && snapshot.isSwinging()) {
                    craftViewer.getHandle().connection.send(new ClientboundAnimatePacket(npc, 0)); // 0 = swing main hand
                }

                // Atualiza posição com interpolação
                double x = snapshot.getX();
                double y = snapshot.getY();
                double z = snapshot.getZ();
                float yaw = snapshot.getYaw();
                float pitch = snapshot.getPitch();
                
                if (nextSnapshot != null) {
                    // Verifica distância para detectar teleporte
                    double distSq = Math.pow(nextSnapshot.getX() - snapshot.getX(), 2) + 
                                    Math.pow(nextSnapshot.getY() - snapshot.getY(), 2) + 
                                    Math.pow(nextSnapshot.getZ() - snapshot.getZ(), 2);

                    if (distSq > 64) { // Se moveu mais de 8 blocos (8^2 = 64) em 1 tick -> Teleporte
                        // Não interpola, usa a posição final imediatamente ou mantém a inicial até o frame virar
                        // Se usarmos a inicial, ele "pula" no próximo frame.
                        // Se usarmos a final, ele "pula" agora.
                        // Vamos manter a posição do snapshot atual sem interpolar para o próximo
                        // até que o frame mude.
                    } else {
                        // Interpolação linear
                        x = x + (nextSnapshot.getX() - x) * progress;
                        y = y + (nextSnapshot.getY() - y) * progress;
                        z = z + (nextSnapshot.getZ() - z) * progress;
                    }
                    
                    // Interpolação de rotação (menor caminho)
                    float nextYaw = nextSnapshot.getYaw();
                    float diffYaw = nextYaw - yaw;
                    while (diffYaw < -180.0f) diffYaw += 360.0f;
                    while (diffYaw >= 180.0f) diffYaw -= 360.0f;
                    yaw = yaw + diffYaw * (float) progress;
                    
                    float nextPitch = nextSnapshot.getPitch();
                    pitch = pitch + (nextPitch - pitch) * (float) progress;
                }
                
                // Calcula movimento horizontal para corrigir partículas de sprint
                boolean isMovingInRecording = false;
                if (nextSnapshot != null) {
                    double recDx = nextSnapshot.getX() - snapshot.getX();
                    double recDz = nextSnapshot.getZ() - snapshot.getZ();
                    if (recDx * recDx + recDz * recDz > 0.0001) {
                        isMovingInRecording = true;
                    }
                }
                
                // Se não está movendo no recording, forçamos a posição exata do snapshot
                if (!isMovingInRecording) {
                    x = snapshot.getX();
                    y = snapshot.getY();
                    z = snapshot.getZ();
                    yaw = snapshot.getYaw();
                    pitch = snapshot.getPitch();
                }
                
                // Aplica offset do mundo temporário
                if (replayWorldInfo != null) {
                    x = replayWorldInfo.transformX(x);
                    y = replayWorldInfo.transformY(y);
                    z = replayWorldInfo.transformZ(z);
                }
                
                npc.setPos(x, y, z);
                npc.setYRot(yaw);
                npc.setXRot(pitch);
                
                // Atualiza entidade de interação (reutiliza spectateLocation para evitar alocação)
                org.bukkit.entity.Interaction interaction = interactionMap.get(entry.getKey());
                spectateLocation.setWorld(replayWorldInfo != null ? replayWorldInfo.world : recording.getDeathLocation().getWorld());
                spectateLocation.setX(x);
                spectateLocation.setY(y);
                spectateLocation.setZ(z);
                spectateLocation.setYaw(yaw);
                spectateLocation.setPitch(pitch);
                
                if (interaction != null) {
                    interaction.teleport(spectateLocation);
                } else {
                    // Se não existir (ex: entrou no range agora), cria
                    try {
                        Location spawnLoc = new Location(spectateLocation.getWorld(), x, y, z, yaw, pitch);
                        interaction = (org.bukkit.entity.Interaction) spawnLoc.getWorld().spawnEntity(spawnLoc, org.bukkit.entity.EntityType.INTERACTION);
                        interaction.setInteractionHeight(1.8f);
                        interaction.setInteractionWidth(0.6f);
                        interaction.setResponsive(true);
                        interactionMap.put(entry.getKey(), interaction);
                    } catch (Exception e) {
                        // Ignora erro de spawn se mundo não carregado ou algo assim
                    }
                }
                
                // Delta-check: só envia pacotes de posição/rotação quando houve mudança significativa
                UUID npcId = entry.getKey();
                double[] lastPos = lastSentPos.get(npcId);
                float[] lastRot = lastSentRot.get(npcId);
                boolean posChanged = lastPos == null || 
                    ((x - lastPos[0]) * (x - lastPos[0]) + (y - lastPos[1]) * (y - lastPos[1]) + (z - lastPos[2]) * (z - lastPos[2])) > 0.01;
                boolean rotChanged = lastRot == null || 
                    Math.abs(yaw - lastRot[0]) > 1.0f || Math.abs(pitch - lastRot[1]) > 1.0f;
                
                if (posChanged || rotChanged) {
                    // Envia pacote de movimento
                    net.minecraft.world.entity.PositionMoveRotation posRot = new net.minecraft.world.entity.PositionMoveRotation(
                        new net.minecraft.world.phys.Vec3(x, y, z),
                        new net.minecraft.world.phys.Vec3(npc.getDeltaMovement().x, npc.getDeltaMovement().y, npc.getDeltaMovement().z),
                        yaw,
                        pitch
                    );
                    craftViewer.getHandle().connection.send(
                        new ClientboundTeleportEntityPacket(
                            npc.getId(),
                            posRot,
                            java.util.Set.of(),
                            npc.onGround()
                        )
                    );
                    
                    if (posChanged) {
                        if (lastPos == null) { lastPos = new double[3]; lastSentPos.put(npcId, lastPos); }
                        lastPos[0] = x; lastPos[1] = y; lastPos[2] = z;
                    }
                }
                
                if (rotChanged) {
                    // Atualiza rotação da cabeça
                    craftViewer.getHandle().connection.send(
                        new ClientboundRotateHeadPacket(npc, (byte) ((yaw * 256.0F) / 360.0F))
                    );
                    if (lastRot == null) { lastRot = new float[2]; lastSentRot.put(npcId, lastRot); }
                    lastRot[0] = yaw; lastRot[1] = pitch;
                }
                
                // Atualiza equipamento (apenas em frames novos para economizar pacotes)
                if (isNewFrame) {
                    updateNPCEquipment(npc, snapshot, craftViewer);
                }
                
                // Atualiza pose (sneaking, etc)
                updateNPCPose(npc, snapshot, craftViewer, isMovingInRecording);

                // Indicador visual de voz (apenas em velocidade 1.0x, quando áudio toca)
                // Respeita mute: jogadores mutados mostram ícone vermelho de mudo
                if (playbackSpeed == 1.0f && audioEnabled) {
                    if (isNewFrame) {
                        if (frame.hasAudioDataFor(entry.getKey())) {
                            if (mutedPlayers.contains(entry.getKey())) {
                                showMutedVoiceIndicator(entry.getKey(), x, y, z);
                            } else {
                                showVoiceIndicator(entry.getKey(), x, y, z);
                            }
                        } else {
                            hideVoiceIndicator(entry.getKey());
                        }
                    } else {
                        net.minecraft.world.entity.Display.TextDisplay indicator = voiceIndicatorMap.get(entry.getKey());
                        if (indicator != null) {
                            updateVoiceIndicatorPosition(indicator, x, y, z);
                        }
                    }
                } else if (isNewFrame) {
                    hideVoiceIndicator(entry.getKey());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao renderizar frame " + frameIndex + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Atualiza equipamento do NPC
     */
    private void updateNPCEquipment(ServerPlayer npc, ReplayFrame.PlayerSnapshot snapshot, CraftPlayer viewer) {
        List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>> equipment = new ArrayList<>();
        
        // Converte armor
        ItemStack[] armor = snapshot.getArmor();
        
        // Sempre envia todos os slots para garantir que itens removidos sejam limpos visualmente
        equipment.add(new com.mojang.datafixers.util.Pair<>(
            net.minecraft.world.entity.EquipmentSlot.FEET,
            (armor != null && armor.length > 0 && armor[0] != null) ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(armor[0]) : net.minecraft.world.item.ItemStack.EMPTY
        ));
        
        equipment.add(new com.mojang.datafixers.util.Pair<>(
            net.minecraft.world.entity.EquipmentSlot.LEGS,
            (armor != null && armor.length > 1 && armor[1] != null) ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(armor[1]) : net.minecraft.world.item.ItemStack.EMPTY
        ));
        
        equipment.add(new com.mojang.datafixers.util.Pair<>(
            net.minecraft.world.entity.EquipmentSlot.CHEST,
            (armor != null && armor.length > 2 && armor[2] != null) ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(armor[2]) : net.minecraft.world.item.ItemStack.EMPTY
        ));
        
        equipment.add(new com.mojang.datafixers.util.Pair<>(
            net.minecraft.world.entity.EquipmentSlot.HEAD,
            (armor != null && armor.length > 3 && armor[3] != null) ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(armor[3]) : net.minecraft.world.item.ItemStack.EMPTY
        ));
        
        // Main hand
        equipment.add(new com.mojang.datafixers.util.Pair<>(
            net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            snapshot.getMainHand() != null ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(snapshot.getMainHand()) : net.minecraft.world.item.ItemStack.EMPTY
        ));

        // Off hand
        equipment.add(new com.mojang.datafixers.util.Pair<>(
            net.minecraft.world.entity.EquipmentSlot.OFFHAND,
            snapshot.getOffHand() != null ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(snapshot.getOffHand()) : net.minecraft.world.item.ItemStack.EMPTY
        ));
        
        viewer.getHandle().connection.send(
            new ClientboundSetEquipmentPacket(npc.getId(), equipment)
        );
    }
    
    /**
     * Manipula clique em entidade (Interaction)
     */
    public void handleEntityClick(org.bukkit.entity.Entity clicked) {
        if (!(clicked instanceof org.bukkit.entity.Interaction)) return;
        
        // Encontra qual snapshot corresponde a esta entidade
        UUID snapshotUuid = null;
        for (Map.Entry<UUID, org.bukkit.entity.Interaction> entry : interactionMap.entrySet()) {
            if (entry.getValue().equals(clicked)) {
                snapshotUuid = entry.getKey();
                break;
            }
        }
        
        if (snapshotUuid == null) return;
        
        // Obtém snapshot do frame atual
        ReplayFrame frame = recording.getFrame(currentFrameIndex);
        if (frame == null) return;
        
        ReplayFrame.PlayerSnapshot snapshot = frame.getPlayerSnapshots().get(snapshotUuid);
        if (snapshot == null) return;
        
        // Abre menu unificado de inspeção + controle de áudio
        openPlayerMenu(snapshotUuid, snapshot);
    }
    
    private String formatPotionEffect(PotionEffect effect) {
        String name = effect.getType().getKey().getKey().toLowerCase().replace("_", " ");
        // Capitalize
        name = name.substring(0, 1).toUpperCase() + name.substring(1);
        int amp = effect.getAmplifier() + 1;
        int duration = effect.getDuration() / 20;
        return String.format("%s %d (%ds)", name, amp, duration);
    }

    /**
     * Atualiza pose do NPC (agachado, etc)
     */
    private void updateNPCPose(ServerPlayer npc, ReplayFrame.PlayerSnapshot snapshot, CraftPlayer viewer, boolean isMovingHorizontally) {
        // Atualiza o flag de sneaking (Metadata index 0, bit 1)
        // Isso é o que visualmente faz o personagem agachar
        npc.setShiftKeyDown(snapshot.isSneaking());
        
        // Só ativa sprint se estiver se movendo horizontalmente
        // Isso evita partículas de terra quando parado
        npc.setSprinting(snapshot.isSprinting() && isMovingHorizontally);
        
        org.bukkit.entity.Pose pose = snapshot.getPose();
        if (pose != null) {
            switch (pose) {
                case SNEAKING:
                    npc.setPose(net.minecraft.world.entity.Pose.CROUCHING);
                    break;
                case SLEEPING:
                    npc.setPose(net.minecraft.world.entity.Pose.SLEEPING);
                    break;
                case SWIMMING:
                    npc.setPose(net.minecraft.world.entity.Pose.SWIMMING);
                    break;
                case SPIN_ATTACK:
                    npc.setPose(net.minecraft.world.entity.Pose.SPIN_ATTACK);
                    break;
                case FALL_FLYING:
                    npc.setPose(net.minecraft.world.entity.Pose.FALL_FLYING);
                    break;
                case DYING:
                    npc.setPose(net.minecraft.world.entity.Pose.DYING);
                    break;
                default:
                    npc.setPose(net.minecraft.world.entity.Pose.STANDING);
                    break;
            }
        } else {
            if (snapshot.isSneaking()) {
                npc.setPose(net.minecraft.world.entity.Pose.CROUCHING);
            } else {
                npc.setPose(net.minecraft.world.entity.Pose.STANDING);
            }
        }
        
        // Envia metadata
        // Precisamos garantir que os dados atualizados sejam enviados
        // getNonDefaultValues() pode não pegar se não foi marcado como dirty corretamente, 
        // mas setShiftKeyDown deve marcar.
        // Por segurança, podemos forçar o envio de todos os dados ou verificar se há mudanças.
        // Em 1.21, packDirty() retorna os dados alterados e limpa o flag dirty.
        java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> data = npc.getEntityData().packDirty();
        if (data != null) {
            viewer.getHandle().connection.send(
                new ClientboundSetEntityDataPacket(npc.getId(), data)
            );
        }
    }
    
    /**
     * Remove todos os NPCs
     */
    private void despawnAllNPCs() {
        if (npcMap.isEmpty()) return;

        for (Map.Entry<UUID, ServerPlayer> entry : npcMap.entrySet()) {
            removePlayerNPC(entry.getValue(), entry.getKey());
        }
        
        npcMap.clear();
        lastSentPos.clear();
        lastSentRot.clear();
    }

    private void removePlayerNPC(ServerPlayer npc, UUID snapshotUuid) {
        CraftPlayer craftViewer = (CraftPlayer) viewer;
        
        // Remove entidade de interação
        org.bukkit.entity.Interaction interaction = interactionMap.remove(snapshotUuid);
        if (interaction != null) {
            interaction.remove();
        }

        // Remove player info
        craftViewer.getHandle().connection.send(
            new ClientboundPlayerInfoRemovePacket(Collections.singletonList(npc.getUUID()))
        );
        
        // Remove entity
        craftViewer.getHandle().connection.send(
            new ClientboundRemoveEntitiesPacket(npc.getId())
        );
    }

    private void despawnAllSkulls() {
        if (skullMap.isEmpty()) return;
        for (net.minecraft.world.entity.decoration.ArmorStand skull : skullMap.values()) {
            removeSkullNPC(skull);
        }
        skullMap.clear();
    }

    private void removeSkullNPC(net.minecraft.world.entity.decoration.ArmorStand skull) {
        CraftPlayer craftViewer = (CraftPlayer) viewer;
        craftViewer.getHandle().connection.send(
            new ClientboundRemoveEntitiesPacket(skull.getId())
        );
    }

    private net.minecraft.world.entity.decoration.ArmorStand createSkullNPC(ReplayFrame.PlayerSnapshot snapshot) {
        try {
            if (snapshot.getLocation() == null || snapshot.getLocation().getWorld() == null) {
                return null;
            }

            ServerLevel level = ((CraftWorld) replayWorldInfo.world).getHandle();
            
            double x = snapshot.getX();
            double y = snapshot.getY();
            double z = snapshot.getZ();
            
            if (replayWorldInfo != null) {
                x = replayWorldInfo.transformX(x);
                y = replayWorldInfo.transformY(y);
                z = replayWorldInfo.transformZ(z);
            }

            net.minecraft.world.entity.decoration.ArmorStand skull = new net.minecraft.world.entity.decoration.ArmorStand(
                level,
                x,
                y - 1.2, // Ajuste de altura
                z
            );
            
            skull.setInvisible(true);
            skull.setNoGravity(true);
            skull.setCustomName(net.minecraft.network.chat.Component.literal("§c" + snapshot.getName()));
            skull.setCustomNameVisible(true);
            skull.setUUID(UUID.randomUUID());
            
            // Envia spawn
            CraftPlayer craftViewer = (CraftPlayer) viewer;
            craftViewer.getHandle().connection.send(
                new ClientboundAddEntityPacket(skull, 0, skull.blockPosition())
            );
            
            // Envia metadata
            java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> skullData = skull.getEntityData().getNonDefaultValues();
            if (skullData != null) {
                craftViewer.getHandle().connection.send(
                    new ClientboundSetEntityDataPacket(skull.getId(), skullData)
                );
            }
            
            // Cria cabeça do jogador
            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) playerHead.getItemMeta();
            if (meta != null) {
                // Evita lookup de OfflinePlayer se possível, mas SkullMeta precisa
                // Otimização: Se não tiver skin carregada, usa apenas o nome?
                // Bukkit.getOfflinePlayer(UUID) pode ser lento se não estiver em cache
                // Mas é necessário para a skin.
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(snapshot.getUuid()));
                playerHead.setItemMeta(meta);
            }
            
            // Envia equipamento (cabeça)
            List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>> equipment = new ArrayList<>();
            equipment.add(new com.mojang.datafixers.util.Pair<>(
                net.minecraft.world.entity.EquipmentSlot.HEAD,
                org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(playerHead)
            ));
            
            craftViewer.getHandle().connection.send(
                new ClientboundSetEquipmentPacket(skull.getId(), equipment)
            );
            
            skullMap.put(snapshot.getUuid(), skull);
            return skull;
            
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao criar Skull NPC: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private long lastInteractionTime = 0;

    /**
     * Manipula clique na hotbar
     */
    public void handleHotbarClick(int slot) {
        // Debounce para evitar cliques duplos (200ms)
        long now = System.currentTimeMillis();
        if (now - lastInteractionTime < 200) {
            return;
        }
        lastInteractionTime = now;
        
        // Apenas executa a ação localmente. A sincronização (broadcastAction) estava causando loops e mensagens duplas
        // se múltiplos admins estivessem assistindo, ou se o evento fosse disparado múltiplas vezes.
        // Além disso, cada espectador deve ter controle independente do seu replay.
        
        switch (slot) {
            case SLOT_TELEPORT:
                teleportToFocus();
                break;
            case SLOT_AUDIO:
                openAudioSelectionMenu();
                break;
            case SLOT_REWIND:
                rewind();
                break;
            case SLOT_PLAY_PAUSE:
                if (currentFrameIndex >= recording.getFrameCount()) {
                    restart();
                } else {
                    if (isPaused) play();
                    else pause();
                }
                break;
            case SLOT_RESTART:
                restart();
                break;
            case SLOT_SPEED:
                cycleSpeed();
                break;
            case SLOT_FORWARD:
                forward();
                break;
            case SLOT_EXIT:
                stop();
                break;
        }
    }
    
    public Player getViewer() {
        return viewer;
    }
    
    public ReplayRecording getRecording() {
        return recording;
    }

    public ReplayWorldManager.ReplayWorldInfo getReplayWorldInfo() {
        return replayWorldInfo;
    }

    public boolean isPlaying() {
        return isSessionActive;
    }

    private void openAudioSelectionMenu() {
        List<ReplayFrame.PlayerSnapshot> snapshots = getCurrentMenuSnapshots();
        if (snapshots.isEmpty()) {
            send("replay.audio.no-players");
            return;
        }

        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(
                null,
                54,
                component("replay.audio.selection-title")
        );

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        filler.editMeta(meta -> meta.displayName(Component.empty()));
        for (int slot = 45; slot <= 53; slot++) {
            inv.setItem(slot, filler);
        }

        for (int i = 0; i < Math.min(45, snapshots.size()); i++) {
            ReplayFrame.PlayerSnapshot snapshot = snapshots.get(i);
            UUID targetUuid = snapshot.getUuid();
            boolean isMuted = mutedPlayers.contains(targetUuid);
            float playerVol = playerVolumes.getOrDefault(targetUuid, 1.0f);

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            org.bukkit.inventory.meta.SkullMeta skullMeta = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
            if (skullMeta != null) {
                skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(targetUuid));
                skullMeta.displayName(component("replay.audio.selection.player-name", "player", snapshot.getName()));
                skullMeta.lore(componentList(
                        "replay.audio.selection.player-lore",
                        "volume", Math.round(playerVol * 100),
                        "status", message(isMuted ? "replay.audio.status.muted" : "replay.audio.status.active")
                ));
                head.setItemMeta(skullMeta);
            }
            inv.setItem(i, head);
        }

        if (snapshots.size() > 45) {
            ItemStack overflow = new ItemStack(Material.BOOK);
            overflow.editMeta(meta -> {
                meta.displayName(component("replay.audio.selection.overflow-name"));
                meta.lore(componentList("replay.audio.selection.overflow-lore"));
            });
            inv.setItem(45, overflow);
        }

        ItemStack globalInfo = new ItemStack(Material.JUKEBOX);
        globalInfo.editMeta(meta -> {
            meta.displayName(component("replay.audio.selection.global-name"));
            meta.lore(componentList("replay.audio.selection.global-lore", "volume", Math.round(globalVolume * 100)));
        });
        inv.setItem(49, globalInfo);

        ItemStack close = new ItemStack(Material.BARRIER);
        close.editMeta(meta -> meta.displayName(component("replay.audio.close")));
        inv.setItem(53, close);

        viewer.openInventory(inv);
    }

    private void openPlayerMenu(UUID targetUuid, ReplayFrame.PlayerSnapshot snapshot) {
        openPlayerMenu(targetUuid, snapshot.getName());
    }

    private void openPlayerMenu(UUID targetUuid, String targetName) {
        boolean isMuted = mutedPlayers.contains(targetUuid);
        float playerVol = playerVolumes.getOrDefault(targetUuid, 1.0f);
        List<String> volumeLabels = volumeLabels();
        Material[] volumeMats = {
                Material.RED_STAINED_GLASS_PANE,
                Material.ORANGE_STAINED_GLASS_PANE,
                Material.YELLOW_STAINED_GLASS_PANE,
                Material.LIME_STAINED_GLASS_PANE,
                Material.GREEN_STAINED_GLASS_PANE
        };

        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(
                null,
                27,
                component("replay.audio.player-title", "player", targetName)
        );

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta skullMeta = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(targetUuid));
            skullMeta.displayName(component("replay.audio.selection.player-name", "player", targetName));
            skullMeta.lore(componentList(
                    "replay.audio.player-head-lore",
                    "playerVolume", Math.round(playerVol * 100),
                    "globalVolume", Math.round(globalVolume * 100),
                    "status", message(isMuted ? "replay.audio.status.muted" : "replay.audio.status.active")
            ));
            head.setItemMeta(skullMeta);
        }
        inv.setItem(4, head);

        ItemStack muteItem = new ItemStack(isMuted ? Material.RED_WOOL : Material.LIME_WOOL);
        muteItem.editMeta(meta -> meta.displayName(component(
                isMuted ? "replay.audio.player-toggle.muted" : "replay.audio.player-toggle.active"
        )));
        inv.setItem(10, muteItem);

        for (int i = 0; i < VOLUME_LEVELS.length; i++) {
            float volume = VOLUME_LEVELS[i];
            boolean selected = Math.abs(playerVol - volume) < 0.01f;
            ItemStack volItem = new ItemStack(volumeMats[i]);
            if (selected) {
                volItem.setAmount(2);
            }
            int index = i;
            volItem.editMeta(meta -> meta.displayName(component(
                    "replay.audio.player-volume-option",
                    "prefix", volumeOptionPrefix(selected),
                    "label", volumeLabels.get(index)
            )));
            inv.setItem(12 + i, volItem);
        }

        for (int i = 0; i < VOLUME_LEVELS.length; i++) {
            float volume = VOLUME_LEVELS[i];
            boolean selected = Math.abs(globalVolume - volume) < 0.01f;
            ItemStack volItem = new ItemStack(volumeMats[i]);
            if (selected) {
                volItem.setAmount(2);
            }
            int index = i;
            volItem.editMeta(meta -> meta.displayName(component(
                    "replay.audio.global-volume-option",
                    "prefix", volumeOptionPrefix(selected),
                    "label", volumeLabels.get(index)
            )));
            inv.setItem(20 + i, volItem);
        }

        ItemStack back = new ItemStack(Material.ARROW);
        back.editMeta(meta -> meta.displayName(component("replay.audio.back")));
        inv.setItem(18, back);

        ItemStack close = new ItemStack(Material.BARRIER);
        close.editMeta(meta -> meta.displayName(component("replay.audio.close")));
        inv.setItem(26, close);

        viewer.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //               VOICE AUDIO CONTROLS (Mute/Volume)
    // ═══════════════════════════════════════════════════════════

    /**
     * Abre menu unificado de inspeção de jogador + controles de áudio.
     */
    private void openLegacyPlayerMenu(UUID targetUuid, ReplayFrame.PlayerSnapshot snapshot) {
        String targetName = snapshot.getName();
        // Menu de 45 slots (5 linhas)
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 45,
                component("replay.audio.player-title", "player", targetName));

        // ── Linha 1: Cabeça do jogador + Equipamento ──
        // Slot 0: Cabeça do jogador (info)
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta skullMeta = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(targetUuid));
            skullMeta.displayName(component("replay.audio.selection.player-name", "player", targetName));
            List<Component> lore = new ArrayList<>();
            lore.add(component("replay.audio.legacy.health", "value", String.format(Locale.US, "%.1f", snapshot.getHealth())));
            lore.add(component("replay.audio.legacy.food", "value", snapshot.getFoodLevel()));
            if (snapshot.getPotionEffects() != null && !snapshot.getPotionEffects().isEmpty()) {
                lore.add(component("replay.audio.legacy.effects"));
                for (PotionEffect effect : snapshot.getPotionEffects()) {
                    lore.add(component("replay.audio.legacy.effect-entry", "effect", formatPotionEffect(effect)));
                }
            }
            skullMeta.lore(lore);
            head.setItemMeta(skullMeta);
        }
        inv.setItem(0, head);

        // Slots 2-7: Equipamento (Capacete, Peitoral, Calça, Botas, Mão Principal, Mão Secundária)
        ItemStack[] armor = snapshot.getArmor();
        if (armor != null) {
            if (armor.length > 3 && armor[3] != null) inv.setItem(2, armor[3]); // Helmet
            if (armor.length > 2 && armor[2] != null) inv.setItem(3, armor[2]); // Chestplate
            if (armor.length > 1 && armor[1] != null) inv.setItem(4, armor[1]); // Leggings
            if (armor.length > 0 && armor[0] != null) inv.setItem(5, armor[0]); // Boots
        }
        if (snapshot.getMainHand() != null) inv.setItem(7, snapshot.getMainHand());
        if (snapshot.getOffHand() != null) inv.setItem(8, snapshot.getOffHand());

        // ── Linha 2: Separador ──
        ItemStack separator = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        separator.editMeta(meta -> meta.displayName(Component.empty()));
        for (int i = 9; i <= 17; i++) {
            inv.setItem(i, separator);
        }

        // ── Linha 3: Título de áudio ──
        boolean isMuted = mutedPlayers.contains(targetUuid);
        float playerVol = playerVolumes.getOrDefault(targetUuid, 1.0f);

        // Slot 18: Label de áudio
        ItemStack audioLabel = new ItemStack(Material.JUKEBOX);
        audioLabel.editMeta(meta -> {
            meta.displayName(component("replay.audio.legacy.label"));
            List<Component> lore = new ArrayList<>();
            lore.add(component("replay.audio.legacy.global-lore", "volume", Math.round(globalVolume * 100)));
            meta.lore(lore);
        });
        inv.setItem(18, audioLabel);

        // Slot 20: Mute/Unmute toggle
        ItemStack muteItem;
        if (isMuted) {
            muteItem = new ItemStack(Material.RED_WOOL);
            muteItem.editMeta(meta -> {
                meta.displayName(component("replay.audio.player-toggle.muted"));
            });
        } else {
            muteItem = new ItemStack(Material.LIME_WOOL);
            muteItem.editMeta(meta -> {
                meta.displayName(component("replay.audio.player-toggle.active"));
            });
        }
        inv.setItem(20, muteItem);

        // ── Linha 4: Volume levels ──
        // Slots 29-33: Volume levels (0%, 25%, 50%, 75%, 100%)
        List<String> volumeLabels = volumeLabels();
        Material[] volumeMats = {Material.RED_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE,
                Material.YELLOW_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE, Material.GREEN_STAINED_GLASS_PANE};
        for (int i = 0; i < VOLUME_LEVELS.length; i++) {
            float vol = VOLUME_LEVELS[i];
            boolean selected = Math.abs(playerVol - vol) < 0.01f;
            ItemStack volItem = new ItemStack(volumeMats[i]);
            if (selected) {
                volItem.setAmount(2);
            }
            int idx = i;
            volItem.editMeta(meta -> {
                meta.displayName(component(
                        "replay.audio.player-volume-option",
                        "prefix", volumeOptionPrefix(selected),
                        "label", volumeLabels.get(idx)
                ));
            });
            inv.setItem(29 + i, volItem);
        }

        // ── Linha 5: Volume global + fechar ──
        // Slots 36-40: Volume global (0%, 25%, 50%, 75%, 100%)
        List<String> globalLabels = volumeLabels();
        for (int i = 0; i < VOLUME_LEVELS.length; i++) {
            float vol = VOLUME_LEVELS[i];
            boolean selected = Math.abs(globalVolume - vol) < 0.01f;
            ItemStack volItem = new ItemStack(volumeMats[i]);
            if (selected) {
                volItem.setAmount(2);
            }
            int idx = i;
            volItem.editMeta(meta -> {
                meta.displayName(component(
                        "replay.audio.global-volume-option",
                        "prefix", volumeOptionPrefix(selected),
                        "label", globalLabels.get(idx)
                ));
            });
            inv.setItem(36 + i, volItem);
        }

        // Slot 44: Fechar
        ItemStack close = new ItemStack(Material.BARRIER);
        close.editMeta(meta -> {
            meta.displayName(component("replay.audio.close"));
        });
        inv.setItem(44, close);

        viewer.openInventory(inv);
    }

    /**
     * Processa clique no menu de controle de áudio.
     */
    void handleLegacyAudioMenuClick(int slot, ItemStack clickedItem) {
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;
        // Itens de separador ou decoração — ignora
        if (clickedItem.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

        // Extrai o nome do jogador do título do inventário
        String title = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .serialize(viewer.getOpenInventory().title());
        if (!title.startsWith(audioPlayerMenuTitlePrefix())) return;
        String targetName = title.substring(audioPlayerMenuTitlePrefix().length());

        // Encontra UUID e snapshot do jogador pelo nome no frame atual
        UUID targetUuid = findPlayerUuidByName(targetName);
        if (targetUuid == null) {
            send("replay.audio.player-not-found");
            viewer.closeInventory();
            return;
        }
        ReplayFrame.PlayerSnapshot snapshot = findPlayerSnapshot(targetUuid);
        if (snapshot == null) {
            viewer.closeInventory();
            return;
        }

        switch (slot) {
            case 20: // Mute/Unmute
                toggleMutePlayer(targetUuid, targetName);
                openPlayerMenu(targetUuid, snapshot);
                break;
            case 29: // Volume individual 0%
                setPlayerVolume(targetUuid, 0.0f);
                mutedPlayers.add(targetUuid);
                openPlayerMenu(targetUuid, snapshot);
                break;
            case 30: // Volume individual 25%
                setPlayerVolume(targetUuid, 0.25f);
                mutedPlayers.remove(targetUuid);
                openPlayerMenu(targetUuid, snapshot);
                break;
            case 31: // Volume individual 50%
                setPlayerVolume(targetUuid, 0.5f);
                mutedPlayers.remove(targetUuid);
                openPlayerMenu(targetUuid, snapshot);
                break;
            case 32: // Volume individual 75%
                setPlayerVolume(targetUuid, 0.75f);
                mutedPlayers.remove(targetUuid);
                openPlayerMenu(targetUuid, snapshot);
                break;
            case 33: // Volume individual 100%
                setPlayerVolume(targetUuid, 1.0f);
                mutedPlayers.remove(targetUuid);
                openPlayerMenu(targetUuid, snapshot);
                break;
            case 36: // Volume global 0%
                setGlobalVolume(0.0f);
                openPlayerMenu(targetUuid, snapshot);
                break;
            case 37: // Volume global 25%
                setGlobalVolume(0.25f);
                openPlayerMenu(targetUuid, snapshot);
                break;
            case 38: // Volume global 50%
                setGlobalVolume(0.5f);
                openPlayerMenu(targetUuid, snapshot);
                break;
            case 39: // Volume global 75%
                setGlobalVolume(0.75f);
                openPlayerMenu(targetUuid, snapshot);
                break;
            case 40: // Volume global 100%
                setGlobalVolume(1.0f);
                openPlayerMenu(targetUuid, snapshot);
                break;
            case 44: // Fechar
                viewer.closeInventory();
                break;
        }
    }

    void handleAudioMenuClick(int slot, ItemStack clickedItem) {
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

        String title = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .serialize(viewer.getOpenInventory().title());

        if (audioSelectionMenuTitle().equals(title)) {
            handleAudioSelectionMenuClick(slot, clickedItem);
            return;
        }

        if (clickedItem.getType() == Material.GRAY_STAINED_GLASS_PANE) return;
        if (!title.startsWith(audioPlayerMenuTitlePrefix())) return;

        String targetName = title.substring(audioPlayerMenuTitlePrefix().length());
        UUID targetUuid = getMenuPlayerUuid(viewer.getOpenInventory().getTopInventory().getItem(4));
        if (targetUuid == null) {
            send("replay.audio.player-not-found-frame");
            viewer.closeInventory();
            return;
        }

        switch (slot) {
            case 10:
                toggleMutePlayer(targetUuid, targetName);
                openPlayerMenu(targetUuid, targetName);
                break;
            case 12:
            case 13:
            case 14:
            case 15:
            case 16:
                float playerVolume = VOLUME_LEVELS[slot - 12];
                setPlayerVolume(targetUuid, playerVolume);
                if (playerVolume <= 0.0f) {
                    mutedPlayers.add(targetUuid);
                } else {
                    mutedPlayers.remove(targetUuid);
                }
                openPlayerMenu(targetUuid, targetName);
                break;
            case 18:
                openAudioSelectionMenu();
                break;
            case 20:
            case 21:
            case 22:
            case 23:
            case 24:
                setGlobalVolume(VOLUME_LEVELS[slot - 20]);
                openPlayerMenu(targetUuid, targetName);
                break;
            case 26:
                viewer.closeInventory();
                break;
            default:
                break;
        }
    }

    private void handleAudioSelectionMenuClick(int slot, ItemStack clickedItem) {
        if (slot == 53 || clickedItem.getType() == Material.BARRIER) {
            viewer.closeInventory();
            return;
        }

        if (slot < 0 || slot >= 45 || clickedItem.getType() != Material.PLAYER_HEAD) {
            return;
        }

        UUID targetUuid = getMenuPlayerUuid(clickedItem);
        if (targetUuid == null) {
            return;
        }

        String targetName = getMenuPlayerName(clickedItem, targetUuid);
        openPlayerMenu(targetUuid, targetName);
    }

    private UUID getMenuPlayerUuid(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return null;
        }

        if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta skullMeta)) {
            return null;
        }

        org.bukkit.OfflinePlayer owner = skullMeta.getOwningPlayer();
        return owner != null ? owner.getUniqueId() : null;
    }

    private String getMenuPlayerName(ItemStack item, UUID fallbackUuid) {
        if (item != null && item.hasItemMeta() && item.getItemMeta().displayName() != null) {
            return LegacyComponentSerializer.legacySection()
                    .serialize(item.getItemMeta().displayName())
                    .replaceAll("\u00A7.", "");
        }

        ReplayFrame.PlayerSnapshot snapshot = findPlayerSnapshot(fallbackUuid);
        if (snapshot != null) {
            return snapshot.getName();
        }

        return Bukkit.getOfflinePlayer(fallbackUuid).getName() != null
                ? Bukkit.getOfflinePlayer(fallbackUuid).getName()
                : fallbackUuid.toString();
    }

    private List<ReplayFrame.PlayerSnapshot> getCurrentMenuSnapshots() {
        ReplayFrame frame = recording.getFrame(currentFrameIndex);
        if (frame == null && recording.getFrameCount() > 0) {
            frame = recording.getFrame(0);
        }
        if (frame == null) {
            return Collections.emptyList();
        }

        List<ReplayFrame.PlayerSnapshot> snapshots = new ArrayList<>(frame.getPlayerSnapshots().values());
        snapshots.sort(Comparator.comparing(ReplayFrame.PlayerSnapshot::getName, String.CASE_INSENSITIVE_ORDER));
        return snapshots;
    }

    private void setGlobalVolume(float volume) {
        globalVolume = Math.max(0.0f, Math.min(1.0f, volume));
        if (globalVolume == 0.0f) {
            audioEnabled = false;
            flushAudioChannels();
            send("replay.audio.global-muted");
        } else {
            audioEnabled = true;
            send("replay.audio.global-volume", "volume", Math.round(globalVolume * 100));
        }
    }

    private ReplayFrame.PlayerSnapshot findPlayerSnapshot(UUID uuid) {
        ReplayFrame frame = recording.getFrame(currentFrameIndex);
        if (frame == null && recording.getFrameCount() > 0) {
            frame = recording.getFrame(0);
        }
        if (frame == null) return null;
        return frame.getPlayerSnapshots().get(uuid);
    }

    private void toggleMutePlayer(UUID playerUuid, String name) {
        if (mutedPlayers.contains(playerUuid)) {
            mutedPlayers.remove(playerUuid);
            send("replay.audio.player-unmuted", "player", name);
            // Limpa canal para recriar com novo volume
            Object ch = audioChannels.remove(playerUuid);
            if (ch instanceof de.maxhenkel.voicechat.api.audiochannel.AudioChannel) {
                try { ((de.maxhenkel.voicechat.api.audiochannel.AudioChannel) ch).flush(); } catch (Exception ignored) {}
            }
        } else {
            mutedPlayers.add(playerUuid);
            send("replay.audio.player-muted", "player", name);
            // Flush canal para parar áudio imediatamente
            Object ch = audioChannels.remove(playerUuid);
            if (ch instanceof de.maxhenkel.voicechat.api.audiochannel.AudioChannel) {
                try { ((de.maxhenkel.voicechat.api.audiochannel.AudioChannel) ch).flush(); } catch (Exception ignored) {}
            }
        }
    }

    private void setPlayerVolume(UUID playerUuid, float volume) {
        playerVolumes.put(playerUuid, Math.max(0.0f, Math.min(1.0f, volume)));
        // Limpa canal para recriar com novo volume
        Object ch = audioChannels.remove(playerUuid);
        if (ch instanceof de.maxhenkel.voicechat.api.audiochannel.AudioChannel) {
            try { ((de.maxhenkel.voicechat.api.audiochannel.AudioChannel) ch).flush(); } catch (Exception ignored) {}
        }
        send("replay.audio.player-volume-set", "volume", Math.round(volume * 100));
    }

    private UUID findPlayerUuidByName(String name) {
        ReplayFrame frame = recording.getFrame(currentFrameIndex);
        if (frame == null && recording.getFrameCount() > 0) {
            frame = recording.getFrame(0);
        }
        if (frame == null) return null;
        for (ReplayFrame.PlayerSnapshot snapshot : frame.getPlayerSnapshots().values()) {
            if (snapshot.getName().equals(name)) {
                return snapshot.getUuid();
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════
    //               VOICE INDICATOR (TextDisplay via packets)
    // ═══════════════════════════════════════════════════════════

    /**
     * Mostra ou atualiza o indicador de voz acima do NPC (clientside via packets)
     */
    private void showVoiceIndicator(UUID playerUuid, double x, double y, double z) {
        net.minecraft.world.entity.Display.TextDisplay indicator = voiceIndicatorMap.get(playerUuid);

        if (indicator == null) {
            ServerLevel level = ((CraftWorld) replayWorldInfo.world).getHandle();
            indicator = new net.minecraft.world.entity.Display.TextDisplay(net.minecraft.world.entity.EntityType.TEXT_DISPLAY, level);
            indicator.setUUID(UUID.randomUUID());
            indicator.setPos(x, y + 2.3, z);
            indicator.setBillboardConstraints(net.minecraft.world.entity.Display.BillboardConstraints.CENTER);
            indicator.setText(net.minecraft.network.chat.Component.literal("\uD83D\uDD0A").withStyle(s -> s.withColor(0x55FF55)));

            // Configura background transparente e see-through via entity data (métodos privados no NMS)
            try {
                java.lang.reflect.Field bgField = net.minecraft.world.entity.Display.TextDisplay.class.getDeclaredField("DATA_BACKGROUND_COLOR_ID");
                bgField.setAccessible(true);
                @SuppressWarnings("unchecked")
                net.minecraft.network.syncher.EntityDataAccessor<Integer> bgAccessor =
                    (net.minecraft.network.syncher.EntityDataAccessor<Integer>) bgField.get(null);
                indicator.getEntityData().set(bgAccessor, 0);

                java.lang.reflect.Field flagsField = net.minecraft.world.entity.Display.TextDisplay.class.getDeclaredField("DATA_STYLE_FLAGS_ID");
                flagsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                net.minecraft.network.syncher.EntityDataAccessor<Byte> flagsAccessor =
                    (net.minecraft.network.syncher.EntityDataAccessor<Byte>) flagsField.get(null);
                indicator.getEntityData().set(flagsAccessor, (byte) 0x02); // SEE_THROUGH
            } catch (Exception ignored) {}

            CraftPlayer craftViewer = (CraftPlayer) viewer;
            craftViewer.getHandle().connection.send(new ClientboundAddEntityPacket(indicator, 0, indicator.blockPosition()));
            java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> indicatorData = indicator.getEntityData().getNonDefaultValues();
            if (indicatorData != null) {
                craftViewer.getHandle().connection.send(new ClientboundSetEntityDataPacket(indicator.getId(), indicatorData));
            }

            voiceIndicatorMap.put(playerUuid, indicator);
        } else {
            updateVoiceIndicatorPosition(indicator, x, y, z);
        }
    }

    /**
     * Mostra indicador de voz "mutado" (vermelho) acima do NPC.
     */
    private void showMutedVoiceIndicator(UUID playerUuid, double x, double y, double z) {
        // Remove indicador ativo anterior se existir (pode ser o verde)
        hideVoiceIndicator(playerUuid);

        ServerLevel level = ((CraftWorld) replayWorldInfo.world).getHandle();
        net.minecraft.world.entity.Display.TextDisplay indicator = new net.minecraft.world.entity.Display.TextDisplay(
                net.minecraft.world.entity.EntityType.TEXT_DISPLAY, level);
        indicator.setUUID(UUID.randomUUID());
        indicator.setPos(x, y + 2.3, z);
        indicator.setBillboardConstraints(net.minecraft.world.entity.Display.BillboardConstraints.CENTER);
        indicator.setText(net.minecraft.network.chat.Component.literal("\uD83D\uDD07").withStyle(s -> s.withColor(0xFF5555)));

        try {
            java.lang.reflect.Field bgField = net.minecraft.world.entity.Display.TextDisplay.class.getDeclaredField("DATA_BACKGROUND_COLOR_ID");
            bgField.setAccessible(true);
            @SuppressWarnings("unchecked")
            net.minecraft.network.syncher.EntityDataAccessor<Integer> bgAccessor =
                    (net.minecraft.network.syncher.EntityDataAccessor<Integer>) bgField.get(null);
            indicator.getEntityData().set(bgAccessor, 0);

            java.lang.reflect.Field flagsField = net.minecraft.world.entity.Display.TextDisplay.class.getDeclaredField("DATA_STYLE_FLAGS_ID");
            flagsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            net.minecraft.network.syncher.EntityDataAccessor<Byte> flagsAccessor =
                    (net.minecraft.network.syncher.EntityDataAccessor<Byte>) flagsField.get(null);
            indicator.getEntityData().set(flagsAccessor, (byte) 0x02);
        } catch (Exception ignored) {}

        CraftPlayer craftViewer = (CraftPlayer) viewer;
        craftViewer.getHandle().connection.send(new ClientboundAddEntityPacket(indicator, 0, indicator.blockPosition()));
        java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> indicatorData = indicator.getEntityData().getNonDefaultValues();
        if (indicatorData != null) {
            craftViewer.getHandle().connection.send(new ClientboundSetEntityDataPacket(indicator.getId(), indicatorData));
        }

        voiceIndicatorMap.put(playerUuid, indicator);
    }

    /**
     * Atualiza posição do indicador de voz via packet de teleporte
     */
    private void updateVoiceIndicatorPosition(net.minecraft.world.entity.Display.TextDisplay indicator, double x, double y, double z) {
        indicator.setPos(x, y + 2.3, z);
        net.minecraft.world.entity.PositionMoveRotation posRot = new net.minecraft.world.entity.PositionMoveRotation(
            new net.minecraft.world.phys.Vec3(x, y + 2.3, z),
            new net.minecraft.world.phys.Vec3(0, 0, 0), 0, 0
        );
        CraftPlayer craftViewer = (CraftPlayer) viewer;
        craftViewer.getHandle().connection.send(new ClientboundTeleportEntityPacket(
            indicator.getId(), posRot, java.util.Set.of(), false
        ));
    }

    /**
     * Esconde o indicador de voz de um jogador
     */
    private void hideVoiceIndicator(UUID playerUuid) {
        net.minecraft.world.entity.Display.TextDisplay indicator = voiceIndicatorMap.remove(playerUuid);
        if (indicator != null) {
            CraftPlayer craftViewer = (CraftPlayer) viewer;
            craftViewer.getHandle().connection.send(new ClientboundRemoveEntitiesPacket(indicator.getId()));
        }
    }

    /**
     * Remove todos os indicadores de voz
     */
    private void despawnAllVoiceIndicators() {
        if (voiceIndicatorMap.isEmpty()) return;
        CraftPlayer craftViewer = (CraftPlayer) viewer;
        for (net.minecraft.world.entity.Display.TextDisplay indicator : voiceIndicatorMap.values()) {
            if (indicator != null) {
                craftViewer.getHandle().connection.send(new ClientboundRemoveEntitiesPacket(indicator.getId()));
            }
        }
        voiceIndicatorMap.clear();
    }

    // ═══════════════════════════════════════════════════════════
    //               VOICE CHAT AUDIO PLAYBACK
    // ═══════════════════════════════════════════════════════════

    /**
     * Inicializa reflection para criar LocationalSoundPacket diretamente,
     * contornando o broadcast chain do LocationalAudioChannel que depende
     * de encontrar o viewer na lista de players do mundo.
     */
    private void initAudioReflection() {
        if (audioReflectionInitialized) return;
        audioReflectionInitialized = true;
        try {
            Class<?> lspClass = Class.forName("de.maxhenkel.voicechat.voice.common.LocationSoundPacket");
            cachedLocationSoundPacketCtor = lspClass.getConstructor(
                UUID.class, UUID.class, org.bukkit.Location.class, byte[].class, long.class, float.class, String.class
            );
            Class<?> implClass = Class.forName("de.maxhenkel.voicechat.plugins.impl.packets.LocationalSoundPacketImpl");
            cachedLocationalSoundPacketImplCtor = implClass.getConstructor(lspClass);
            audioReflectionAvailable = true;
            plugin.getLogger().info("[Replay Audio] Envio direto de pacotes posicionais inicializado com sucesso");
        } catch (Exception e) {
            audioReflectionAvailable = false;
            plugin.getLogger().warning("[Replay Audio] Envio direto indisponível, usando canal estático como fallback: " + e.getMessage());
        }
    }

    /**
     * Cria um LocationalSoundPacket via reflection para envio direto ao viewer.
     */
    private de.maxhenkel.voicechat.api.packets.LocationalSoundPacket createLocationalPacket(
            UUID channelId, byte[] opusData, double x, double y, double z, long seqNum) {
        try {
            Object internalPacket = cachedLocationSoundPacketCtor.newInstance(
                channelId, channelId, new org.bukkit.Location(null, x, y, z), opusData, seqNum, 10000f, (String) null
            );
            return (de.maxhenkel.voicechat.api.packets.LocationalSoundPacket)
                    cachedLocationalSoundPacketImplCtor.newInstance(internalPacket);
        } catch (Exception e) {
            return null;
        }
    }

    private int audioDebugCounter = 0;
    private int audioSendSuccessCount = 0;

    /**
     * Envia dados de áudio de um frame ao viewer.
     * Usa envio direto de pacotes posicionais (primário) ou canal estático (fallback).
     * Respeita mute/volume por jogador e global.
     */
    private void sendFrameAudio(ReplayFrame frame) {
        if (frame == null || !frame.hasAudioData()) return;
        if (!audioEnabled || globalVolume == 0.0f) return;

        try {
            de.maxhenkel.voicechat.api.VoicechatServerApi api = ReplayVoicechatPlugin.getServerApi();
            if (api == null) {
                if (audioDebugCounter++ % 200 == 0) {
                    plugin.getLogger().warning("[Replay Audio] VoiceChat API indisponível (serverApi null) - viewer: " + viewer.getName());
                }
                return;
            }

            de.maxhenkel.voicechat.api.VoicechatConnection viewerConn = api.getConnectionOf(viewer.getUniqueId());
            if (viewerConn == null) {
                if (audioDebugCounter++ % 200 == 0) {
                    plugin.getLogger().warning("[Replay Audio] Viewer " + viewer.getName() + " sem conexão VoiceChat (mod instalado?)");
                }
                return;
            }

            if (viewerConn.isDisabled()) {
                if (audioDebugCounter++ % 200 == 0) {
                    plugin.getLogger().warning("[Replay Audio] Viewer " + viewer.getName() + " com voice chat desabilitado");
                }
                return;
            }

            initAudioReflection();

            Map<UUID, java.util.List<byte[]>> audioData = frame.getDecodedAudioData();
            boolean anyPacketSent = false;

            for (Map.Entry<UUID, java.util.List<byte[]>> entry : audioData.entrySet()) {
                UUID speakerUuid = entry.getKey();
                java.util.List<byte[]> packets = entry.getValue();
                if (packets.isEmpty()) continue;

                if (mutedPlayers.contains(speakerUuid)) continue;

                float playerVol = playerVolumes.getOrDefault(speakerUuid, 1.0f);
                float effectiveVolume = globalVolume * playerVol;
                if (effectiveVolume <= 0.01f) continue;

                ReplayFrame.PlayerSnapshot snapshot = frame.getPlayerSnapshots().get(speakerUuid);
                if (snapshot == null) continue;

                double x = snapshot.getX();
                double y = snapshot.getY();
                double z = snapshot.getZ();
                if (replayWorldInfo != null) {
                    x = replayWorldInfo.transformX(x);
                    y = replayWorldInfo.transformY(y);
                    z = replayWorldInfo.transformZ(z);
                }

                UUID channelId = speakerChannelIds.computeIfAbsent(speakerUuid, k -> UUID.randomUUID());
                boolean needsVolumeScale = effectiveVolume < 0.99f;

                for (byte[] opusData : packets) {
                    if (opusData == null || opusData.length == 0) continue;

                    byte[] dataToSend = opusData;
                    if (needsVolumeScale) {
                        byte[] scaled = scaleOpusVolume(api, opusData, effectiveVolume);
                        if (scaled != null) dataToSend = scaled;
                    }

                    boolean sent = false;

                    // Primário: envio direto de pacote posicional (áudio 3D, contorna broadcast chain)
                    if (audioReflectionAvailable) {
                        long seqNum = speakerSequenceNumbers.merge(speakerUuid, 1L, Long::sum) - 1;
                        de.maxhenkel.voicechat.api.packets.LocationalSoundPacket packet =
                                createLocationalPacket(channelId, dataToSend, x, y, z, seqNum);
                        if (packet != null) {
                            api.sendLocationalSoundPacketTo(viewerConn, packet);
                            sent = true;
                        }
                    }

                    // Fallback: canal estático (sem áudio posicional, mas funcional)
                    if (!sent) {
                        de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel staticCh =
                                getOrCreateStaticChannel(api, viewerConn, speakerUuid);
                        if (staticCh != null) {
                            staticCh.send(dataToSend);
                            sent = true;
                        }
                    }

                    if (sent) anyPacketSent = true;
                }
            }

            if (anyPacketSent && audioSendSuccessCount == 0) {
                plugin.getLogger().info("[Replay Audio] Primeiro envio de áudio com sucesso | viewer: " + viewer.getName()
                    + " | método: " + (audioReflectionAvailable ? "direto posicional" : "canal estático"));
            }
            if (anyPacketSent) audioSendSuccessCount++;

        } catch (Exception e) {
            plugin.getLogger().warning("[Replay Audio] Erro ao reproduzir áudio: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Decodifica Opus → PCM, escala o volume, e re-codifica para Opus.
     */
    private byte[] scaleOpusVolume(de.maxhenkel.voicechat.api.VoicechatServerApi api, byte[] opusData, float volume) {
        try {
            if (opusDecoder == null) {
                opusDecoder = api.createDecoder();
            }
            if (opusEncoder == null) {
                opusEncoder = api.createEncoder();
            }
            if (opusDecoder == null || opusEncoder == null) return null;

            short[] pcm = opusDecoder.decode(opusData);
            if (pcm == null || pcm.length == 0) return null;

            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int)(pcm[i] * volume)));
            }

            return opusEncoder.encode(pcm);
        } catch (Exception e) {
            opusDecoder = null;
            opusEncoder = null;
            return null;
        }
    }

    /**
     * Cria ou reutiliza um StaticAudioChannel para fallback (quando reflection falha).
     */
    private de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel getOrCreateStaticChannel(
            de.maxhenkel.voicechat.api.VoicechatServerApi api,
            de.maxhenkel.voicechat.api.VoicechatConnection viewerConn,
            UUID speakerUuid) {
        try {
            Object existing = audioChannels.get(speakerUuid);
            if (existing instanceof de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel) {
                de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel ch =
                        (de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel) existing;
                if (!ch.isClosed()) return ch;
                audioChannels.remove(speakerUuid);
            }

            UUID channelId = speakerChannelIds.computeIfAbsent(speakerUuid, k -> UUID.randomUUID());
            de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel channel = api.createStaticAudioChannel(channelId);
            if (channel == null) return null;

            channel.addTarget(viewerConn);
            audioChannels.put(speakerUuid, channel);
            return channel;
        } catch (Exception e) {
            return null;
        }
    }

    private void flushAudioChannels() {
        // Flush canais estáticos (fallback)
        for (Object obj : audioChannels.values()) {
            if (obj instanceof de.maxhenkel.voicechat.api.audiochannel.AudioChannel) {
                try {
                    ((de.maxhenkel.voicechat.api.audiochannel.AudioChannel) obj).flush();
                } catch (Exception ignored) {}
            }
        }
        // Flush via envio direto: envia pacote vazio para cada canal ativo
        if (audioReflectionAvailable && !speakerChannelIds.isEmpty()) {
            try {
                de.maxhenkel.voicechat.api.VoicechatServerApi api = ReplayVoicechatPlugin.getServerApi();
                de.maxhenkel.voicechat.api.VoicechatConnection viewerConn = api != null ? api.getConnectionOf(viewer.getUniqueId()) : null;
                if (api != null && viewerConn != null) {
                    for (Map.Entry<UUID, UUID> entry : speakerChannelIds.entrySet()) {
                        long seqNum = speakerSequenceNumbers.merge(entry.getKey(), 1L, Long::sum) - 1;
                        de.maxhenkel.voicechat.api.packets.LocationalSoundPacket packet =
                                createLocationalPacket(entry.getValue(), new byte[0], 0, 0, 0, seqNum);
                        if (packet != null) {
                            api.sendLocationalSoundPacketTo(viewerConn, packet);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void clearAudioChannels() {
        flushAudioChannels();
        audioChannels.clear();
        speakerChannelIds.clear();
        speakerSequenceNumbers.clear();
        if (opusDecoder != null) {
            try { opusDecoder.close(); } catch (Exception ignored) {}
            opusDecoder = null;
        }
        if (opusEncoder != null) {
            try { opusEncoder.close(); } catch (Exception ignored) {}
            opusEncoder = null;
        }
    }
}
