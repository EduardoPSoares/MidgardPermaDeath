package me.ray.rpermadeath.replay;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedParticle;
import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.replay.events.ParticleEvent;
import org.bukkit.Particle;

public class ReplayProtocolListener {

    public ReplayProtocolListener(RPermadeath plugin, ReplayManager replayManager) {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, PacketType.Play.Server.WORLD_PARTICLES) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    if (!replayManager.isRecordingEnabled()) return;

                    // Extrai dados do pacote
                    // O índice dos campos pode variar dependendo da versão do Minecraft
                    // Usando ProtocolLib wrappers é mais seguro
                    
                    double x = event.getPacket().getDoubles().read(0);
                    double y = event.getPacket().getDoubles().read(1);
                    double z = event.getPacket().getDoubles().read(2);
                    
                    float offsetX = event.getPacket().getFloat().read(0);
                    float offsetY = event.getPacket().getFloat().read(1);
                    float offsetZ = event.getPacket().getFloat().read(2);
                    float speed = event.getPacket().getFloat().read(3);
                    int count = event.getPacket().getIntegers().read(0);
                    
                    // Tenta obter o nome da partícula
                    String particleName = "UNKNOWN";
                    
                    // Em versões mais recentes, a partícula é um objeto complexo (WrappedParticle)
                    if (event.getPacket().getNewParticles().size() > 0) {
                        WrappedParticle wrapped = event.getPacket().getNewParticles().read(0);
                        particleName = wrapped.getParticle().name();
                    } else {
                        // Fallback para versões antigas ou se não usar NewParticles
                        // Pode ser um EnumWrappers.Particle
                        // Mas vamos assumir 1.20+ onde NewParticles é usado
                    }

                    replayManager.addEvent(new ParticleEvent(
                        particleName,
                        x, y, z,
                        offsetX, offsetY, offsetZ,
                        speed, count
                    ));
                    
                } catch (Exception e) {
                    // Ignora erros para não lagar o servidor
                }
            }
        });
    }
}
