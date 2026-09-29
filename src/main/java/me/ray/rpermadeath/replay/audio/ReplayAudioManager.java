package me.ray.rpermadeath.replay.audio;

import me.ray.rpermadeath.RPermadeath;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gerencia o buffer de áudio capturado do Simple Voice Chat.
 * Os pacotes Opus são armazenados temporariamente entre capturas de frames visuais
 * e depois anexados ao ReplayFrame correspondente.
 */
public class ReplayAudioManager {
    private final RPermadeath plugin;
    private final Map<UUID, List<byte[]>> currentBuffer;
    private volatile boolean enabled;

    public ReplayAudioManager(RPermadeath plugin) {
        this.plugin = plugin;
        this.currentBuffer = new ConcurrentHashMap<>();
        this.enabled = plugin.getConfig().getBoolean("replay.voice-chat.enabled", true);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private long captureCount = 0;

    /**
     * Chamado pelo VoicechatPlugin quando um pacote de microfone é recebido.
     */
    public void addAudioPacket(UUID playerUuid, byte[] opusData) {
        if (!enabled) return;
        currentBuffer.computeIfAbsent(playerUuid, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(opusData);
        if (++captureCount == 1) {
            plugin.getLogger().info("[Replay Audio] Primeiro pacote de áudio capturado de " + playerUuid);
        }
    }

    /**
     * Chamado pelo ReplayManager ao criar um novo frame visual.
     * Retorna todos os pacotes de áudio buffered desde a última chamada e limpa o buffer.
     */
    public Map<UUID, List<byte[]>> drainAudioBuffer() {
        if (currentBuffer.isEmpty()) return Collections.emptyMap();

        Map<UUID, List<byte[]>> result = new HashMap<>();
        Iterator<Map.Entry<UUID, List<byte[]>>> it = currentBuffer.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<byte[]>> entry = it.next();
            List<byte[]> packets = entry.getValue();
            synchronized (packets) {
                if (!packets.isEmpty()) {
                    result.put(entry.getKey(), new ArrayList<>(packets));
                    packets.clear();
                } else {
                    // Remove entradas vazias para evitar leak de memória
                    it.remove();
                }
            }
        }
        return result;
    }

    public void clearBuffer() {
        currentBuffer.clear();
    }

    public void clearPlayer(UUID playerUuid) {
        currentBuffer.remove(playerUuid);
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("replay.voice-chat.enabled", true);
    }
}
