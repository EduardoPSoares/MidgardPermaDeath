package me.ray.rpermadeath.replay.audio;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import me.ray.rpermadeath.RPermadeath;

public class ReplayVoicechatPlugin implements VoicechatPlugin {

    private static volatile VoicechatServerApi serverApi;

    @Override
    public String getPluginId() {
        return "rpermadeath-replay-audio";
    }

    @Override
    public void initialize(VoicechatApi api) {
        RPermadeath plugin = RPermadeath.getInstance();
        if (plugin != null) {
            plugin.getLogger().info("[VoiceChat Replay] Addon inicializado - captura de áudio para replay ativa.");
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
            serverApi = event.getVoicechat();
            RPermadeath plugin = RPermadeath.getInstance();
            if (plugin != null) {
                plugin.getLogger().info("[VoiceChat Replay] Servidor de voz iniciado - API disponível para replay de áudio.");
            }
        });
        registration.registerEvent(VoicechatServerStoppedEvent.class, event -> {
            serverApi = null;
            RPermadeath plugin = RPermadeath.getInstance();
            if (plugin != null) {
                plugin.getLogger().info("[VoiceChat Replay] Servidor de voz parado - replay de áudio temporariamente indisponível.");
            }
        });
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        RPermadeath plugin = RPermadeath.getInstance();
        if (plugin == null) return;

        ReplayAudioManager audioManager = plugin.getReplayAudioManager();
        if (audioManager == null || !audioManager.isEnabled()) return;

        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) return;

        var senderPlayer = sender.getPlayer();
        if (senderPlayer == null) return;

        var packet = event.getPacket();
        if (packet == null) return;

        byte[] opusData = packet.getOpusEncodedData();
        if (opusData == null || opusData.length == 0) return;

        audioManager.addAudioPacket(senderPlayer.getUuid(), opusData.clone());
    }

    public static VoicechatServerApi getServerApi() {
        if (serverApi != null) return serverApi;
        // Fallback: após PLM reload, o evento VoicechatServerStartedEvent não é disparado
        // novamente para a nova instância do plugin. Tenta obter a API via reflection.
        try {
            Class<?> apiImplClass = Class.forName("de.maxhenkel.voicechat.plugins.impl.VoicechatServerApiImpl");
            java.lang.reflect.Method instanceMethod = apiImplClass.getMethod("instance");
            Object api = instanceMethod.invoke(null);
            if (api instanceof VoicechatServerApi) {
                serverApi = (VoicechatServerApi) api;
                RPermadeath plugin = RPermadeath.getInstance();
                if (plugin != null) {
                    plugin.getLogger().info("[VoiceChat Replay] API obtida via fallback (reflection) - provavelmente após PLM reload.");
                }
                return serverApi;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
