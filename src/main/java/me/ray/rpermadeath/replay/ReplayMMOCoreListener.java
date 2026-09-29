package me.ray.rpermadeath.replay;

import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.replay.events.SkillCastEvent;
import io.lumine.mythic.lib.api.event.skill.PlayerCastSkillEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ReplayMMOCoreListener implements Listener {
    private final ReplayManager replayManager;

    public ReplayMMOCoreListener(RPermadeath plugin, ReplayManager replayManager) {
        this.replayManager = replayManager;
    }

    @EventHandler
    public void onSkillCast(PlayerCastSkillEvent event) {
        try {
            if (!replayManager.isRecordingEnabled()) return;
            
            String skillName = event.getCast().getHandler().getId();
            
            replayManager.addEvent(new SkillCastEvent(
                event.getPlayer().getName(),
                skillName,
                event.getPlayer().getLocation().getX(),
                event.getPlayer().getLocation().getY(),
                event.getPlayer().getLocation().getZ()
            ));
        } catch (Exception e) {
            // Ignora erros de gravação de skill para não afetar o gameplay
        }
    }
}
