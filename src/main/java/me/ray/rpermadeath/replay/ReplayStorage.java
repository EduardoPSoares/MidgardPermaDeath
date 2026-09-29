package me.ray.rpermadeath.replay;

import com.google.gson.*;
import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.database.DatabaseManager;
import me.ray.rpermadeath.replay.events.ParticleEvent;
import me.ray.rpermadeath.replay.events.ReplayEvent;
import me.ray.rpermadeath.replay.events.SkillCastEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Pose;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ReplayStorage {
    private final RPermadeath plugin;
    private final Gson gson;
    private final File replaysFolder;
    private final DatabaseManager databaseManager;
    private static final int CACHE_MAX_SIZE = 5;
    private final java.util.concurrent.ConcurrentHashMap<Integer, ReplayRecording> replayCache = new java.util.concurrent.ConcurrentHashMap<>();

    public ReplayStorage(RPermadeath plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.replaysFolder = new File(plugin.getDataFolder(), "replays");
        if (!replaysFolder.exists()) {
            replaysFolder.mkdirs();
        }
        
        this.gson = new GsonBuilder()
                .registerTypeAdapter(Location.class, new LocationAdapter())
                .registerTypeHierarchyAdapter(ItemStack.class, new ItemStackAdapter())
                .registerTypeAdapter(PotionEffect.class, new PotionEffectAdapter())
                .registerTypeAdapter(EntityType.class, new EntityTypeAdapter())
                .registerTypeAdapter(Sound.class, new SoundAdapter())
                .registerTypeAdapter(ReplayFrame.PlayerSnapshot.class, new PlayerSnapshotAdapter())
                .registerTypeAdapter(ReplayEvent.class, new ReplayEventAdapter())
                .create();
    }

    public void deleteReplays(UUID playerId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteReplaysSync(playerId));
    }

    /**
     * Deleta replays de forma síncrona (deve ser chamado de thread async)
     */
    private void deleteReplaysSync(UUID playerId) {
        // Invalida cache
        replayCache.clear();
        // 1. Busca filenames no DB antes de deletar
        java.util.List<String> filenames = new ArrayList<>();
        if (databaseManager != null) {
            String selectSql = "SELECT filename FROM midgard_replays WHERE player_uuid = ?";
            String deleteSql = "DELETE FROM midgard_replays WHERE player_uuid = ?";
            try (Connection conn = databaseManager.getConnection()) {
                if (conn != null) {
                    try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                        selectStmt.setString(1, playerId.toString());
                        try (ResultSet rs = selectStmt.executeQuery()) {
                            while (rs.next()) {
                                filenames.add(rs.getString("filename"));
                            }
                        }
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.SEVERE, "Erro ao listar replays para deletar", e);
                    }
                    try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                        deleteStmt.setString(1, playerId.toString());
                        deleteStmt.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.SEVERE, "Erro ao deletar replays do banco de dados", e);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erro ao fechar conexão no deleteReplaysSync", e);
            }
        }
        // 2. Deleta arquivos locais
        int deleted = 0;
        for (String filename : filenames) {
            File file = new File(replaysFolder, filename);
            if (file.exists() && file.delete()) {
                deleted++;
            }
        }
        // Também deleta arquivos órfãos que começam com o UUID do jogador
        File[] orphans = replaysFolder.listFiles((dir, name) ->
            name.startsWith(playerId.toString()) && name.endsWith(".replay.gz"));
        if (orphans != null) {
            for (File f : orphans) {
                if (f.delete()) deleted++;
            }
        }
        if (deleted > 0) {
            plugin.getLogger().info("[Replay] Deletados " + deleted + " replays antigos para " + playerId);
        }
    }

    public void cleanupOldReplays(int days) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long retentionTime = days * 24L * 60L * 60L * 1000L;
            long threshold = System.currentTimeMillis() - retentionTime;
            
            // 1. Busca filenames antigos no DB
            java.util.List<String> oldFiles = new ArrayList<>();
            if (databaseManager != null) {
                String selectSql = "SELECT filename FROM midgard_replays WHERE death_time < ?";
                String deleteSql = "DELETE FROM midgard_replays WHERE death_time < ?";
                try (Connection conn = databaseManager.getConnection()) {
                    if (conn != null) {
                        try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                            selectStmt.setLong(1, threshold);
                            try (ResultSet rs = selectStmt.executeQuery()) {
                                while (rs.next()) {
                                    oldFiles.add(rs.getString("filename"));
                                }
                            }
                        } catch (SQLException e) {
                            plugin.getLogger().log(Level.SEVERE, "Erro ao listar replays antigos", e);
                        }
                        try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                            deleteStmt.setLong(1, threshold);
                            deleteStmt.executeUpdate();
                        } catch (SQLException e) {
                            plugin.getLogger().log(Level.SEVERE, "Erro ao limpar replays antigos do banco de dados", e);
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Erro ao fechar conexão no cleanupOldReplays", e);
                }
            }
            // 2. Deleta arquivos locais correspondentes
            int deleted = 0;
            for (String filename : oldFiles) {
                File file = new File(replaysFolder, filename);
                if (file.exists() && file.delete()) {
                    deleted++;
                }
            }
            // 3. Limpa arquivos órfãos antigos
            File[] allFiles = replaysFolder.listFiles((dir, name) -> name.endsWith(".replay.gz"));
            if (allFiles != null) {
                long now = System.currentTimeMillis();
                for (File f : allFiles) {
                    if (now - f.lastModified() > retentionTime) {
                        if (f.delete()) deleted++;
                    }
                }
            }
            if (deleted > 0) {
                plugin.getLogger().info("[Replay] Limpeza: " + deleted + " replays antigos removidos.");
            }
        });
    }

    public void saveReplay(ReplayRecording recording) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // 0. Deleta replays antigos do mesmo jogador antes de salvar o novo
                deleteReplaysSync(recording.getDeathPlayerId());

                // 1. Gera nome do arquivo
                String filename = recording.getDeathPlayerId() + "_" + recording.getDeathTime() + ".replay.gz";
                File file = new File(replaysFolder, filename);
                
                // 2. Salva arquivo GZIP localmente
                try (FileOutputStream fos = new FileOutputStream(file);
                     GZIPOutputStream gzip = new GZIPOutputStream(fos);
                     OutputStreamWriter writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
                    gson.toJson(recording, writer);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "[Replay] Erro ao salvar arquivo de replay: " + filename, e);
                    return;
                }
                
                long fileSize = file.length();
                plugin.getLogger().info("[Replay] Arquivo salvo: " + filename + " (" + (fileSize / 1024) + "KB)");
                
                // 3. Salva metadados no banco de dados
                if (databaseManager != null) {
                    try {
                        String sql = "INSERT INTO midgard_replays (player_uuid, death_time, filename, file_size) VALUES (?, ?, ?, ?)";
                        try (Connection conn = databaseManager.getConnection()) {
                            if (conn != null) {
                                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                                    stmt.setString(1, recording.getDeathPlayerId().toString());
                                    stmt.setLong(2, recording.getDeathTime());
                                    stmt.setString(3, filename);
                                    stmt.setLong(4, fileSize);
                                    stmt.executeUpdate();
                                    plugin.getLogger().info("[Replay] Metadados salvos no banco de dados para " + recording.getDeathPlayerId());
                                }
                            } else {
                                plugin.getLogger().warning("[Replay] Conexão nula. Arquivo salvo localmente, mas metadados não registrados.");
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "[Replay] Falha ao salvar metadados no banco de dados (arquivo local salvo)", e);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Erro inesperado ao salvar replay", e);
            }
        });
    }

    public static class ReplayMetadata {
        private final int id;
        private final UUID playerId;
        private final long timestamp;

        public ReplayMetadata(int id, UUID playerId, long timestamp) {
            this.id = id;
            this.playerId = playerId;
            this.timestamp = timestamp;
        }
        
        public int getId() { return id; }
        public UUID getPlayerId() { return playerId; }
        public long getTimestamp() { return timestamp; }
    }

    public java.util.List<ReplayMetadata> listReplays(UUID playerId) {
        java.util.List<ReplayMetadata> list = new ArrayList<>();
        
        if (databaseManager == null) return list;
        
        String sql = "SELECT id, death_time, filename FROM midgard_replays WHERE player_uuid = ? ORDER BY death_time DESC";
        try (Connection conn = databaseManager.getConnection()) {
            if (conn != null) {
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerId.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String filename = rs.getString("filename");
                            File file = new File(replaysFolder, filename);
                            if (file.exists()) {
                                list.add(new ReplayMetadata(rs.getInt("id"), playerId, rs.getLong("death_time")));
                            }
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Erro ao listar replays do banco de dados para " + playerId, e);
                }
            } else {
                plugin.getLogger().warning("[Replay] Conexão nula ao listar replays de " + playerId);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Erro ao fechar conexão no listReplays", e);
        }
        
        return list;
    }

    public ReplayRecording loadReplay(int id) {
        // Verifica cache primeiro
        ReplayRecording cached = replayCache.get(id);
        if (cached != null) {
            plugin.getLogger().info("[Replay] Replay ID " + id + " carregado do cache.");
            return cached;
        }

        // Busca filename no banco de dados
        String filename = null;
        if (databaseManager != null) {
            String sql = "SELECT filename FROM midgard_replays WHERE id = ?";
            try (Connection conn = databaseManager.getConnection()) {
                if (conn == null) {
                    plugin.getLogger().severe("[Replay] Conexão nula ao carregar replay ID " + id);
                    return null;
                }
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, id);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            filename = rs.getString("filename");
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "[Replay] Erro ao buscar filename do replay ID " + id, e);
                    return null;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "[Replay] Erro ao fechar conexão no loadReplay", e);
                return null;
            }
        }
        
        if (filename == null || filename.isEmpty()) {
            plugin.getLogger().warning("[Replay] Replay ID " + id + " não encontrado ou sem arquivo associado no banco de dados.");
            return null;
        }
        
        ReplayRecording recording = loadReplayFile(new File(replaysFolder, filename));
        if (recording != null) {
            replayCache.put(id, recording);
            // Evicção simples: remove entradas aleatórias se o cache exceder o tamanho máximo
            while (replayCache.size() > CACHE_MAX_SIZE) {
                Integer firstKey = replayCache.keys().nextElement();
                replayCache.remove(firstKey);
            }
        }
        return recording;
    }
    
    private ReplayRecording loadReplayFile(File file) {
        if (!file.exists() || file.isDirectory()) {
            plugin.getLogger().warning("[Replay] Arquivo não encontrado ou inválido: " + file.getName());
            return null;
        }
        plugin.getLogger().info("[Replay] Carregando arquivo: " + file.getName() + " (" + (file.length() / 1024) + "KB)");
        try (FileInputStream fis = new FileInputStream(file);
             GZIPInputStream gzip = new GZIPInputStream(fis);
             InputStreamReader reader = new InputStreamReader(gzip, StandardCharsets.UTF_8)) {
            ReplayRecording recording = gson.fromJson(reader, ReplayRecording.class);
            if (recording != null) {
                plugin.getLogger().info("[Replay] Replay carregado com sucesso! (" + recording.getFrameCount() + " frames)");
            }
            return recording;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[Replay] Erro ao carregar arquivo: " + file.getName(), e);
            return null;
        }
    }

    public java.util.Set<UUID> getReplayIds() {
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        
        if (databaseManager == null) return ids;
        
        String sql = "SELECT DISTINCT player_uuid FROM midgard_replays";
        
        try (Connection conn = databaseManager.getConnection()) {
            if (conn != null) {
                try (PreparedStatement stmt = conn.prepareStatement(sql);
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        try {
                            ids.add(UUID.fromString(rs.getString("player_uuid")));
                        } catch (Exception ignored) {}
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Erro ao listar IDs de replays do banco de dados", e);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Erro ao fechar conexão no getReplayIds", e);
        }

        return ids;
    }

    private static class SoundAdapter implements JsonSerializer<Sound>, JsonDeserializer<Sound> {
        @Override
        public JsonElement serialize(Sound src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public Sound deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            String s = json.getAsString();
            try {
                // Tenta pela chave (registry)
                NamespacedKey key = NamespacedKey.fromString(s.toLowerCase());
                if (key != null) {
                    Sound sound = Registry.SOUNDS.get(key);
                    if (sound != null) return sound;
                }
            } catch (Exception ignored) {}
            
            try {
                // Tenta pelo nome (enum legacy)
                @SuppressWarnings("deprecation")
                Sound sound = Sound.valueOf(s);
                return sound;
            } catch (Exception e) {
                // Fallback seguro
                return Sound.UI_BUTTON_CLICK;
            }
        }
    }

    private static class LocationAdapter implements JsonSerializer<Location>, JsonDeserializer<Location> {
        @Override
        public JsonElement serialize(Location src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            if (src.getWorld() != null) {
                obj.addProperty("world", src.getWorld().getName());
            }
            obj.addProperty("x", src.getX());
            obj.addProperty("y", src.getY());
            obj.addProperty("z", src.getZ());
            obj.addProperty("yaw", src.getYaw());
            obj.addProperty("pitch", src.getPitch());
            return obj;
        }

        @Override
        public Location deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            String worldName = obj.has("world") ? obj.get("world").getAsString() : "world";
            World world = Bukkit.getWorld(worldName);
            return new Location(
                world,
                obj.get("x").getAsDouble(),
                obj.get("y").getAsDouble(),
                obj.get("z").getAsDouble(),
                obj.get("yaw").getAsFloat(),
                obj.get("pitch").getAsFloat()
            );
        }
    }
    
    private static class ItemStackAdapter implements JsonSerializer<ItemStack>, JsonDeserializer<ItemStack> {
        @Override
        public JsonElement serialize(ItemStack src, Type typeOfSrc, JsonSerializationContext context) {
            if (src == null || src.getType() == org.bukkit.Material.AIR) {
                return JsonNull.INSTANCE;
            }
            
            // Se tiver meta (MMOItems, itens customizados, nomes, lore), usa serialização completa em Base64
            // Isso garante que CustomModelData e NBT sejam preservados para o replay visual
            if (src.hasItemMeta()) {
                try {
                    return new JsonPrimitive(Base64.getEncoder().encodeToString(src.serializeAsBytes()));
                } catch (Exception e) {
                    e.printStackTrace();
                    // Fallback para serialização simples se falhar
                }
            }
            
            // Serialização simplificada para itens vanilla sem meta (economiza muito espaço)
            JsonObject obj = new JsonObject();
            obj.addProperty("t", src.getType().name()); // Short key
            if (src.getAmount() > 1) {
                obj.addProperty("a", src.getAmount()); // Short key
            }
            
            return obj;
        }

        @Override
        public ItemStack deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json.isJsonPrimitive()) {
                // Suporte a Base64 (Formato completo/Legado)
                try {
                    return ItemStack.deserializeBytes(Base64.getDecoder().decode(json.getAsString()));
                } catch (Exception e) {
                    return null;
                }
            } else if (json.isJsonObject()) {
                // Novo formato simplificado (Apenas para itens sem meta)
                JsonObject obj = json.getAsJsonObject();
                try {
                    String typeName = obj.has("t") ? obj.get("t").getAsString() : (obj.has("type") ? obj.get("type").getAsString() : "AIR");
                    org.bukkit.Material mat = org.bukkit.Material.valueOf(typeName);
                    
                    int amount = obj.has("a") ? obj.get("a").getAsInt() : (obj.has("amount") ? obj.get("amount").getAsInt() : 1);
                    return new ItemStack(mat, amount);
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }
    }

    private static class PotionEffectAdapter implements JsonSerializer<PotionEffect>, JsonDeserializer<PotionEffect> {
        @Override
        public JsonElement serialize(PotionEffect src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", src.getType().getKey().toString());
            obj.addProperty("duration", src.getDuration());
            obj.addProperty("amplifier", src.getAmplifier());
            obj.addProperty("ambient", src.isAmbient());
            obj.addProperty("particles", src.hasParticles());
            obj.addProperty("icon", src.hasIcon());
            return obj;
        }

        @Override
        public PotionEffect deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            PotionEffectType type = Registry.POTION_EFFECT_TYPE.get(NamespacedKey.fromString(obj.get("type").getAsString()));
            if (type == null) return null;
            
            int duration = obj.get("duration").getAsInt();
            int amplifier = obj.get("amplifier").getAsInt();
            boolean ambient = obj.get("ambient").getAsBoolean();
            boolean particles = obj.get("particles").getAsBoolean();
            boolean icon = obj.get("icon").getAsBoolean();
            return new PotionEffect(type, duration, amplifier, ambient, particles, icon);
        }
    }

    private static class EntityTypeAdapter implements JsonSerializer<EntityType>, JsonDeserializer<EntityType> {
        @Override
        public JsonElement serialize(EntityType src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.name());
        }

        @Override
        public EntityType deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            try {
                return EntityType.valueOf(json.getAsString());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private static class PlayerSnapshotAdapter implements JsonSerializer<ReplayFrame.PlayerSnapshot>, JsonDeserializer<ReplayFrame.PlayerSnapshot> {
        @Override
        public JsonElement serialize(ReplayFrame.PlayerSnapshot src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            // UUID e Name são necessários para reconstrução correta
            obj.addProperty("id", src.getUuid().toString()); // Short key
            obj.addProperty("n", src.getName()); // Short key
            
            // Arredonda coordenadas para 3 casas decimais para maior precisão
            obj.addProperty("x", Math.round(src.getX() * 1000.0) / 1000.0);
            obj.addProperty("y", Math.round(src.getY() * 1000.0) / 1000.0);
            obj.addProperty("z", Math.round(src.getZ() * 1000.0) / 1000.0);
            obj.addProperty("yaw", Math.round(src.getYaw() * 1000.0) / 1000.0);
            obj.addProperty("pitch", Math.round(src.getPitch() * 1000.0) / 1000.0);
            obj.addProperty("w", src.getWorldName()); // Short key
            
            obj.addProperty("h", Math.round(src.getHealth() * 10.0) / 10.0); // Short key
            // obj.addProperty("foodLevel", src.getFoodLevel()); // Removido (não essencial para replay visual)
            
            if (src.getMainHand() != null && src.getMainHand().getType() != org.bukkit.Material.AIR) {
                obj.add("mh", context.serialize(src.getMainHand())); // Short key
            }
            if (src.getOffHand() != null && src.getOffHand().getType() != org.bukkit.Material.AIR) {
                obj.add("oh", context.serialize(src.getOffHand())); // Short key
            }
            
            // Otimiza array de armadura (remove nulos/air)
            boolean hasArmor = false;
            for (ItemStack i : src.getArmor()) {
                if (i != null && i.getType() != org.bukkit.Material.AIR) {
                    hasArmor = true;
                    break;
                }
            }
            if (hasArmor) {
                obj.add("am", context.serialize(src.getArmor())); // Short key
            }
            
            if (src.isSneaking()) obj.addProperty("sn", true); // Short key
            if (src.isSprinting()) obj.addProperty("sp", true); // Short key
            if (src.isBlocking()) obj.addProperty("bl", true); // Short key
            
            if (src.getPotionEffects() != null && !src.getPotionEffects().isEmpty()) {
                obj.add("pe", context.serialize(src.getPotionEffects())); // Short key
            }
            
            if (src.isHurt()) obj.addProperty("ht", true); // Short key
            if (src.isSwinging()) obj.addProperty("sw", true); // Short key
            if (src.getPose() != Pose.STANDING) obj.addProperty("ps", src.getPose().name()); // Short key
            
            if (src.getSkinTexture() != null) obj.addProperty("st", src.getSkinTexture());
            if (src.getSkinSignature() != null) obj.addProperty("ss", src.getSkinSignature());
            
            return obj;
        }

        @Override
        public ReplayFrame.PlayerSnapshot deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            
            // Tenta obter UUID/Name do contexto ou usa valores dummy se não disponíveis
            // Nota: O Gson padrão não passa a chave do mapa para o deserializador do valor.
            // Isso significa que precisamos de uma estratégia diferente se quisermos remover UUID/Name do JSON.
            // Por enquanto, vamos manter UUID/Name no JSON para garantir que funcione, mas com chaves curtas se possível.
            // Mas espere, o código anterior removia UUID/Name. Se removermos, como recuperamos?
            // A estrutura do ReplayFrame é Map<UUID, PlayerSnapshot>.
            // O Gson serializa o Map como um objeto JSON onde as chaves são UUIDs.
            // Ao deserializar, o Gson lê a chave (UUID) e chama deserialize() para o valor (PlayerSnapshot).
            // Mas o deserialize() não recebe a chave.
            // SOLUÇÃO: O PlayerSnapshot precisa ter o UUID. Se não estiver no JSON, não conseguimos instanciar o objeto corretamente
            // a menos que mudemos a estrutura para não usar PlayerSnapshot como valor direto, ou usemos um TypeAdapterFactory.
            
            // Para simplificar e manter compatibilidade com o código existente que espera UUID no objeto:
            // Vamos tentar ler "uuid" ou "id". Se não existir, teremos um problema.
            // Mas espere! Se o ReplayFrame usa Map<UUID, PlayerSnapshot>, o UUID está na chave do Map.
            // O PlayerSnapshot é o valor.
            // Se o PlayerSnapshot precisa armazenar o UUID internamente (campo 'uuid'), ele precisa vir do JSON.
            
            // Vamos reverter a remoção do UUID/Name por segurança, mas usar chaves curtas se possível?
            // Não, UUID é essencial. Vamos manter.
            
            UUID uuid;
            if (obj.has("uuid")) uuid = context.deserialize(obj.get("uuid"), UUID.class);
            else if (obj.has("id")) uuid = context.deserialize(obj.get("id"), UUID.class);
            else uuid = UUID.randomUUID(); // Fallback perigoso
            
            String name = obj.has("name") ? obj.get("name").getAsString() : (obj.has("n") ? obj.get("n").getAsString() : "Unknown");
            
            double x, y, z;
            float yaw, pitch;
            String worldName;
            
            if (obj.has("location")) {
                // Old format
                JsonObject locObj = obj.getAsJsonObject("location");
                worldName = locObj.has("world") ? locObj.get("world").getAsString() : "world";
                x = locObj.get("x").getAsDouble();
                y = locObj.get("y").getAsDouble();
                z = locObj.get("z").getAsDouble();
                yaw = locObj.get("yaw").getAsFloat();
                pitch = locObj.get("pitch").getAsFloat();
            } else {
                // New format (Short keys)
                x = obj.has("x") ? obj.get("x").getAsDouble() : 0;
                y = obj.has("y") ? obj.get("y").getAsDouble() : 0;
                z = obj.has("z") ? obj.get("z").getAsDouble() : 0;
                yaw = obj.has("yaw") ? obj.get("yaw").getAsFloat() : 0;
                pitch = obj.has("pitch") ? obj.get("pitch").getAsFloat() : 0;
                worldName = obj.has("w") ? obj.get("w").getAsString() : (obj.has("worldName") ? obj.get("worldName").getAsString() : "world");
            }
            
            double health = obj.has("h") ? obj.get("h").getAsDouble() : (obj.has("health") ? obj.get("health").getAsDouble() : 20);
            int foodLevel = obj.has("f") ? obj.get("f").getAsInt() : (obj.has("foodLevel") ? obj.get("foodLevel").getAsInt() : 20);
            
            ItemStack mainHand = obj.has("mh") ? context.deserialize(obj.get("mh"), ItemStack.class) : (obj.has("mainHand") ? context.deserialize(obj.get("mainHand"), ItemStack.class) : null);
            ItemStack offHand = obj.has("oh") ? context.deserialize(obj.get("oh"), ItemStack.class) : (obj.has("offHand") ? context.deserialize(obj.get("offHand"), ItemStack.class) : null);
            ItemStack[] armor = obj.has("am") ? context.deserialize(obj.get("am"), ItemStack[].class) : (obj.has("armor") ? context.deserialize(obj.get("armor"), ItemStack[].class) : null);
            
            boolean sneaking = obj.has("sn") ? obj.get("sn").getAsBoolean() : (obj.has("sneaking") ? obj.get("sneaking").getAsBoolean() : false);
            boolean sprinting = obj.has("sp") ? obj.get("sp").getAsBoolean() : (obj.has("sprinting") ? obj.get("sprinting").getAsBoolean() : false);
            boolean blocking = obj.has("bl") ? obj.get("bl").getAsBoolean() : (obj.has("blocking") ? obj.get("blocking").getAsBoolean() : false);
            
            Collection<PotionEffect> potionEffects = new ArrayList<>();
            if (obj.has("pe")) {
                 JsonArray effects = obj.getAsJsonArray("pe");
                 for (JsonElement e : effects) potionEffects.add(context.deserialize(e, PotionEffect.class));
            } else if (obj.has("potionEffects")) {
                 JsonArray effects = obj.getAsJsonArray("potionEffects");
                 for (JsonElement e : effects) potionEffects.add(context.deserialize(e, PotionEffect.class));
            }
            
            boolean hurt = obj.has("ht") ? obj.get("ht").getAsBoolean() : (obj.has("hurt") && obj.get("hurt").getAsBoolean());
            boolean swinging = obj.has("sw") ? obj.get("sw").getAsBoolean() : (obj.has("swinging") && obj.get("swinging").getAsBoolean());
            
            Pose pose = Pose.STANDING;
            if (obj.has("ps")) {
                try { pose = Pose.valueOf(obj.get("ps").getAsString()); } catch (Exception ignored) {}
            } else if (obj.has("pose")) {
                try { pose = Pose.valueOf(obj.get("pose").getAsString()); } catch (Exception ignored) {}
            }
            
            String skinTexture = obj.has("st") ? obj.get("st").getAsString() : (obj.has("skinTexture") ? obj.get("skinTexture").getAsString() : "");
            String skinSignature = obj.has("ss") ? obj.get("ss").getAsString() : (obj.has("skinSignature") ? obj.get("skinSignature").getAsString() : "");

            return new ReplayFrame.PlayerSnapshot(
                uuid, name, x, y, z, yaw, pitch, worldName,
                health, foodLevel, mainHand, offHand, armor,
                sneaking, sprinting, blocking, potionEffects,
                hurt, swinging, pose,
                skinTexture, skinSignature
            );
        }
    }

    private static class ReplayEventAdapter implements JsonSerializer<ReplayEvent>, JsonDeserializer<ReplayEvent> {
        @Override
        public JsonElement serialize(ReplayEvent src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = context.serialize(src).getAsJsonObject();
            obj.addProperty("eventType", src.getType());
            return obj;
        }

        @Override
        public ReplayEvent deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            String type = obj.get("eventType").getAsString();
            
            switch (type) {
                case "PARTICLE":
                    return context.deserialize(json, ParticleEvent.class);
                case "SKILL":
                    return context.deserialize(json, SkillCastEvent.class);
                case "DAMAGE":
                    return context.deserialize(json, me.ray.rpermadeath.replay.events.DamageReplayEvent.class);
                default:
                    return null;
            }
        }
    }
}
