package me.ray.rpermadeath.replay;

import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.database.DatabaseManager;
import me.ray.rpermadeath.replay.audio.ReplayAudioManager;
import me.ray.rpermadeath.replay.events.ReplayEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffect;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Gerencia a gravação e armazenamento de replays
 */
public class ReplayManager {
    private final RPermadeath plugin;
    private final Map<UUID, ReplayRecording> activeRecordings;
    private final Map<UUID, ReplayRecording> completedRecordings;
    private final Set<UUID> availableReplays;
    private final Deque<ReplayFrame> recentFrames;
    private final Set<UUID> damagedPlayers;
    private final Set<UUID> swingingPlayers;
    private final List<ReplayEvent> eventBuffer;
    private static final int MAX_CACHED_RECORDINGS = 20;

    private final ReplayStorage storage;
    private BukkitTask recordingTask;
    private boolean recordingEnabled = true;
    
    private final Map<UUID, InventoryCache> inventoryCache = new HashMap<>();
    private final Map<UUID, ReplayFrame.PlayerSnapshot> lastSnapshots = new HashMap<>();
    private final Map<UUID, Long> recordingExpiration = new HashMap<>();
    private final org.bukkit.Location reusableLoc = new org.bukkit.Location(null, 0, 0, 0);

    // Monitoramento de Performance
    private long lastRecordDuration = 0;
    
    private static class InventoryCache {
        ItemStack mainHand;
        ItemStack offHand;
        ItemStack[] armor;
    }

    // Configurações
    private int preDeathSeconds;
    private int postDeathSeconds;
    private int replayRadius;
    private int recordInterval;
    private static final int BASE_FPS = 20;
    private int preDeathFrames;

    private void cacheRecording(UUID id, ReplayRecording recording) {
        completedRecordings.put(id, recording);
        // Evict oldest entries if cache is too large
        while (completedRecordings.size() > MAX_CACHED_RECORDINGS) {
            Iterator<UUID> it = completedRecordings.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }


    public ReplayManager(RPermadeath plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.storage = new ReplayStorage(plugin, databaseManager);

        this.activeRecordings = new ConcurrentHashMap<>();
        this.completedRecordings = new ConcurrentHashMap<>();
        this.availableReplays = ConcurrentHashMap.newKeySet();
        this.recentFrames = new ConcurrentLinkedDeque<>();
        this.damagedPlayers = ConcurrentHashMap.newKeySet();
        this.swingingPlayers = ConcurrentHashMap.newKeySet();
        this.eventBuffer = Collections.synchronizedList(new ArrayList<>());

        loadConfig();
        cleanupOldReplays();
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            availableReplays.addAll(storage.getReplayIds());
        });

        if (recordingEnabled) {
            startRecording();
        }
    }
    public void addEvent(ReplayEvent event) {
        if (recordingEnabled) {
            eventBuffer.add(event);
        }
    }

    public void recordDamage(Player victim, String damagerName, double damage) {
        if (recordingEnabled) {
            addEvent(new me.ray.rpermadeath.replay.events.DamageReplayEvent(damage, damagerName, victim.getName()));
            markDamaged(victim.getUniqueId());
        }
    }





    public boolean isRecording(Player player) {
        return recordingEnabled;
    }

    public void reload() {
        boolean currentStatus = this.recordingEnabled;
        loadConfig();
        boolean newConfigValue = this.recordingEnabled;
        
        // Reverte para o status atual para que o setRecordingEnabled detecte a mudança se houver
        this.recordingEnabled = currentStatus;
        setRecordingEnabled(newConfigValue);
    }

    private void loadConfig() {
        this.recordingEnabled = plugin.getConfig().getBoolean("replay.background-recording-enabled", true);
        this.preDeathSeconds = plugin.getConfig().getInt("replay.time-before", 10);
        this.postDeathSeconds = plugin.getConfig().getInt("replay.time-after", 10);
        this.replayRadius = plugin.getConfig().getInt("replay.radius", 30);
        // Força um intervalo mínimo de 4 ticks (5 FPS) para economizar recursos
        this.recordInterval = Math.max(4, plugin.getConfig().getInt("replay.interval-ticks", 4));
   // Limita o tempo de gravação para evitar arquivos gigantes
        if (this.preDeathSeconds > 30) this.preDeathSeconds = 30;
        if (this.postDeathSeconds > 15) this.postDeathSeconds = 15;
        
        int fps = BASE_FPS / recordInterval;
        this.preDeathFrames = preDeathSeconds * fps;
    }

    private void cleanupOldReplays() {
        int retentionDays = plugin.getConfig().getInt("replay.retention-days", 0);
        if (retentionDays > 0) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                storage.cleanupOldReplays(retentionDays);
            });
        }
    }
    
    public void markDamaged(UUID uuid) {
        damagedPlayers.add(uuid);
    }

    public void markSwinging(UUID uuid) {
        swingingPlayers.add(uuid);
    }
    
    public void clearCache(UUID uuid) {
        inventoryCache.remove(uuid);
        lastSnapshots.remove(uuid);
        recordingExpiration.remove(uuid);
    }

    public void stopRecording(UUID uuid) {
        ReplayRecording rec = activeRecordings.remove(uuid);
        if (rec != null) {
            rec.finalizeRecording();
            storage.saveReplay(rec);
            availableReplays.add(uuid);
            plugin.getLogger().info("[Replay] Gravação finalizada (Player Quit): " + uuid + " | Frames: " + rec.getFrameCount());
            // plugin.getLogger().info("Gravação finalizada (Player Quit): " + uuid);
        }
    }

    public void savePlayerInventory(Player player) {
        File file = new File(plugin.getDataFolder(), "userdata/replay_inv_" + player.getUniqueId() + ".yml");
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        
        YamlConfiguration config = new YamlConfiguration();
        config.set("inventory", player.getInventory().getContents());
        config.set("armor", player.getInventory().getArmorContents());
        config.set("gamemode", player.getGameMode().toString());
        config.set("location", player.getLocation());
        
        // Status
        config.set("health", player.getHealth());
        config.set("food", player.getFoodLevel());
        config.set("saturation", player.getSaturation());
        config.set("exp", player.getExp());
        config.set("level", player.getLevel());
        config.set("potions", player.getActivePotionEffects());
        
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Erro ao salvar inventário de backup para " + player.getName() + ": " + e.getMessage());
        }
    }
    
    public void restorePlayerInventory(Player player) {
        File file = new File(plugin.getDataFolder(), "userdata/replay_inv_" + player.getUniqueId() + ".yml");
        if (!file.exists()) return;
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        
        // Restaura apenas se o arquivo for válido
        try {
            List<?> invList = config.getList("inventory");
            if (invList != null) {
                ItemStack[] content = invList.toArray(new ItemStack[0]);
                player.getInventory().setContents(content);
            }
            
            List<?> armorList = config.getList("armor");
            if (armorList != null) {
                ItemStack[] armor = armorList.toArray(new ItemStack[0]);
                player.getInventory().setArmorContents(armor);
            }
            
            String gm = config.getString("gamemode");
            if (gm != null) {
                try {
                    player.setGameMode(org.bukkit.GameMode.valueOf(gm));
                } catch (IllegalArgumentException ignored) {}
            }
            
            org.bukkit.Location loc = (org.bukkit.Location) config.get("location");
            if (loc != null) {
                player.teleport(loc);
            }
            
            // Restaura status
            if (config.contains("health")) player.setHealth(config.getDouble("health"));
            if (config.contains("food")) player.setFoodLevel(config.getInt("food"));
            if (config.contains("saturation")) player.setSaturation((float) config.getDouble("saturation"));
            if (config.contains("exp")) player.setExp((float) config.getDouble("exp"));
            if (config.contains("level")) player.setLevel(config.getInt("level"));
            
            List<?> potions = config.getList("potions");
            if (potions != null) {
                for (Object obj : potions) {
                    if (obj instanceof PotionEffect) {
                        player.addPotionEffect((PotionEffect) obj);
                    }
                }
            }
            
            plugin.getMessages().send(player, "reset.backup-restored");
            
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao restaurar inventário de " + player.getName() + ": " + e.getMessage());
        } finally {
            // Deleta o arquivo após restaurar para evitar duplicação ou restauração acidental futura
            file.delete();
        }
    }

    /**
     * Inicia a gravação contínua de frames
     */
    private void startRecording() {
        if (recordingTask != null && !recordingTask.isCancelled()) {
            recordingTask.cancel();
        }

        recordingTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                long startTime = System.nanoTime();
                long currentTime = System.currentTimeMillis();
                
                // Safety check
                if (activeRecordings == null || recentFrames == null) return;

                ReplayFrame frame = new ReplayFrame(currentTime);
                
                Set<UUID> processedEntities = new HashSet<>();
                
                // Lista de jogadores online
                List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
                
                // SISTEMA DE PROXIMIDADE: Grava apenas jogadores próximos a outros jogadores
                // Usa bucketing por chunk para evitar O(N²) comparações
                long expirationTime = currentTime + 20000; // 20 segundos
                
                // Marca jogadores em combate diretamente
                for (Player p : onlinePlayers) {
                    if (p == null || !p.isOnline()) continue;
                    if (damagedPlayers.contains(p.getUniqueId()) || swingingPlayers.contains(p.getUniqueId())) {
                        recordingExpiration.put(p.getUniqueId(), expirationTime);
                    }
                }
                
                // Bucketing por chunk: agrupa jogadores pelo chunk em que estão
                int chunkRadius = (replayRadius >> 4) + 1; // raio em chunks
                Map<Long, List<Player>> chunkBuckets = new HashMap<>();
                for (Player p : onlinePlayers) {
                    if (p == null || !p.isOnline()) continue;
                    int cx = p.getLocation().getBlockX() >> 4;
                    int cz = p.getLocation().getBlockZ() >> 4;
                    long key = ((long) p.getWorld().getUID().hashCode() << 32) | (((long) cx & 0xFFFFL) << 16) | ((long) cz & 0xFFFFL);
                    chunkBuckets.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                }
                
                int radiusSq = replayRadius * replayRadius;
                for (Player p1 : onlinePlayers) {
                    if (p1 == null || !p1.isOnline()) continue;
                    int cx = p1.getLocation().getBlockX() >> 4;
                    int cz = p1.getLocation().getBlockZ() >> 4;
                    
                    // Verifica apenas chunks adjacentes dentro do raio
                    for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                            long neighborKey = ((long) p1.getWorld().getUID().hashCode() << 32) | (((long) (cx + dx) & 0xFFFFL) << 16) | ((long) (cz + dz) & 0xFFFFL);
                            List<Player> bucket = chunkBuckets.get(neighborKey);
                            if (bucket == null) continue;
                            for (Player p2 : bucket) {
                                if (p1 == p2) continue;
                                if (p1.getWorld() != p2.getWorld()) continue;
                                if (p1.getLocation().distanceSquared(p2.getLocation()) <= radiusSq) {
                                    recordingExpiration.put(p1.getUniqueId(), expirationTime);
                                    recordingExpiration.put(p2.getUniqueId(), expirationTime);
                                }
                            }
                        }
                    }
                }
                
                // Limpa expirações antigas e define quem gravar
                recordingExpiration.entrySet().removeIf(entry -> entry.getValue() < currentTime);
                Set<UUID> playersToRecord = new HashSet<>(recordingExpiration.keySet());
                
            // Limpa caches internos de jogadores que não estão sendo gravados
            // NÃO remove snapshots de recentFrames — eles são necessários para replays de morte
            lastSnapshots.keySet().removeIf(uuid -> !playersToRecord.contains(uuid));
            inventoryCache.keySet().removeIf(uuid -> !playersToRecord.contains(uuid));
            
            for (Player player : onlinePlayers) {
                if (!playersToRecord.contains(player.getUniqueId())) {
                    continue;
                }
                
                boolean hurt = damagedPlayers.contains(player.getUniqueId());
                boolean swinging = swingingPlayers.contains(player.getUniqueId());
                
                // Otimização de Memória: Reutilização de ItemStacks com comparação leve
                InventoryCache cache = inventoryCache.computeIfAbsent(player.getUniqueId(), k -> new InventoryCache());
                
                ItemStack currentMain = player.getInventory().getItemInMainHand();
                if (shouldUpdateItem(cache.mainHand, currentMain)) {
                    cache.mainHand = currentMain.clone();
                }
                
                ItemStack currentOff = player.getInventory().getItemInOffHand();
                if (shouldUpdateItem(cache.offHand, currentOff)) {
                    cache.offHand = currentOff.clone();
                }
                
                ItemStack[] currentArmor = player.getInventory().getArmorContents();
                // Verifica se o array de armadura mudou
                boolean armorChanged = false;
                if (cache.armor == null) {
                    armorChanged = true;
                } else {
                    if (cache.armor.length != currentArmor.length) {
                        armorChanged = true;
                    } else {
                        for (int i = 0; i < currentArmor.length; i++) {
                            if (shouldUpdateItem(cache.armor[i], currentArmor[i])) {
                                armorChanged = true;
                                break;
                            }
                        }
                    }
                }

                if (armorChanged) {
                    cache.armor = new ItemStack[currentArmor.length];
                    for (int i = 0; i < currentArmor.length; i++) {
                        cache.armor[i] = currentArmor[i] != null ? currentArmor[i].clone() : null;
                    }
                }

                // Otimização de Deduplicação de Snapshots
                // Reutiliza o objeto Location para evitar alocação
                player.getLocation(reusableLoc);
                
                ReplayFrame.PlayerSnapshot lastSnapshot = lastSnapshots.get(player.getUniqueId());
                ReplayFrame.PlayerSnapshot snapshot;
                
                if (lastSnapshot != null && lastSnapshot.matches(
                        reusableLoc,
                        player.getHealth(),
                        player.getFoodLevel(),
                        cache.mainHand,
                        cache.offHand,
                        cache.armor,
                        player.isSneaking(),
                        player.isSprinting(),
                        player.isBlocking(),
                        hurt,
                        swinging,
                        player.getPose()
                )) {
                    // Se nada mudou, reutiliza o objeto snapshot anterior
                    snapshot = lastSnapshot;
                } else {
                    // Se mudou, cria novo e atualiza cache
                    String skinTexture = "";
                    String skinSignature = "";
                    try {
                        com.destroystokyo.paper.profile.PlayerProfile profile = player.getPlayerProfile();
                        for (com.destroystokyo.paper.profile.ProfileProperty prop : profile.getProperties()) {
                            if ("textures".equals(prop.getName())) {
                                skinTexture = prop.getValue();
                                skinSignature = prop.getSignature();
                                break;
                            }
                        }
                    } catch (Exception ignored) {}

                    snapshot = new ReplayFrame.PlayerSnapshot(
                        player.getUniqueId(),
                        player.getName(),
                        reusableLoc, // O construtor extrai os valores, então é seguro passar o objeto reutilizável
                        player.getHealth(),
                        player.getFoodLevel(),
                        cache.mainHand,
                        cache.offHand,
                        cache.armor,
                        player.isSneaking(),
                        player.isSprinting(),
                        player.isBlocking(),
                        player.getActivePotionEffects(),
                        hurt,
                        swinging,
                        player.getPose(),
                        skinTexture,
                        skinSignature
                    );
                    lastSnapshots.put(player.getUniqueId(), snapshot);
                }
                
                frame.addPlayerSnapshot(snapshot);
                processedEntities.add(player.getUniqueId());

                // Captura entidades próximas (Desativado para economizar espaço - Apenas Players)
            }
            
            // Adiciona eventos do buffer
            synchronized (eventBuffer) {
                for (ReplayEvent event : eventBuffer) {
                    frame.addEvent(event);
                }
                eventBuffer.clear();
            }

            // Captura dados de áudio do Simple Voice Chat
            ReplayAudioManager audioMgr = plugin.getReplayAudioManager();
            if (audioMgr != null && audioMgr.isEnabled()) {
                Map<UUID, java.util.List<byte[]>> audioData = audioMgr.drainAudioBuffer();
                if (!audioData.isEmpty()) {
                    attachAudioSpeakerSnapshots(frame, audioData, expirationTime);
                    frame.addAudioData(audioData);
                }
            }

            // Limpa listas do tick
            damagedPlayers.clear();
            swingingPlayers.clear();
            
            // Adiciona à fila de frames recentes
            recentFrames.addLast(frame);
            
            // Mantém apenas os últimos frames necessários
            while (recentFrames.size() > preDeathFrames) {
                recentFrames.removeFirst();
            }
            
            // Adiciona aos recordings ativos (pós-morte)
            Iterator<Map.Entry<UUID, ReplayRecording>> iterator = activeRecordings.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, ReplayRecording> entry = iterator.next();
                ReplayRecording recording = entry.getValue();
                
                // Filtra apenas jogadores próximos para economizar espaço
                ReplayFrame filteredFrame = frame.filter(recording.getDeathLocation(), replayRadius);
                recording.addFrame(filteredFrame);
                
                // Verifica se deve finalizar
                if (recording.getState() == ReplayRecording.State.POST_DEATH) {
                    long timeSincePostDeath = currentTime - recording.getPostDeathStartTime();
                    if (timeSincePostDeath >= postDeathSeconds * 1000) {
                        recording.finalizeRecording();
                        storage.saveReplay(recording);
                        availableReplays.add(entry.getKey());
                        
                        iterator.remove();
                        
                        // Contabiliza frames com snapshots para diagnóstico
                        int framesWithData = 0;
                        for (int fi = 0; fi < recording.getFrameCount(); fi++) {
                            ReplayFrame f = recording.getFrame(fi);
                            if (f != null && !f.getPlayerSnapshots().isEmpty()) framesWithData++;
                        }
                        plugin.getLogger().info("[Replay] Finalizado e salvo para " + entry.getKey() 
                            + " | Total frames: " + recording.getFrameCount()
                            + " | Com dados: " + framesWithData);
                    }
                }
                // Se for DOWNED, continua gravando indefinidamente
            }
            
                lastRecordDuration = System.nanoTime() - startTime;
            } catch (Throwable e) {
                plugin.getLogger().severe("Erro no loop de gravação de replay: " + e.getMessage());
                e.printStackTrace();
            }
        }, 0L, recordInterval); // Executa a cada X ticks
    }
    
    /**
     * Inicia a gravação quando um jogador morre
     */
    public void startDeathRecording(Player player) {
        startDeathRecording(player, null);
    }
    
    /**
     * Inicia a gravação quando um jogador morre, incluindo o assassino
     */
    public void startDeathRecording(Player player, Player killer) {
        if (!recordingEnabled) {
            return;
        }

        UUID playerId = player.getUniqueId();
        
        // Se já estiver gravando (ex: estava downed), apenas transita para POST_DEATH
        if (activeRecordings.containsKey(playerId)) {
            confirmDeath(player);
            return;
        }
        
        // Força a inclusão do jogador morto e do assassino no sistema de gravação
        // para que os frames pós-morte também capturem jogadores próximos
        long postDeathExpiration = System.currentTimeMillis() + (postDeathSeconds + 2) * 1000L;
        recordingExpiration.put(playerId, postDeathExpiration);
        if (killer != null) {
            recordingExpiration.put(killer.getUniqueId(), postDeathExpiration);
        }
        
        // Cria nova gravação
        ReplayRecording recording = new ReplayRecording(playerId, player.getLocation());
        recording.setState(ReplayRecording.State.POST_DEATH);
        recording.setPostDeathStartTime(System.currentTimeMillis());
        
        // Captura o snapshot final do jogador antes de morrer
        ReplayFrame.PlayerSnapshot deathSnapshot = capturePlayerSnapshot(player);
        
        // Adiciona os últimos frames pré-morte (filtrados por distância)
        // Usa filter() que cria cópias dos frames, evitando mutação de frames compartilhados
        int copiedFrames = 0;
        for (ReplayFrame frame : recentFrames) {
            ReplayFrame filtered = frame.filter(player.getLocation(), replayRadius);
            // Injeta snapshot de morte na cópia filtrada (não no frame original)
            if (deathSnapshot != null && !filtered.getPlayerSnapshots().containsKey(playerId)) {
                filtered.addPlayerSnapshot(deathSnapshot);
            }
            if (!filtered.getPlayerSnapshots().isEmpty()) {
                copiedFrames++;
            }
            recording.addFrame(filtered);
        }
        
        // Adiciona aos recordings ativos para continuar gravando por mais X segundos
        activeRecordings.put(playerId, recording);
        
        plugin.getLogger().info("[Replay] Gravação iniciada para " + player.getName() 
            + " | Frames pré-morte: " + recentFrames.size() 
            + " | Com snapshots: " + copiedFrames);
    }
    
    /**
     * Captura um snapshot do jogador no momento atual
     */
    private ReplayFrame.PlayerSnapshot capturePlayerSnapshot(Player player) {
        return capturePlayerSnapshot(player, true, false);
    }

    private ReplayFrame.PlayerSnapshot capturePlayerSnapshot(Player player, boolean hurt, boolean swinging) {
        try {
            String skinTexture = "";
            String skinSignature = "";
            try {
                com.destroystokyo.paper.profile.PlayerProfile profile = player.getPlayerProfile();
                for (com.destroystokyo.paper.profile.ProfileProperty prop : profile.getProperties()) {
                    if ("textures".equals(prop.getName())) {
                        skinTexture = prop.getValue();
                        skinSignature = prop.getSignature();
                        break;
                    }
                }
            } catch (Exception ignored) {}
            
            return new ReplayFrame.PlayerSnapshot(
                player.getUniqueId(),
                player.getName(),
                player.getLocation(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getInventory().getItemInMainHand().clone(),
                player.getInventory().getItemInOffHand().clone(),
                player.getInventory().getArmorContents().clone(),
                player.isSneaking(),
                player.isSprinting(),
                player.isBlocking(),
                player.getActivePotionEffects(),
                hurt,
                swinging,
                player.getPose(),
                skinTexture,
                skinSignature
            );
        } catch (Exception e) {
            plugin.getLogger().warning("[Replay] Erro ao capturar snapshot de morte: " + e.getMessage());
            return null;
        }
    }

    private void attachAudioSpeakerSnapshots(ReplayFrame frame, Map<UUID, List<byte[]>> audioData, long expirationTime) {
        for (UUID speakerId : audioData.keySet()) {
            recordingExpiration.put(speakerId, expirationTime);

            if (frame.getPlayerSnapshots().containsKey(speakerId)) {
                continue;
            }

            Player speaker = Bukkit.getPlayer(speakerId);
            if (speaker == null || !speaker.isOnline()) {
                continue;
            }

            ReplayFrame.PlayerSnapshot snapshot = capturePlayerSnapshot(
                    speaker,
                    damagedPlayers.contains(speakerId),
                    swingingPlayers.contains(speakerId)
            );
            if (snapshot != null) {
                frame.addPlayerSnapshot(snapshot);
            }
        }
    }

    /**
     * Inicia a gravação quando um jogador cai (ReviveMe)
     */
    public void startDownedRecording(Player player) {
        if (!recordingEnabled) {
            // plugin.getLogger().info("Gravação de replay está desativada. Não será criado replay para " + player.getName());
            return;
        }

        UUID playerId = player.getUniqueId();
        if (activeRecordings.containsKey(playerId)) return;

        ReplayRecording recording = new ReplayRecording(playerId, player.getLocation());
        recording.setState(ReplayRecording.State.DOWNED);
        
        // Adiciona os últimos frames pré-morte (pré-downed), filtrados por distância
        int audioFrames = 0;
        for (ReplayFrame frame : recentFrames) {
            ReplayFrame filtered = frame.filter(player.getLocation(), replayRadius);
            if (filtered.hasAudioData()) audioFrames++;
            recording.addFrame(filtered);
        }
        
        activeRecordings.put(playerId, recording);
        plugin.getLogger().info("[Replay] Gravação iniciada (downed) para " + player.getName()
            + " | Frames pré-downed: " + recentFrames.size()
            + " | Com áudio: " + audioFrames);
    }

    /**
     * Transita de DOWNED para POST_DEATH
     */
    public void confirmDeath(Player player) {
        ReplayRecording recording = activeRecordings.get(player.getUniqueId());
        if (recording != null && recording.getState() == ReplayRecording.State.DOWNED) {
            recording.setState(ReplayRecording.State.POST_DEATH);
            recording.setPostDeathStartTime(System.currentTimeMillis());
            // plugin.getLogger().info("Confirmou morte para replay de " + player.getName());
        } else if (recording == null) {
            startDeathRecording(player);
        }
    }

    /**
     * Cancela a gravação (jogador revivido)
     */
    public void cancelRecording(Player player) {
        if (activeRecordings.remove(player.getUniqueId()) != null) {
            // plugin.getLogger().info("Cancelou gravação de replay (revivido) para " + player.getName());
        }
    }
    
    /**
     * Obtém um replay completo
     */
    public ReplayRecording getRecording(UUID playerId) {
        return completedRecordings.get(playerId);
    }
    
    /**
     * Verifica se existe um replay para o jogador
     */
    public boolean hasRecording(UUID playerId) {
        // Só retorna true se estiver em availableReplays (salvo em disco) ou completedRecordings (cacheado)
        // Ignora activeRecordings (ainda gravando)
        return availableReplays.contains(playerId) || completedRecordings.containsKey(playerId);
    }
    
    public boolean isRecordingActive(UUID playerId) {
        return activeRecordings.containsKey(playerId);
    }
    
    public void loadRecording(UUID playerId, java.util.function.Consumer<ReplayRecording> callback) {
        if (completedRecordings.containsKey(playerId)) {
            callback.accept(completedRecordings.get(playerId));
            return;
        }
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                java.util.List<ReplayStorage.ReplayMetadata> replays = storage.listReplays(playerId);
                if (!replays.isEmpty()) {
                    ReplayRecording loaded = storage.loadReplay(replays.get(0).getId());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (loaded != null) {
                            cacheRecording(playerId, loaded);
                        }
                        callback.accept(loaded);
                    });
                } else {
                    plugin.getLogger().warning("[Replay] Nenhum replay encontrado no banco de dados para " + playerId);
                    Bukkit.getScheduler().runTask(plugin, () -> callback.accept(null));
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Erro ao carregar replay para " + playerId + ": " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(null));
            }
        });
    }

    public void loadRecording(int replayId, java.util.function.Consumer<ReplayRecording> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ReplayRecording loaded = storage.loadReplay(replayId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (loaded != null) {
                        cacheRecording(loaded.getDeathPlayerId(), loaded);
                    }
                    callback.accept(loaded);
                });
            } catch (Exception e) {
                plugin.getLogger().severe("Erro ao carregar replay ID " + replayId + ": " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(null));
            }
        });
    }

    public int getReplayCount(UUID playerId) {
        java.util.List<ReplayStorage.ReplayMetadata> replays = storage.listReplays(playerId);
        return replays.size();
    }

    public java.util.List<ReplayStorage.ReplayMetadata> getReplays(UUID playerId) {
        return storage.listReplays(playerId);
    }
    
    /**
     * Remove um replay
     */
    public void removeRecording(UUID playerId) {
        completedRecordings.remove(playerId);
        activeRecordings.remove(playerId);
    }

    /**
     * Deleta permanentemente todos os replays de um jogador (DB e Arquivos)
     */
    public void deleteReplays(UUID playerId) {
        removeRecording(playerId); // Limpa cache
        availableReplays.remove(playerId); // Remove da lista de disponíveis
        storage.deleteReplays(playerId); // Deleta fisicamente
    }
    
    /**
     * Para a gravação
     */
    public void shutdown() {
        try {
            if (recordingTask != null) {
                recordingTask.cancel();
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao cancelar tarefa de gravação: " + e.getMessage());
        }
        // Salva gravações ativas antes de limpar
        for (Map.Entry<UUID, ReplayRecording> entry : activeRecordings.entrySet()) {
            try {
                ReplayRecording rec = entry.getValue();
                rec.finalizeRecording();
                storage.saveReplay(rec);
                plugin.getLogger().info("[Replay] Gravação salva no shutdown para " + entry.getKey());
            } catch (Exception e) {
                plugin.getLogger().severe("[Replay] Erro ao salvar gravação no shutdown: " + e.getMessage());
            }
        }
        activeRecordings.clear();
        completedRecordings.clear();
        recentFrames.clear();
    }
    
    /**
     * Obtém estatísticas
     */
    public String getStats() {
        return String.format("Frames em buffer: %d | Gravações ativas: %d | Replays completos: %d",
            recentFrames.size(), activeRecordings.size(), completedRecordings.size());
    }

    public long getEstimatedMemoryUsage() {
        long estimatedBytes = 0;
        
        // Estimativa base por frame (overhead de objeto + timestamp) ~ 64 bytes
        // + Snapshots de jogadores e entidades
        
        // Como iterar sobre todos os frames pode ser pesado, vamos pegar uma amostra
        int sampleSize = Math.min(recentFrames.size(), 10);
        if (sampleSize == 0) return 0;
        
        long sampleBytes = 0;
        int count = 0;
        
        for (ReplayFrame frame : recentFrames) {
            if (count >= sampleSize) break;
            
            sampleBytes += 64; // Base frame overhead
            
            // Players
            sampleBytes += frame.getPlayerSnapshots().size() * 512; // Estimativa conservadora de 512 bytes por player snapshot (incluindo itens simplificados)
            
            count++;
        }
        
        long avgFrameSize = sampleBytes / sampleSize;
        estimatedBytes = avgFrameSize * recentFrames.size();
        
        // Adiciona gravações ativas
        for (ReplayRecording rec : activeRecordings.values()) {
            estimatedBytes += rec.getFrameCount() * avgFrameSize;
        }
        
        // Adiciona replays completos em memória
        for (ReplayRecording rec : completedRecordings.values()) {
            estimatedBytes += rec.getFrameCount() * avgFrameSize;
        }
        
        return estimatedBytes;
    }
    
    /**
     * Verifica se o item deve ser atualizado no cache.
     * Evita comparação pesada de NBT se o tipo/quantidade/meta básica forem iguais.
     */
    private boolean shouldUpdateItem(ItemStack cached, ItemStack current) {
        if (cached == null && current == null) return false;
        if (cached == null || current == null) return true;
        
        // Verifica tipo e quantidade primeiro (muito rápido)
        if (cached.getType() != current.getType()) return true;
        if (cached.getAmount() != current.getAmount()) return true;
        
        // Se for AIR, são iguais
        if (cached.getType() == org.bukkit.Material.AIR) return false;
        
        // Verifica se tem meta
        boolean hasMeta1 = cached.hasItemMeta();
        boolean hasMeta2 = current.hasItemMeta();
        if (hasMeta1 != hasMeta2) return true;
        
        // Se ambos têm meta, faz uma verificação simplificada para evitar NBT pesado
        if (hasMeta1) {
            // Se tiver encantamentos, verifica se mudaram (para o brilho)
            if (cached.getEnchantments().size() != current.getEnchantments().size()) return true;
            
            // Para itens complexos, infelizmente precisamos do equals completo ou isSimilar
            // Mas podemos assumir que se o tipo/qtd/encants são iguais, para fins de REPLAY VISUAL, está ok.
            // O equals completo é necessário se quisermos precisão absoluta.
            // Vamos usar isSimilar que é um pouco mais leve que equals (ignora quantidade)
            return !cached.isSimilar(current);
        }
        
        return false;
    }

    public List<String> getReplayPlayerNames() {
        List<String> names = new ArrayList<>();
        synchronized (availableReplays) {
            for (UUID uuid : availableReplays) {
                org.bukkit.OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                if (player.getName() != null) {
                    names.add(player.getName());
                }
            }
        }
        return names;
    }

    public void setRecordingEnabled(boolean enabled) {
        if (this.recordingEnabled == enabled) return;
        this.recordingEnabled = enabled;
        
        if (enabled) {
            startRecording();
            plugin.getLogger().info("Sistema de gravação de replay ATIVADO.");
        } else {
            if (recordingTask != null) {
                recordingTask.cancel();
                recordingTask = null;
            }
            // Limpa buffers
            recentFrames.clear();
            activeRecordings.clear();
            inventoryCache.clear();
            plugin.getLogger().info("Sistema de gravação de replay DESATIVADO.");
        }
    }

    public boolean isRecordingEnabled() {
        return recordingEnabled;
    }
    
    public String getPerformanceStats() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        
        double tps = Bukkit.getTPS()[0];
        
        int cachedFrames = recentFrames.size();
        int activeRecs = activeRecordings.size();
        int cachedSnapshots = lastSnapshots.size();
        
        // Calcula tamanho estimado do cache de frames (muito aproximado)
        // Cada frame tem um mapa de snapshots.
        int totalSnapshotsInHistory = 0;
        for (ReplayFrame frame : recentFrames) {
            totalSnapshotsInHistory += frame.getPlayerSnapshots().size();
        }
        
        double avgDurationMs = lastRecordDuration / 1_000_000.0;
        
        // Calcula estatísticas
        int totalPlayers = Bukkit.getOnlinePlayers().size();
        int trackedPlayers = lastSnapshots.size();
        
        StringBuilder sb = new StringBuilder();
        sb.append("§6§lᴘᴇʀꜰᴏʀᴍᴀɴᴄᴇ ᴅᴏ ʀᴇᴘʟᴀʏ\n");
        sb.append(String.format("§7ᴛᴘs ᴅᴏ sᴇʀᴠɪᴅᴏʀ: §f%.2f\n", tps));
        sb.append(String.format("§7ᴍᴇᴍóʀɪᴀ ᴊᴠᴍ: §f%dᴍʙ / %dᴍʙ\n", usedMemory, maxMemory));
        sb.append(String.format("§7sᴛᴀᴛᴜs ɢʀᴀᴠᴀçãᴏ: %s\n", recordingEnabled ? "§aᴀᴛɪᴠᴀᴅᴏ" : "§cᴅᴇsᴀᴛɪᴠᴀᴅᴏ"));
        sb.append(String.format("§7ᴛᴇᴍᴘᴏ ᴘʀᴏᴄᴇssᴀᴍᴇɴᴛᴏ (úʟᴛɪᴍᴏ ᴛɪᴄᴋ): §f%.4f ᴍs\n", avgDurationMs));
        sb.append(String.format("§7ɪɴᴛᴇʀᴠᴀʟᴏ ᴅᴇ ɢʀᴀᴠᴀçãᴏ: §f%d ᴛɪᴄᴋs\n", recordInterval));
        sb.append(String.format("§7ᴊᴏɢᴀᴅᴏʀᴇs ᴏɴʟɪɴᴇ: §f%d §7| ʀᴀsᴛʀᴇᴀᴅᴏs: §c%d §7(§a%.1f%% ᴇᴄᴏɴᴏᴍɪᴀ§7)\n", 
            totalPlayers, trackedPlayers, 
            totalPlayers > 0 ? (100.0 - (trackedPlayers * 100.0 / totalPlayers)) : 0));
        sb.append(String.format("§7ꜰʀᴀᴍᴇs ᴇᴍ ᴄᴀᴄʜᴇ (ᴘʀé-ᴍᴏʀᴛᴇ): §f%d\n", cachedFrames));
        sb.append(String.format("§7ᴛᴏᴛᴀʟ sɴᴀᴘsʜᴏᴛs ᴇᴍ ᴍᴇᴍóʀɪᴀ: §f%d\n", totalSnapshotsInHistory));
        sb.append(String.format("§7ᴊᴏɢᴀᴅᴏʀᴇs ʀᴀsᴛʀᴇᴀᴅᴏs (ᴄᴀᴄʜᴇ): §f%d\n", cachedSnapshots));
        sb.append(String.format("§7ɢʀᴀᴠᴀções ᴀᴛɪᴠᴀs (ᴘós-ᴍᴏʀᴛᴇ): §f%d\n", activeRecs));
        
        return sb.toString();
    }

    public ReplayStorage getStorage() {
        return storage;
    }
}
