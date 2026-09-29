package me.ray.rpermadeath.replay.events;

import org.bukkit.entity.Player;

public interface ReplayEvent {
    void play(Player viewer);
    String getType();
}
