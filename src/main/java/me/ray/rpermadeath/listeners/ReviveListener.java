package me.ray.rpermadeath.listeners;

import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.replay.ReplayManager;
import net.kokoricraft.reviveme.events.PlayerDownedEvent;
import net.kokoricraft.reviveme.events.PlayerReviveEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;

public class ReviveListener implements Listener {

    private final ReplayManager replayManager;

    public ReviveListener(RPermadeath plugin, ReplayManager replayManager) {
        this.replayManager = replayManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDown(PlayerDownedEvent event) {
        try {
            Player player = event.getPlayer();
            replayManager.startDownedRecording(player);
        } catch (Exception e) {
            // Log error but don't break the event
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerRevive(PlayerReviveEvent event) {
        try {
            Player player = event.getPlayer();
            replayManager.cancelRecording(player);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
