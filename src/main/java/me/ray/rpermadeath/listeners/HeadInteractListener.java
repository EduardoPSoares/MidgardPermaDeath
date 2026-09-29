package me.ray.rpermadeath.listeners;

import me.ray.rpermadeath.RPermadeath;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

public class HeadInteractListener implements Listener {

    private final RPermadeath plugin;

    public HeadInteractListener(RPermadeath plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onHeadInteract(PlayerInteractEvent event) {
        try {
            if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
                return;
            }

            Player player = event.getPlayer();
            if (!player.isSneaking()) {
                return;
            }

            if (!player.hasPermission("rpermadeath.admin")) {
                return;
            }

            ItemStack item = event.getItem();
            if (item == null || item.getType() != Material.PLAYER_HEAD) {
                return;
            }

            SkullMeta meta = (SkullMeta) item.getItemMeta();
            if (meta == null) {
                return;
            }

            OfflinePlayer target = meta.getOwningPlayer();
            if (target == null || target.getName() == null) {
                return;
            }

            String targetName = target.getName();

            boolean started = plugin.startPermanentReset(
                    target.getUniqueId(),
                    targetName,
                    "\u00A7c[RPermadeath] \u00A7fSeu personagem foi deletado."
            );

            if (started) {
                plugin.getMessages().send(player, "commands.permanent-reset.started", "player", targetName);
            } else {
                plugin.getMessages().send(player, "commands.permanent-reset.already-running", "player", targetName);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no HeadInteractListener.onHeadInteract: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
