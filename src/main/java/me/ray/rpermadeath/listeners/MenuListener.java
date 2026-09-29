package me.ray.rpermadeath.listeners;

import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.managers.DeathInfo;
import me.ray.rpermadeath.managers.MenuManager;
import me.ray.rpermadeath.replay.ReplayManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public class MenuListener implements Listener {

    private final RPermadeath plugin;
    private final MenuManager menuManager;
    private final ReplayManager replayManager;

    public MenuListener(RPermadeath plugin, MenuManager menuManager, ReplayManager replayManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
        this.replayManager = replayManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String configTitleRaw = plugin.getConfig().getString("menu.title", "&8Valhalla - Mortos");
        String viewTitleClean = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        String configTitleClean = PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(configTitleRaw)
        );

        if (viewTitleClean.equals(configTitleClean)) {
            handleMainMenu(event, player);
            return;
        }

        if (viewTitleClean.equals(plainMessage("menu.revive-selection.title"))) {
            handleReviveSelectionMenu(event, player);
            return;
        }

        if (viewTitleClean.equals(plainMessage("menu.revive-confirmation.title"))
                || viewTitleClean.equals(plainMessage("menu.revive-confirmation.reset-title"))) {
            handleReviveConfirmationMenu(event, player);
            return;
        }

        if (viewTitleClean.startsWith(plainMessage("menu.replay-list.title-prefix"))) {
            handleReplayListMenu(event, player);
            return;
        }

        if (viewTitleClean.equals(plainMessage("menu.confirm-delete.title"))) {
            handleDeleteConfirmationMenu(event, player);
        }
    }

    private void handleMainMenu(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        NamespacedKey key = new NamespacedKey(plugin, "player_uuid");
        if (!meta.getPersistentDataContainer().has(key, PersistentDataType.STRING)) {
            return;
        }

        String uuidStr = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        UUID targetId;
        try {
            targetId = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            plugin.getMessages().send(player, "menu.listener.invalid-uuid");
            return;
        }

        if (event.getClick() == ClickType.LEFT) {
            if (replayManager.hasRecording(targetId)) {
                player.closeInventory();
                plugin.getMessages().send(player, "menu.listener.replay-loading");
                replayManager.loadRecording(targetId, recording -> {
                    if (recording == null) {
                        plugin.getMessages().send(player, "menu.listener.replay-load-error");
                        return;
                    }
                    plugin.getReplayListener().startReplay(player, recording);
                });
            } else {
                plugin.getMessages().send(player, "menu.listener.replay-unavailable");
            }
        } else if (event.isShiftClick() && event.isRightClick()) {
            if (player.hasPermission("rpermadeath.admin")) {
                player.closeInventory();
                menuManager.openConfirmationMenu(player, targetId, safePlayerName(targetId));
            }
        } else if (event.getClick() == ClickType.RIGHT) {
            if (player.hasPermission("rpermadeath.admin")) {
                player.closeInventory();
                menuManager.openReviveSelectionMenu(player, targetId, safePlayerName(targetId));
            }
        }
    }

    private void handleReviveSelectionMenu(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;

        NamespacedKey keyUuid = new NamespacedKey(plugin, "target_uuid");
        NamespacedKey keyType = new NamespacedKey(plugin, "revive_type");
        if (!item.getItemMeta().getPersistentDataContainer().has(keyUuid, PersistentDataType.STRING)) {
            return;
        }

        String uuidStr = item.getItemMeta().getPersistentDataContainer().get(keyUuid, PersistentDataType.STRING);
        String type = item.getItemMeta().getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
        UUID targetId = UUID.fromString(uuidStr);
        boolean reset = "RESET".equals(type);

        player.closeInventory();
        menuManager.openReviveConfirmationMenu(player, targetId, safePlayerName(targetId), reset);
    }

    private void handleReviveConfirmationMenu(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;

        if (item.getType() == org.bukkit.Material.RED_TERRACOTTA) {
            player.closeInventory();
            menuManager.openMainMenu(player);
            return;
        }

        if (item.getType() != org.bukkit.Material.LIME_TERRACOTTA) {
            return;
        }

        NamespacedKey keyUuid = new NamespacedKey(plugin, "target_uuid");
        NamespacedKey keyType = new NamespacedKey(plugin, "revive_type");
        if (!item.getItemMeta().getPersistentDataContainer().has(keyUuid, PersistentDataType.STRING)) {
            return;
        }

        String uuidStr = item.getItemMeta().getPersistentDataContainer().get(keyUuid, PersistentDataType.STRING);
        String type = item.getItemMeta().getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
        UUID targetId = UUID.fromString(uuidStr);
        String targetName = safePlayerName(targetId);

        player.closeInventory();

        if ("RESET".equals(type)) {
            boolean started = plugin.startPermanentReset(
                    targetId,
                    targetName,
                    plugin.getMessages().legacy("reset.permanent-kick")
            );
            if (started) {
                plugin.getMessages().send(player, "commands.reset.started", "player", targetName);
            } else {
                plugin.getMessages().send(player, "commands.reset.already-running", "player", targetName);
            }
        } else {
            player.performCommand("rdeath ressucitar " + targetName);
        }
    }

    private void handleReplayListMenu(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;

        if (item.getType() == org.bukkit.Material.BARRIER) {
            player.closeInventory();
            menuManager.openMainMenu(player);
            return;
        }

        if (item.getType() == org.bukkit.Material.ARROW) {
            NamespacedKey keyPage = new NamespacedKey(plugin, "page_nav");
            NamespacedKey keyUuid = new NamespacedKey(plugin, "target_uuid");

            if (item.getItemMeta().getPersistentDataContainer().has(keyPage, PersistentDataType.INTEGER)) {
                int page = item.getItemMeta().getPersistentDataContainer().get(keyPage, PersistentDataType.INTEGER);
                String uuidStr = item.getItemMeta().getPersistentDataContainer().get(keyUuid, PersistentDataType.STRING);
                UUID targetId = UUID.fromString(uuidStr);

                player.closeInventory();
                menuManager.openReplayListMenu(player, targetId, safePlayerName(targetId), page);
            }
            return;
        }

        if (item.getType() == org.bukkit.Material.KNOWLEDGE_BOOK) {
            NamespacedKey keyId = new NamespacedKey(plugin, "replay_id");
            if (!item.getItemMeta().getPersistentDataContainer().has(keyId, PersistentDataType.INTEGER)) {
                return;
            }

            int replayId = item.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.INTEGER);
            player.closeInventory();

            plugin.getMessages().send(player, "menu.listener.replay-loading");
            replayManager.loadRecording(replayId, recording -> {
                if (recording == null) {
                    plugin.getMessages().send(player, "menu.listener.replay-load-error");
                    return;
                }
                plugin.getReplayListener().startReplay(player, recording);
            });
        }
    }

    private void handleDeleteConfirmationMenu(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;

        if (item.getType() == org.bukkit.Material.LIME_CONCRETE) {
            NamespacedKey key = new NamespacedKey(plugin, "confirm_target");
            if (!item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING)) {
                return;
            }

            String uuidStr = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
            UUID targetId = UUID.fromString(uuidStr);
            String targetName = safePlayerName(targetId);

            player.closeInventory();

            String time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
            plugin.getLogger().info("ADMIN ACTION: " + player.getName() + " deletou " + targetName + " em " + time);
            logAdminAction(player.getName(), targetName, time);

            boolean started = plugin.startPermanentReset(
                    targetId,
                    targetName,
                    plugin.getMessages().legacy("reset.deleted-kick")
            );
            if (started) {
                plugin.getMessages().send(player, "commands.permanent-reset.started", "player", targetName);
            } else {
                plugin.getMessages().send(player, "commands.permanent-reset.already-running", "player", targetName);
            }
        } else if (item.getType() == org.bukkit.Material.RED_CONCRETE) {
            player.closeInventory();
            menuManager.openMainMenu(player);
        }
    }

    private String safePlayerName(UUID uuid) {
        DeathInfo info = plugin.getDeathManager().getDeathInfo(uuid);
        if (info != null && info.getPlayerName() != null) {
            return info.getPlayerName();
        }

        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString();
    }

    private void logAdminAction(String admin, String target, String time) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            java.io.File logFile = new java.io.File(plugin.getDataFolder(), "admin_actions.log");
            try (java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
                 java.io.PrintWriter pw = new java.io.PrintWriter(fw)) {
                pw.println("[" + time + "] " + admin + " deletou " + target);
            } catch (java.io.IOException e) {
                plugin.getLogger().warning("Erro ao gravar log de admin: " + e.getMessage());
            }
        });
    }

    private String plainMessage(String path, Object... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(plugin.getMessages().component(path, placeholders));
    }
}
