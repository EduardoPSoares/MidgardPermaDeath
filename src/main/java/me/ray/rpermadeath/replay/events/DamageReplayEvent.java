package me.ray.rpermadeath.replay.events;

import me.ray.rpermadeath.RPermadeath;
import org.bukkit.entity.Player;

public class DamageReplayEvent implements ReplayEvent {
    private final double damage;
    private final String damagerName;
    private final String victimName;

    public DamageReplayEvent(double damage, String damagerName, String victimName) {
        this.damage = damage;
        this.damagerName = damagerName;
        this.victimName = victimName;
    }

    @Override
    public void play(Player viewer) {
        RPermadeath plugin = RPermadeath.getInstance();
        if (plugin != null) {
            plugin.getMessages().send(viewer, "replay.damage-event",
                    "victim", victimName,
                    "damage", String.format("%.1f", damage),
                    "damager", damagerName);
        }
    }

    @Override
    public String getType() {
        return "DAMAGE";
    }

    public String getVictimName() {
        return victimName;
    }

    public String getDamagerName() {
        return damagerName;
    }
}
