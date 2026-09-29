package me.ray.rpermadeath.listeners;

import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.managers.DeathInfo;
import me.ray.rpermadeath.managers.DeathManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;

public class RitualListener implements Listener {

    private final RPermadeath plugin;
    private final DeathManager deathManager;

    public RitualListener(RPermadeath plugin, DeathManager deathManager) {
        this.plugin = plugin;
        this.deathManager = deathManager;
    }

    @EventHandler
    public void onRitualInteract(PlayerInteractEvent event) {
        try {
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
            if (!plugin.getConfig().getBoolean("ritual.enabled", true)) return;

            Block clickedBlock = event.getClickedBlock();
            if (clickedBlock == null) return;

            String interactionBlockType = plugin.getConfig().getString("ritual.interaction-block", "BEACON");
            if (!clickedBlock.getType().name().equals(interactionBlockType)) return;

            String baseBlockType = plugin.getConfig().getString("ritual.base-block", "GOLD_BLOCK");
            Material baseMat = Material.getMaterial(baseBlockType);
            if (baseMat == null) return;

            Block centerBase = clickedBlock.getRelative(BlockFace.DOWN);
            if (!checkBase(centerBase, baseMat)) {
                plugin.getMessages().send(event.getPlayer(), "ritual.altar-incomplete", "block", baseBlockType);
                return;
            }

            ItemStack item = event.getItem();
            if (item == null) return;

            String requiredItemType = plugin.getConfig().getString("ritual.item", "NETHER_STAR");
            if (!item.getType().name().equals(requiredItemType)) return;

            String requiredName = plugin.getConfig().getString("ritual.item-name", "&6&lTotem de Odin").replace("&", "§");
            ItemMeta meta = item.getItemMeta();
            if (meta == null || meta.displayName() == null
                    || !LegacyComponentSerializer.legacySection().serialize(meta.displayName()).equals(requiredName)) {
                plugin.getMessages().send(event.getPlayer(), "ritual.invalid-item");
                return;
            }

            event.setCancelled(true);
            openReviveMenu(event.getPlayer());
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no RitualListener.onRitualInteract: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean checkBase(Block center, Material mat) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (center.getRelative(x, 0, z).getType() != mat) {
                    return false;
                }
            }
        }
        return true;
    }

    private void openReviveMenu(Player player) {
        java.util.List<UUID> deadIds = new java.util.ArrayList<>(deathManager.getDeadPlayers());
        java.util.List<Object[]> entries = new java.util.ArrayList<>();
        for (UUID deadId : deadIds) {
            DeathInfo info = deathManager.getDeathInfo(deadId);
            if (info == null) continue;
            entries.add(new Object[]{deadId, info});
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            java.util.List<ItemStack> items = new java.util.ArrayList<>();
            for (Object[] entry : entries) {
                UUID deadId = (UUID) entry[0];
                DeathInfo info = (DeathInfo) entry[1];

                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
                if (meta != null) {
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(deadId));
                    meta.displayName(plugin.getMessages().component("ritual.menu.head-name", "player", info.getPlayerName()));
                    meta.lore(plugin.getMessages().componentList("ritual.menu.head-lore",
                            "date", info.getDeathTime().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                            "killer", info.getKillerName()));
                    meta.getPersistentDataContainer().set(
                            new org.bukkit.NamespacedKey(plugin, "dead_uuid"),
                            org.bukkit.persistence.PersistentDataType.STRING,
                            deadId.toString()
                    );
                    head.setItemMeta(meta);
                    items.add(head);
                }
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                org.bukkit.inventory.Inventory gui = Bukkit.createInventory(null, 54, plugin.getMessages().component("ritual.menu.title"));
                for (ItemStack item : items) {
                    gui.addItem(item);
                }
                player.openInventory(gui);
            });
        });
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        try {
            String title = LegacyComponentSerializer.legacySection().serialize(event.getView().title());
            if (!title.equals(plugin.getMessages().legacy("ritual.menu.title"))) return;
            event.setCancelled(true);

            if (event.getCurrentItem() == null || event.getCurrentItem().getType() != Material.PLAYER_HEAD) return;

            Player player = (Player) event.getWhoClicked();
            ItemMeta meta = event.getCurrentItem().getItemMeta();
            if (meta == null) return;

            String uuidStr = meta.getPersistentDataContainer().get(
                    new org.bukkit.NamespacedKey(plugin, "dead_uuid"),
                    org.bukkit.persistence.PersistentDataType.STRING
            );

            if (uuidStr == null) {
                return;
            }

            try {
                UUID deadId = UUID.fromString(uuidStr);

                ItemStack hand = player.getInventory().getItemInMainHand();
                String requiredItemType = plugin.getConfig().getString("ritual.item", "NETHER_STAR");
                if (!hand.getType().name().equals(requiredItemType)) {
                    plugin.getMessages().send(player, "ritual.need-item-in-hand");
                    player.closeInventory();
                    return;
                }

                String requiredName = plugin.getConfig().getString("ritual.item-name", "&6&lTotem de Odin").replace("&", "§");
                ItemMeta handMeta = hand.getItemMeta();
                if (handMeta == null || handMeta.displayName() == null
                        || !LegacyComponentSerializer.legacySection().serialize(handMeta.displayName()).equals(requiredName)) {
                    plugin.getMessages().send(player, "ritual.need-correct-item");
                    player.closeInventory();
                    return;
                }

                if (!deathManager.isDead(deadId)) {
                    plugin.getMessages().send(player, "ritual.target-already-revived");
                    player.closeInventory();
                    return;
                }

                if (hand.getAmount() > 1) {
                    hand.setAmount(hand.getAmount() - 1);
                } else {
                    player.getInventory().setItemInMainHand(null);
                }

                player.closeInventory();

                player.getWorld().strikeLightningEffect(player.getLocation());
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.5f);

                Bukkit.broadcast(plugin.getMessages().component("ritual.broadcast-complete"));
                String revivedName = meta.displayName() != null
                        ? LegacyComponentSerializer.legacySection().serialize(meta.displayName()).replace("§e", "")
                        : plugin.getMessages().raw("general.unknown");
                Bukkit.broadcast(plugin.getMessages().component("ritual.broadcast-success",
                        "player", player.getName(),
                        "target", revivedName));

                deathManager.revive(deadId);
            } catch (IllegalArgumentException e) {
                plugin.getMessages().send(player, "ritual.invalid-uuid");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro no RitualListener.onInventoryClick: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
