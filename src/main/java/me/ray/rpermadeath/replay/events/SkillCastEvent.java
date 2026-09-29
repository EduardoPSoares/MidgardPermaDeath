package me.ray.rpermadeath.replay.events;

import me.ray.rpermadeath.RPermadeath;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class SkillCastEvent implements ReplayEvent {
    private final String playerName;
    private final String skillName;
    private final double x;
    private final double y;
    private final double z;

    public SkillCastEvent(String playerName, String skillName, double x, double y, double z) {
        this.playerName = playerName;
        this.skillName = skillName;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void play(Player viewer) {
        RPermadeath plugin = RPermadeath.getInstance();
        if (plugin != null) {
            viewer.sendActionBar(plugin.getMessages().component(
                    "replay.skill-cast",
                    "player", playerName,
                    "skill", skillName
            ));
        }

        Location loc = new Location(viewer.getWorld(), x, y, z);

        viewer.playSound(loc, org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f);
        viewer.playSound(loc, org.bukkit.Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.5f, 1.0f);

        viewer.spawnParticle(org.bukkit.Particle.WITCH, loc.add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0.1);
        viewer.spawnParticle(org.bukkit.Particle.END_ROD, loc, 10, 0.3, 0.3, 0.3, 0.05);
    }

    @Override
    public String getType() {
        return "SKILL";
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getSkillName() {
        return skillName;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }
}
