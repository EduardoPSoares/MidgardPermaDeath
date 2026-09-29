package me.ray.rpermadeath.replay.events;

import org.bukkit.Particle;
import org.bukkit.entity.Player;

public class ParticleEvent implements ReplayEvent {
    private final String particleName;
    private final double x, y, z;
    private final float offsetX, offsetY, offsetZ;
    private final float speed;
    private final int count;
    // Dados extras podem ser complexos de serializar, vamos focar no básico por enquanto
    // ou tentar serializar o WrappedParticle se possível, mas é melhor guardar os dados primitivos.

    public ParticleEvent(String particleName, double x, double y, double z, float offsetX, float offsetY, float offsetZ, float speed, int count) {
        this.particleName = particleName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.speed = speed;
        this.count = count;
    }

    @Override
    public void play(Player viewer) {
        try {
            // Tenta usar a API do Bukkit primeiro se possível, mas ProtocolLib é mais seguro para reproduzir o que foi capturado
            // O problema é converter String de volta para Particle ou WrappedParticle
            
            // Vamos tentar usar spawnParticle do Bukkit
            try {
                Particle particle = Particle.valueOf(particleName);
                viewer.spawnParticle(particle, x, y, z, count, offsetX, offsetY, offsetZ, speed);
            } catch (IllegalArgumentException e) {
                // Se falhar (ex: particula custom ou nome diferente), tentamos ProtocolLib ou ignoramos
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getType() {
        return "PARTICLE";
    }
    
    // Getters para serialização
    public String getParticleName() { return particleName; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getOffsetX() { return offsetX; }
    public float getOffsetY() { return offsetY; }
    public float getOffsetZ() { return offsetZ; }
    public float getSpeed() { return speed; }
    public int getCount() { return count; }
}
