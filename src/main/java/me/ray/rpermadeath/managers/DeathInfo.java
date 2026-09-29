package me.ray.rpermadeath.managers;

import org.bukkit.Location;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class DeathInfo {
    private final UUID playerId;
    private final String playerName;
    private final LocalDateTime deathTime;
    private final UUID killerId;
    private final String killerName;
    private final Location deathLocation;

    public DeathInfo(UUID playerId, String playerName, LocalDateTime deathTime, UUID killerId, String killerName, Location deathLocation) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.deathTime = deathTime;
        this.killerId = killerId;
        this.killerName = killerName;
        this.deathLocation = deathLocation;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public LocalDateTime getDeathTime() {
        return deathTime;
    }

    public String getFormattedDeathTime() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        return deathTime.format(formatter);
    }

    public UUID getKillerId() {
        return killerId;
    }

    public String getKillerName() {
        return killerName;
    }

    public Location getDeathLocation() {
        return deathLocation;
    }

    public String getFormattedLocation() {
        if (deathLocation == null) {
            return "Desconhecida";
        }
        return String.format("X: %.0f, Y: %.0f, Z: %.0f (%s)",
                deathLocation.getX(),
                deathLocation.getY(),
                deathLocation.getZ(),
                deathLocation.getWorld() != null ? deathLocation.getWorld().getName() : "Desconhecido");
    }
}
