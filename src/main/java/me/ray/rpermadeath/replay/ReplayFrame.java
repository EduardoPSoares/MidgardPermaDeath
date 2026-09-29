package me.ray.rpermadeath.replay;

import me.ray.rpermadeath.replay.events.DamageReplayEvent;
import me.ray.rpermadeath.replay.events.ParticleEvent;
import me.ray.rpermadeath.replay.events.ReplayEvent;
import me.ray.rpermadeath.replay.events.SkillCastEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Representa um frame do replay, capturando o estado de todos os jogadores em um momento específico
 */
public class ReplayFrame {
    private final long timestamp;
    private final Map<UUID, PlayerSnapshot> playerSnapshots;
    private final List<ReplayEvent> events;
    // Audio data: UUID (string) -> lista de pacotes Opus codificados em Base64
    private Map<String, List<String>> ad;

    
    public ReplayFrame(long timestamp) {
        this.timestamp = timestamp;
        this.playerSnapshots = new HashMap<>();
        this.events = new ArrayList<>();
        this.ad = null; // Lazy init para economizar memória quando não há áudio
    }
    

    
    public void addEvent(ReplayEvent event) {
        events.add(event);
    }
    
    public List<ReplayEvent> getEvents() {
        return events;
    }
    
    public void addPlayerSnapshot(PlayerSnapshot snapshot) {
        playerSnapshots.put(snapshot.getUuid(), snapshot);
    }

    public void addPlayerSnapshot(Player player, boolean hurt, boolean swinging) {
        String texture = null;
        String signature = null;
        try {
            com.mojang.authlib.GameProfile profile = ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle().getGameProfile();
            if (profile.getProperties().containsKey("textures")) {
                com.mojang.authlib.properties.Property property = profile.getProperties().get("textures").iterator().next();
                texture = property.value();
                signature = property.signature();
            }
        } catch (Exception ignored) {}

        PlayerSnapshot snapshot = new PlayerSnapshot(
            player.getUniqueId(),
            player.getName(),
            player.getLocation().clone(),
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
            texture,
            signature
        );
        playerSnapshots.put(player.getUniqueId(), snapshot);
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public Map<UUID, PlayerSnapshot> getPlayerSnapshots() {
        return playerSnapshots;
    }

    /**
     * Adiciona dados de áudio capturados ao frame.
     * @param audioBuffer mapa de UUID do jogador para lista de pacotes Opus (byte[])
     */
    public void addAudioData(Map<UUID, List<byte[]>> audioBuffer) {
        if (audioBuffer == null || audioBuffer.isEmpty()) return;
        if (this.ad == null) this.ad = new HashMap<>();
        for (Map.Entry<UUID, List<byte[]>> entry : audioBuffer.entrySet()) {
            List<String> encoded = new ArrayList<>(entry.getValue().size());
            for (byte[] packet : entry.getValue()) {
                encoded.add(Base64.getEncoder().encodeToString(packet));
            }
            this.ad.put(entry.getKey().toString(), encoded);
        }
    }

    /**
     * Retorna dados de áudio decodificados do frame.
     * @return mapa de UUID do jogador para lista de pacotes Opus (byte[])
     */
    public Map<UUID, List<byte[]>> getDecodedAudioData() {
        if (ad == null || ad.isEmpty()) return Collections.emptyMap();
        Map<UUID, List<byte[]>> result = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : ad.entrySet()) {
            try {
                UUID uuid = UUID.fromString(entry.getKey());
                List<byte[]> decoded = new ArrayList<>(entry.getValue().size());
                for (String s : entry.getValue()) {
                    decoded.add(Base64.getDecoder().decode(s));
                }
                result.put(uuid, decoded);
            } catch (Exception ignored) {}
        }
        return result;
    }

    public boolean hasAudioData() {
        return ad != null && !ad.isEmpty();
    }

    /**
     * Verifica se um jogador específico tem dados de áudio neste frame.
     */
    public boolean hasAudioDataFor(UUID playerUuid) {
        if (ad == null) return false;
        List<String> data = ad.get(playerUuid.toString());
        return data != null && !data.isEmpty();
    }
    
    public ReplayFrame filter(Location center, double radius) {
        ReplayFrame newFrame = new ReplayFrame(this.timestamp);
        if (center.getWorld() == null) return newFrame;
        double radiusSq = radius * radius;
        String worldName = center.getWorld().getName();
        double cx = center.getX();
        double cy = center.getY();
        double cz = center.getZ();

        for (PlayerSnapshot snapshot : playerSnapshots.values()) {
            if (!snapshot.worldName.equals(worldName)) continue;
            
            double dx = snapshot.x - cx;
            double dy = snapshot.y - cy;
            double dz = snapshot.z - cz;
            
            if (dx*dx + dy*dy + dz*dz <= radiusSq) {
                newFrame.addPlayerSnapshot(snapshot);
            }
        }
        
        // Filtra eventos: só inclui eventos relevantes aos jogadores do frame ou dentro do raio
        Set<String> playerNames = new HashSet<>();
        for (PlayerSnapshot snap : newFrame.getPlayerSnapshots().values()) {
            playerNames.add(snap.getName());
        }

        for (ReplayEvent event : events) {
            if (event instanceof SkillCastEvent) {
                SkillCastEvent skill = (SkillCastEvent) event;
                if (playerNames.contains(skill.getPlayerName())) {
                    newFrame.addEvent(event);
                }
            } else if (event instanceof DamageReplayEvent) {
                DamageReplayEvent dmg = (DamageReplayEvent) event;
                if (playerNames.contains(dmg.getVictimName()) || playerNames.contains(dmg.getDamagerName())) {
                    newFrame.addEvent(event);
                }
            } else if (event instanceof ParticleEvent) {
                ParticleEvent particle = (ParticleEvent) event;
                double pdx = particle.getX() - cx;
                double pdy = particle.getY() - cy;
                double pdz = particle.getZ() - cz;
                if (pdx*pdx + pdy*pdy + pdz*pdz <= radiusSq) {
                    newFrame.addEvent(event);
                }
            } else {
                newFrame.addEvent(event);
            }
        }

        // Copia dados de áudio dos jogadores que estão no frame filtrado
        if (ad != null && !ad.isEmpty()) {
            for (Map.Entry<UUID, PlayerSnapshot> snap : newFrame.getPlayerSnapshots().entrySet()) {
                String uuidStr = snap.getKey().toString();
                List<String> audioPackets = ad.get(uuidStr);
                if (audioPackets != null && !audioPackets.isEmpty()) {
                    if (newFrame.ad == null) newFrame.ad = new HashMap<>();
                    newFrame.ad.put(uuidStr, audioPackets);
                }
            }
        }
        
        return newFrame;
    }

    public static class PlayerSnapshot {
        private final UUID uuid;
        private final String name;
        
        // Armazena primitivos para economizar memória e evitar referências a World
        private final double x, y, z;
        private final float yaw, pitch;
        private final String worldName;
        
        private final double health;
        private final int foodLevel;
        private final ItemStack mainHand;
        private final ItemStack offHand;
        private final ItemStack[] armor;
        private final boolean sneaking;
        private final boolean sprinting;
        private final boolean blocking;
        private final java.util.Collection<org.bukkit.potion.PotionEffect> potionEffects;
        private final boolean hurt;
        private final boolean swinging;
        private final Pose pose;
        private final String skinTexture;
        private final String skinSignature;
        
        public boolean matches(Location loc, double health, int foodLevel, ItemStack mainHand, ItemStack offHand, ItemStack[] armor,
                             boolean sneaking, boolean sprinting, boolean blocking, boolean hurt, boolean swinging, Pose pose) {
            // Verifica mundo
            if (loc.getWorld() == null || !this.worldName.equals(loc.getWorld().getName())) return false;
            
            // Verifica posição (com pequena tolerância para evitar jitter de ponto flutuante)
            if (Math.abs(this.x - loc.getX()) > 0.0001) return false;
            if (Math.abs(this.y - loc.getY()) > 0.0001) return false;
            if (Math.abs(this.z - loc.getZ()) > 0.0001) return false;
            if (Math.abs(this.yaw - loc.getYaw()) > 0.0001) return false;
            if (Math.abs(this.pitch - loc.getPitch()) > 0.0001) return false;
            
            // Verifica status
            if (Math.abs(this.health - health) > 0.0001) return false;
            if (this.foodLevel != foodLevel) return false;
            
            // Verifica flags
            if (this.sneaking != sneaking) return false;
            if (this.sprinting != sprinting) return false;
            if (this.blocking != blocking) return false;
            if (this.hurt != hurt) return false;
            if (this.swinging != swinging) return false;
            if (this.pose != pose) return false;
            
            // Verifica itens (referência, pois usamos cache)
            if (this.mainHand != mainHand) return false;
            if (this.offHand != offHand) return false;
            if (this.armor != armor) return false; // Array reference check is enough due to cache logic
            
            // Nota: Ignoramos PotionEffects na comparação frame-a-frame para performance,
            // assumindo que se nada mais mudou, os efeitos provavelmente são os mesmos ou a mudança visual é mínima.
            // Se for crítico, podemos comparar o tamanho da coleção ou hash.
            
            return true;
        }

        public PlayerSnapshot(UUID uuid, String name, Location location, double health, 
                            int foodLevel, ItemStack mainHand, ItemStack offHand, ItemStack[] armor,
                            boolean sneaking, boolean sprinting, boolean blocking,
                            java.util.Collection<org.bukkit.potion.PotionEffect> potionEffects,
                            boolean hurt, boolean swinging, Pose pose, String skinTexture, String skinSignature) {
            this.uuid = uuid;
            this.name = name;
            
            // Extrai valores
            this.x = location.getX();
            this.y = location.getY();
            this.z = location.getZ();
            this.yaw = location.getYaw();
            this.pitch = location.getPitch();
            this.worldName = location.getWorld() != null ? location.getWorld().getName() : "world";
            
            this.health = health;
            this.foodLevel = foodLevel;
            this.mainHand = mainHand;
            this.offHand = offHand;
            this.armor = armor;
            this.sneaking = sneaking;
            this.sprinting = sprinting;
            this.blocking = blocking;
            this.potionEffects = potionEffects;
            this.hurt = hurt;
            this.swinging = swinging;
            this.pose = pose;
            this.skinTexture = skinTexture;
            this.skinSignature = skinSignature;
        }
        
        // Construtor para deserialização (usado pelo Adapter)
        public PlayerSnapshot(UUID uuid, String name, double x, double y, double z, float yaw, float pitch, String worldName,
                            double health, int foodLevel, ItemStack mainHand, ItemStack offHand, ItemStack[] armor,
                            boolean sneaking, boolean sprinting, boolean blocking,
                            java.util.Collection<org.bukkit.potion.PotionEffect> potionEffects,
                            boolean hurt, boolean swinging, Pose pose, String skinTexture, String skinSignature) {
            this.uuid = uuid;
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.worldName = worldName;
            this.health = health;
            this.foodLevel = foodLevel;
            this.mainHand = mainHand;
            this.offHand = offHand;
            this.armor = armor;
            this.sneaking = sneaking;
            this.sprinting = sprinting;
            this.blocking = blocking;
            this.potionEffects = potionEffects;
            this.hurt = hurt;
            this.swinging = swinging;
            this.pose = pose;
            this.skinTexture = skinTexture;
            this.skinSignature = skinSignature;
        }
        
        public UUID getUuid() { return uuid; }
        public String getName() { return name; }
        
        public Location getLocation() { 
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
            return new Location(world, x, y, z, yaw, pitch);
        }
        
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
        public float getYaw() { return yaw; }
        public float getPitch() { return pitch; }
        public String getWorldName() { return worldName; }

        public double getHealth() { return health; }
        public int getFoodLevel() { return foodLevel; }
        public ItemStack getMainHand() { return mainHand; }
        public ItemStack getOffHand() { return offHand; }
        public ItemStack[] getArmor() { return armor; }
        public boolean isSneaking() { return sneaking; }
        public boolean isSprinting() { return sprinting; }
        public boolean isBlocking() { return blocking; }
        public java.util.Collection<org.bukkit.potion.PotionEffect> getPotionEffects() { 
            return potionEffects; 
        }
        public boolean isHurt() { return hurt; }
        public boolean isSwinging() { return swinging; }
        public Pose getPose() { return pose; }
        public String getSkinTexture() { return skinTexture; }
        public String getSkinSignature() { return skinSignature; }
    }

}
