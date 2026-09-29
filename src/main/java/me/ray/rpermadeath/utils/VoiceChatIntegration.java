package me.ray.rpermadeath.utils;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.managers.DeathState;

public class VoiceChatIntegration implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "rpermadeath";
    }

    @Override
    public void initialize(VoicechatApi api) {
        RPermadeath plugin = RPermadeath.getInstance();
        if (plugin != null) {
            plugin.getLogger().info("[VoiceChat Ghost] Addon inicializado - fantasmas serão mutados no voice chat.");
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        try {
            var connection = event.getSenderConnection();
            if (connection == null) return;
            
            var player = connection.getPlayer();
            if (player == null) return;
            
            RPermadeath plugin = RPermadeath.getInstance();
            if (plugin == null) return;
            
            var deathManager = plugin.getDeathManager();
            if (deathManager == null) return;
            
            java.util.UUID uuid = player.getUuid();
            DeathState state = deathManager.getDeathState(uuid);
            
            if (state == DeathState.GHOST) {
                event.cancel();
            }
        } catch (Exception e) {
            RPermadeath inst = RPermadeath.getInstance();
            if (inst != null) {
                inst.getLogger().log(java.util.logging.Level.SEVERE, "[VoiceChat Ghost] Erro ao verificar estado de ghost", e);
            }
        }
    }
}
