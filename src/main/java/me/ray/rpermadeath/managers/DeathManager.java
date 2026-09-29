package me.ray.rpermadeath.managers;

import me.ray.rpermadeath.RPermadeath;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DeathManager {

    private final RPermadeath plugin;
    private final me.ray.rpermadeath.database.DatabaseManager databaseManager;
    private final Set<UUID> deadPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, DeathInfo> deathInfoMap = new ConcurrentHashMap<>();
    private final Map<UUID, DeathState> playerStates = new ConcurrentHashMap<>();
    private final Set<UUID> recentlyRevived = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> recentlyRevivedTime = new ConcurrentHashMap<>();
    private Location deathSpawn;
    private Location purgatorySpawn;
    private Location ressSpawn;
    private boolean globalEnabled;
    private List<String> disabledRegions;

    private static final class LoadedDeathLocation {
        private final String worldName;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;

        private LoadedDeathLocation(String worldName, double x, double y, double z, float yaw, float pitch) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    public DeathManager(RPermadeath plugin, me.ray.rpermadeath.database.DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        
        // Limpeza periódica do recentlyRevived para evitar crescimento unbounded (TTL: 5 minutos)
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            recentlyRevivedTime.entrySet().removeIf(e -> now - e.getValue() > 300_000);
            recentlyRevived.retainAll(recentlyRevivedTime.keySet());
        }, 6000L, 6000L); // A cada 5 minutos
    }

    public void load() {
        deathSpawn = readLocation("death-spawn");
        purgatorySpawn = readLocation("purgatory-spawn");
        ressSpawn = readLocation("respawn");
        
        globalEnabled = plugin.getConfig().getBoolean("permadeath.enabled", true);
        disabledRegions = plugin.getConfig().getStringList("permadeath.disabled-regions");

        // Preserva recentlyRevived entre reloads para evitar re-inserção de registros deletados
        // NÃO limpar recentlyRevived aqui

        deadPlayers.clear();
        deathInfoMap.clear();
        playerStates.clear();

        if (databaseManager != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try (java.sql.Connection conn = databaseManager.getConnection()) {
                    if (conn == null) {
                        plugin.getLogger().severe("ERRO CRITICO: Conexão com banco de dados retornou null no load()! Jogadores mortos NÃO serão carregados. Verifique a configuração do banco de dados.");
                        return;
                    }
                    
                    Set<UUID> loadedDead = ConcurrentHashMap.newKeySet();
                    Map<UUID, DeathInfo> loadedInfo = new ConcurrentHashMap<>();
                    Map<UUID, DeathState> loadedStates = new ConcurrentHashMap<>();
                    Map<UUID, LoadedDeathLocation> loadedLocations = new ConcurrentHashMap<>();
                    
                    try (java.sql.Statement stmt = conn.createStatement();
                         java.sql.ResultSet rs = stmt.executeQuery("SELECT * FROM midgard_deaths WHERE death_state != 'ALIVE'")) {
                        
                        while (rs.next()) {
                            try {
                                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                                
                                // Ignora jogadores recentemente revividos (proteção contra re-inserção)
                                if (recentlyRevived.contains(uuid)) {
                                    continue;
                                }
                                
                                String name = rs.getString("player_name");
                                java.sql.Timestamp ts = rs.getTimestamp("death_time");
                                LocalDateTime deathTime = ts != null ? ts.toLocalDateTime() : LocalDateTime.now();
                                
                                String killerIdStr = rs.getString("killer_uuid");
                                UUID killerId = killerIdStr != null ? UUID.fromString(killerIdStr) : null;
                                String killerName = rs.getString("killer_name");
                                
                                String worldName = rs.getString("world");
                                
                                String stateStr = rs.getString("death_state");
                                // Ignorar registros de jogadores já revividos (estado ALIVE)
                                if ("ALIVE".equals(stateStr)) {
                                    continue;
                                }

                                // Location será resolvida na main thread se necessário
                                final double x = rs.getDouble("x");
                                final double y = rs.getDouble("y");
                                final double z = rs.getDouble("z");
                                final float yaw = rs.getFloat("yaw");
                                final float pitch = rs.getFloat("pitch");

                                if (worldName != null && !worldName.isBlank()) {
                                    loadedLocations.put(uuid, new LoadedDeathLocation(worldName, x, y, z, yaw, pitch));
                                }
                                
                                // Cria info com location null temporariamente
                                DeathInfo info = new DeathInfo(uuid, name, deathTime, killerId, killerName, null);
                                loadedInfo.put(uuid, info);
                                loadedDead.add(uuid);
                                
                                if (stateStr != null) {
                                    try {
                                        loadedStates.put(uuid, DeathState.valueOf(stateStr));
                                    } catch (IllegalArgumentException ignored) {}
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning("Erro ao carregar registro de morte do DB (Skipping): " + e.getMessage());
                            }
                        }
                    }
                    
                    // Limpa registros órfãos com estado ALIVE do banco
                    try (java.sql.Statement cleanupStmt = conn.createStatement()) {
                        int removed = cleanupStmt.executeUpdate("DELETE FROM midgard_deaths WHERE death_state = 'ALIVE'");
                        if (removed > 0) {
                            plugin.getLogger().info("Removidos " + removed + " registros órfãos (ALIVE) do Banco de Dados.");
                        }
                    }
                    
                    // Aplica os dados carregados na main thread
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        // Re-filtra recentlyRevived antes de aplicar
                        // (jogador pode ter sido revivido enquanto a query async rodava)
                        for (UUID revived : recentlyRevived) {
                            loadedDead.remove(revived);
                            loadedInfo.remove(revived);
                            loadedStates.remove(revived);
                            loadedLocations.remove(revived);
                        }

                        Map<UUID, DeathInfo> resolvedInfo = new ConcurrentHashMap<>();
                        for (Map.Entry<UUID, DeathInfo> entry : loadedInfo.entrySet()) {
                            UUID uuid = entry.getKey();
                            DeathInfo info = entry.getValue();

                            Location deathLocation = null;
                            LoadedDeathLocation loadedLocation = loadedLocations.get(uuid);
                            if (loadedLocation != null) {
                                World world = Bukkit.getWorld(loadedLocation.worldName);
                                if (world != null) {
                                    deathLocation = new Location(
                                            world,
                                            loadedLocation.x,
                                            loadedLocation.y,
                                            loadedLocation.z,
                                            loadedLocation.yaw,
                                            loadedLocation.pitch
                                    );
                                } else {
                                    plugin.getLogger().warning("Mundo da morte nao encontrado ao carregar registro de " + info.getPlayerName() + ": " + loadedLocation.worldName);
                                }
                            }

                            resolvedInfo.put(uuid, new DeathInfo(
                                    uuid,
                                    info.getPlayerName(),
                                    info.getDeathTime(),
                                    info.getKillerId(),
                                    info.getKillerName(),
                                    deathLocation
                            ));
                        }

                        deadPlayers.addAll(loadedDead);
                        deathInfoMap.putAll(resolvedInfo);
                        playerStates.putAll(loadedStates);
                        plugin.getLogger().info("Carregados " + deadPlayers.size() + " jogadores mortos do Banco de Dados.");
                        
                        // Log detalhado dos jogadores carregados
                        for (Map.Entry<UUID, DeathInfo> entry : resolvedInfo.entrySet()) {
                            DeathState st = loadedStates.getOrDefault(entry.getKey(), DeathState.VALHALLA);
                            plugin.getLogger().info("  - " + entry.getValue().getPlayerName() + " (" + entry.getKey() + ") estado=" + st);
                        }
                        
                        // Atualiza ghosts para jogadores online
                        if (plugin.getGhostManager() != null) {
                            for (Player p : Bukkit.getOnlinePlayers()) {
                                plugin.getGhostManager().updateGhost(p);
                            }
                        }
                    });
                    
                } catch (java.sql.SQLException e) {
                    plugin.getLogger().severe("Erro ao carregar mortes do DB: " + e.getMessage());
                }
            });
        } else {
            plugin.getLogger().warning("DatabaseManager é null no load(). Sistema de banco de dados desativado?");
        }
    }

    public boolean shouldProcessDeath(Location location) {
        if (!globalEnabled) {
            return false;
        }
        
        if (plugin.getWorldGuardManager() != null) {
            if (plugin.getWorldGuardManager().isInRegion(location, disabledRegions)) {
                return false;
            }
        }
        
        return true;
    }

    public boolean isGlobalEnabled() {
        return globalEnabled;
    }

    public void setGlobalEnabled(boolean enabled) {
        this.globalEnabled = enabled;
        plugin.getConfig().set("permadeath.enabled", enabled);
        plugin.saveConfig();
    }

    public List<String> getDisabledRegions() {
        if (disabledRegions == null) {
            disabledRegions = new ArrayList<>();
        }
        return disabledRegions;
    }

    public void addDisabledRegion(String region) {
        if (disabledRegions == null) {
            disabledRegions = new ArrayList<>();
        }
        if (!disabledRegions.contains(region)) {
            disabledRegions.add(region);
            plugin.getConfig().set("permadeath.disabled-regions", disabledRegions);
            plugin.saveConfig();
        }
    }

    public void removeDisabledRegion(String region) {
        if (disabledRegions != null && disabledRegions.contains(region)) {
            disabledRegions.remove(region);
            plugin.getConfig().set("permadeath.disabled-regions", disabledRegions);
            plugin.saveConfig();
        }
    }

    public boolean isDead(UUID playerId) {
        return deadPlayers.contains(playerId);
    }

    public void markDead(Player player) {
        markDead(player, null, player.getLocation(), getDefaultDeathState());
    }

    public void markDead(Player player, Player killer, Location deathLocation) {
        markDead(player, killer, deathLocation, getDefaultDeathState());
    }

    public void markDead(Player player, Player killer, Location deathLocation, DeathState initialState) {
        try {
            UUID playerId = player.getUniqueId();
            recentlyRevived.remove(playerId);
            recentlyRevivedTime.remove(playerId);
            deadPlayers.add(playerId);
            
            // Define estado inicial
            DeathState resolvedState = initialState == null || initialState == DeathState.ALIVE
                    ? getDefaultDeathState()
                    : initialState;
            setDeathState(playerId, resolvedState);
            
            LocalDateTime deathTime = LocalDateTime.now();
            UUID killerId = killer != null ? killer.getUniqueId() : null;
            String killerName = killer != null ? killer.getName() : "Desconhecido";
            
            DeathInfo info = new DeathInfo(playerId, player.getName(), deathTime, killerId, killerName, deathLocation);
            deathInfoMap.put(playerId, info);
            
            saveDeathInfo(info);
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao marcar jogador como morto: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public DeathState getDeathState(UUID playerId) {
        if (!isDead(playerId)) return DeathState.ALIVE;
        return playerStates.getOrDefault(playerId, getDefaultDeathState());
    }

    public void setDeathState(UUID playerId, DeathState state) {
        if (state == DeathState.ALIVE) {
            playerStates.remove(playerId);
        } else {
            playerStates.put(playerId, state);
        }

        // Update DB (async para não bloquear a main thread)
        if (databaseManager != null) {
            final String stateName = state.name();
            final String uuid = playerId.toString();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                // Se o jogador foi revivido ou não está mais morto, não atualizar
                if (recentlyRevived.contains(playerId) || !deadPlayers.contains(playerId)) {
                    return;
                }
                try (java.sql.Connection conn = databaseManager.getConnection()) {
                    if (conn == null) {
                        plugin.getLogger().severe("ERRO: Conexão null ao atualizar death_state para " + uuid + " (estado: " + stateName + ")");
                        return;
                    }
                    String sql = "UPDATE midgard_deaths SET death_state = ? WHERE player_uuid = ?";
                    try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, stateName);
                        stmt.setString(2, uuid);
                        stmt.executeUpdate();
                    }
                } catch (java.sql.SQLException e) {
                    plugin.getLogger().severe("Erro ao atualizar estado de morte no DB: " + e.getMessage());
                }
            });
        }
    }

    private DeathState getDefaultDeathState() {
        String stateName = plugin.getConfig().getString("death.default-type", "VALHALLA");
        try {
            DeathState state = DeathState.valueOf(stateName);
            if (state == DeathState.PURGATORY) {
                plugin.getLogger().warning("death.default-type=PURGATORY nao e suportado. Usando VALHALLA como padrao.");
                return DeathState.VALHALLA;
            }
            return state;
        } catch (IllegalArgumentException e) {
            return DeathState.VALHALLA;
        }
    }

    public DeathInfo getDeathInfo(UUID playerId) {
        return deathInfoMap.get(playerId);
    }

    public Set<UUID> getDeadPlayers() {
        return new HashSet<>(deadPlayers);
    }

    public void revive(UUID playerId) {
        try {
            recentlyRevived.add(playerId);
            recentlyRevivedTime.put(playerId, System.currentTimeMillis());
            deadPlayers.remove(playerId);
            deathInfoMap.remove(playerId);
            playerStates.remove(playerId);

            // Deleta do banco de dados com retry para garantir que o registro é removido
            boolean deleted = deleteFromDbWithRetry(playerId, 3);
            if (!deleted) {
                plugin.getLogger().severe("[REVIVE] FALHA CRITICA: Não foi possível deletar registro de " + playerId + " do banco após 3 tentativas! O jogador pode reaparecer como morto após restart.");
            }
            
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) {
                // Remove ghost mode se estiver ativo
                if (plugin.getGhostManager() != null) {
                    // Força remoção completa
                    plugin.getGhostManager().removeGhost(p);
                }

                // Remove Auto Delete Item
                for (int i = 0; i < p.getInventory().getSize(); i++) {
                    ItemStack item = p.getInventory().getItem(i);
                    if (item != null && item.hasItemMeta()) {
                        if (item.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin, "auto_delete_item"), PersistentDataType.BYTE)) {
                            p.getInventory().setItem(i, null);
                        }
                    }
                }

                p.setGameMode(org.bukkit.GameMode.SURVIVAL);
                if (ressSpawn != null) {
                    p.teleport(ressSpawn);
                } else {
                    Location bed = p.getRespawnLocation();
                    if (bed != null) p.teleport(bed);
                    else p.teleport(p.getWorld().getSpawnLocation());
                }
                plugin.getMessages().send(p, "death.revived-player");
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao reviver jogador: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void reviveWithReset(UUID playerId, String playerName) {
        try {
            // 1. Limpa dados
            new me.ray.rpermadeath.utils.PlayerDataWiper(plugin).wipeData(playerId, playerName);
            
            // 2. Revive normalmente
            revive(playerId);
            
            // 3. Se estiver online, limpa inventário atual também (pois wipeData deleta arquivos, mas não limpa memória se online)
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) {
                p.getInventory().clear();
                p.getInventory().setArmorContents(null);
                p.setExp(0);
                p.setLevel(0);
                p.setHealth(20);
                p.setFoodLevel(20);
                p.getActivePotionEffects().forEach(effect -> p.removePotionEffect(effect.getType()));
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao reviver com reset: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public String getDeathDate(UUID playerId) {
        DeathInfo info = getDeathInfo(playerId);
        if (info == null) return plugin.getMessages().raw("general.unknown");
        return info.getDeathTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    public String getDeathCause(UUID playerId) {
        DeathInfo info = getDeathInfo(playerId);
        if (info == null) return plugin.getMessages().raw("general.unknown");
        return plugin.getMessages().applyPlaceholders(
                plugin.getMessages().raw("death.cause-format"),
                "killer", info.getKillerName()
        );
    }

    public String getDeathLocationString(UUID playerId) {
        DeathInfo info = getDeathInfo(playerId);
        if (info == null || info.getDeathLocation() == null) return plugin.getMessages().raw("general.unknown");
        Location loc = info.getDeathLocation();
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "?";
        return plugin.getMessages().applyPlaceholders(
                plugin.getMessages().raw("death.location-format"),
                "world", worldName,
                "x", loc.getBlockX(),
                "y", loc.getBlockY(),
                "z", loc.getBlockZ()
        );
    }

    public List<String> getDeadPlayerNames() {
        List<String> names = new ArrayList<>();
        for (UUID uuid : deadPlayers) {
            DeathInfo info = deathInfoMap.get(uuid);
            if (info != null && info.getPlayerName() != null) {
                names.add(info.getPlayerName());
            }
        }
        return names;
    }

    public Location getDeathSpawn() {
        return deathSpawn;
    }

    public void setDeathSpawn(Location loc) {
        this.deathSpawn = loc;
        writeLocation("death-spawn", loc);
        plugin.saveConfig();
    }

    public Location getRessSpawn() {
        return ressSpawn;
    }

    public Location getPurgatorySpawn() {
        return purgatorySpawn;
    }

    public void setRessSpawn(Location loc) {
        this.ressSpawn = loc;
        writeLocation("respawn", loc);
        plugin.saveConfig();
    }

    public void setPurgatorySpawn(Location loc) {
        this.purgatorySpawn = loc;
        writeLocation("purgatory-spawn", loc);
        plugin.saveConfig();
    }

    private Location readLocation(String path) {
        try {
            ConfigurationSection sec = plugin.getConfig().getConfigurationSection(path);
            if (sec == null) return null;
            String worldName = sec.getString("world");
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            double x = sec.getDouble("x");
            double y = sec.getDouble("y");
            double z = sec.getDouble("z");
            float yaw = (float) sec.getDouble("yaw");
            float pitch = (float) sec.getDouble("pitch");
            return new Location(world, x, y, z, yaw, pitch);
        } catch (Exception e) {
            plugin.getLogger().warning("Erro ao ler localização de " + path + ": " + e.getMessage());
            return null;
        }
    }

    private void writeLocation(String path, Location loc) {
        try {
            plugin.getConfig().set(path + ".world", loc.getWorld().getName());
            plugin.getConfig().set(path + ".x", loc.getX());
            plugin.getConfig().set(path + ".y", loc.getY());
            plugin.getConfig().set(path + ".z", loc.getZ());
            plugin.getConfig().set(path + ".yaw", loc.getYaw());
            plugin.getConfig().set(path + ".pitch", loc.getPitch());
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao escrever localização em " + path + ": " + e.getMessage());
        }
    }

    private void saveDeathInfo(DeathInfo info) {
        // Não re-insere dados de jogadores revividos ou que não estão mais mortos
        if (recentlyRevived.contains(info.getPlayerId())) return;
        if (!deadPlayers.contains(info.getPlayerId())) return;
        // Save to DB (async para não bloquear a main thread)
        if (databaseManager != null) {
            // Captura valores imutáveis antes de ir para async
            final UUID playerId = info.getPlayerId();
            final String playerName = info.getPlayerName();
            final LocalDateTime deathTime = info.getDeathTime();
            final UUID killerId = info.getKillerId();
            final String killerName = info.getKillerName();
            final Location deathLoc = info.getDeathLocation();
            final String worldName = (deathLoc != null && deathLoc.getWorld() != null) ? deathLoc.getWorld().getName() : null;
            final double x = deathLoc != null ? deathLoc.getX() : 0;
            final double y = deathLoc != null ? deathLoc.getY() : 0;
            final double z = deathLoc != null ? deathLoc.getZ() : 0;
            final float yaw = deathLoc != null ? deathLoc.getYaw() : 0;
            final float pitch = deathLoc != null ? deathLoc.getPitch() : 0;
            final DeathState state = getDeathState(playerId);
            
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                // Re-verifica após entrar no async: jogador pode ter sido revivido enquanto task esperava
                if (recentlyRevived.contains(playerId) || !deadPlayers.contains(playerId)) {
                    plugin.getLogger().info("[saveDeathInfo] INSERT cancelado para " + playerName + " - jogador já não está morto.");
                    return;
                }
                
                try (java.sql.Connection conn = databaseManager.getConnection()) {
                    if (conn == null) {
                        plugin.getLogger().severe("ERRO CRITICO: Conexão com banco de dados retornou null no saveDeathInfo()! Morte de " + playerName + " NÃO foi salva no banco!");
                        return;
                    }
                    String sql = "INSERT INTO midgard_deaths (player_uuid, player_name, death_time, killer_uuid, killer_name, world, x, y, z, yaw, pitch, death_state) " +
                              "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                              "ON CONFLICT(player_uuid) DO UPDATE SET " +
                              "player_name=excluded.player_name, " +
                              "death_time=excluded.death_time, " +
                              "killer_uuid=excluded.killer_uuid, " +
                              "killer_name=excluded.killer_name, " +
                              "world=excluded.world, " +
                              "x=excluded.x, " +
                              "y=excluded.y, " +
                              "z=excluded.z, " +
                              "yaw=excluded.yaw, " +
                              "pitch=excluded.pitch, " +
                              "death_state=excluded.death_state";
                    
                    try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, playerId.toString());
                        stmt.setString(2, playerName);
                        stmt.setTimestamp(3, java.sql.Timestamp.valueOf(deathTime));
                        stmt.setString(4, killerId != null ? killerId.toString() : null);
                        stmt.setString(5, killerName);
                        
                        if (worldName != null) {
                            stmt.setString(6, worldName);
                            stmt.setDouble(7, x);
                            stmt.setDouble(8, y);
                            stmt.setDouble(9, z);
                            stmt.setFloat(10, yaw);
                            stmt.setFloat(11, pitch);
                        } else {
                            stmt.setNull(6, java.sql.Types.VARCHAR);
                            stmt.setNull(7, java.sql.Types.DOUBLE);
                            stmt.setNull(8, java.sql.Types.DOUBLE);
                            stmt.setNull(9, java.sql.Types.DOUBLE);
                            stmt.setNull(10, java.sql.Types.FLOAT);
                            stmt.setNull(11, java.sql.Types.FLOAT);
                        }
                        
                        stmt.setString(12, state.name());
                        stmt.executeUpdate();
                    }

                    // Post-INSERT safety: se o jogador foi revivido enquanto o INSERT estava em voo,
                    // deleta imediatamente o registro que acabamos de criar.
                    if (recentlyRevived.contains(playerId) || !deadPlayers.contains(playerId)) {
                        plugin.getLogger().info("[saveDeathInfo] Jogador " + playerName + " foi revivido durante INSERT assíncrono. Limpando registro.");
                        try (java.sql.PreparedStatement del = conn.prepareStatement(
                                "DELETE FROM midgard_deaths WHERE player_uuid = ?")) {
                            del.setString(1, playerId.toString());
                            del.executeUpdate();
                        }
                    }
                } catch (java.sql.SQLException e) {
                    plugin.getLogger().severe("Erro ao salvar informações de morte (DB): " + e.getMessage());
                }
            });
        }
    }

    /**
     * Deleta registro de morte do banco de dados com retry.
     * Tenta até maxRetries vezes para garantir que o registro é removido.
     * @return true se o DELETE foi executado com sucesso (mesmo que 0 rows afetadas)
     */
    private boolean deleteFromDbWithRetry(UUID playerId, int maxRetries) {
        if (databaseManager == null) {
            plugin.getLogger().severe("[deleteFromDb] DatabaseManager é null! Não é possível deletar registro de " + playerId);
            return false;
        }
        String uuid = playerId.toString();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (java.sql.Connection conn = databaseManager.getConnection()) {
                if (conn == null) {
                    plugin.getLogger().warning("[deleteFromDb] Tentativa " + attempt + "/" + maxRetries + ": conexão null para " + uuid);
                    if (attempt < maxRetries) {
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    }
                    continue;
                }
                try (java.sql.PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM midgard_deaths WHERE player_uuid = ?")) {
                    stmt.setString(1, uuid);
                    int rows = stmt.executeUpdate();
                    if (rows > 0) {
                        plugin.getLogger().info("[deleteFromDb] Registro deletado do DB para " + uuid + " (" + rows + " rows, tentativa " + attempt + ")");
                    } else {
                        plugin.getLogger().info("[deleteFromDb] Nenhum registro no DB para " + uuid + " (já deletado, tentativa " + attempt + ")");
                    }
                    return true;
                }
            } catch (java.sql.SQLException e) {
                plugin.getLogger().warning("[deleteFromDb] Tentativa " + attempt + "/" + maxRetries + " falhou para " + uuid + ": " + e.getMessage());
                if (attempt < maxRetries) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
            }
        }
        return false;
    }

    public void removeDeathInfo(UUID playerId) {
        // Remove from DB (async para não bloquear a main thread)
        if (databaseManager != null) {
            final UUID pid = playerId;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                deleteFromDbWithRetry(pid, 2);
            });
        }
    }

    /**
     * Remove registro de morte do banco de dados de forma SÍNCRONA.
     * Usado pelo revive() para garantir que o DELETE complete antes de qualquer reload.
     */
    public void removeDeathInfoSync(UUID playerId) {
        deleteFromDbWithRetry(playerId, 3);
    }

    public void removeDeadPlayer(UUID playerId) {
        recentlyRevived.add(playerId);
        recentlyRevivedTime.put(playerId, System.currentTimeMillis());
        deadPlayers.remove(playerId);
        deathInfoMap.remove(playerId);
        playerStates.remove(playerId);
        
        boolean deleted = deleteFromDbWithRetry(playerId, 3);
        if (!deleted) {
            plugin.getLogger().severe("[removeDeadPlayer] FALHA CRITICA: Não foi possível deletar registro de " + playerId + " do banco!");
        }
    }

    /**
     * Executa sincronamente todos os DELETEs pendentes para jogadores revividos.
     * DEVE ser chamado no onDisable ANTES de cancelTasks() e disconnect().
     */
    public void flushPendingDeletes() {
        if (databaseManager == null || recentlyRevived.isEmpty()) return;
        plugin.getLogger().info("[Shutdown] Limpando " + recentlyRevived.size() + " registros de jogadores revividos...");
        for (UUID uuid : recentlyRevived) {
            deleteFromDbWithRetry(uuid, 2);
        }
    }

    // saveAll() e saveAllSync() removidos: dados são persistidos imediatamente
    // via markDead() → saveDeathInfo() (sync) e setDeathState() (async UPDATE)
}
