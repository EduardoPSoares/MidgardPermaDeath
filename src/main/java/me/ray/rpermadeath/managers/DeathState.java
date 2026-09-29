package me.ray.rpermadeath.managers;

public enum DeathState {
    ALIVE,
    SPECTATOR, // Modo espectador padrão (Gamemode 3)
    VALHALLA,  // Teleportado para mundo específico
    PURGATORY, // Teleportado para o purgatorio
    GHOST      // Modo fantasma (Invisível, livre, sem interação)
}
