package me.ray.rpermadeath.managers;

import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.replay.ReplayManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MenuManager {
    private final RPermadeath plugin;
    private final DeathManager deathManager;
    private ReplayManager replayManager;

    public MenuManager(RPermadeath plugin, DeathManager deathManager) {
        this.plugin = plugin;
        this.deathManager = deathManager;
    }

    public void setReplayManager(ReplayManager replayManager) {
        this.replayManager = replayManager;
    }

    public void openMainMenu(Player player) {
        try {
            String title = plugin.getConfig().getString("menu.title", "&8Valhalla - Mortos");
            int size = Math.max(27, plugin.getConfig().getInt("menu.size", 54));

            List<String> deadPlayers = deathManager.getDeadPlayerNames();
            List<Integer> validSlots = getValidSlots(size);

            final int finalSize = size;
            final String finalTitle = title;
            final String skullNameFormat = plugin.getConfig().getString("menu.items.player-head.name", "&c{player}");
            final List<String> loreFormat = plugin.getConfig().getStringList("menu.items.player-head.lore");
            final String replayAvailable = plugin.getConfig().getString("menu.items.replay-status.available", "&eClique esquerdo para assistir replay");
            final String replayUnavailable = plugin.getConfig().getString("menu.items.replay-status.unavailable", "&8Replay indisponivel");
            final String replayDisabled = plugin.getConfig().getString("menu.items.replay-status.disabled", "&cReplay desativado");
            final String adminAction = plugin.getConfig().getString("menu.items.admin-action", "&aClique direito para ressuscitar");
            final String deleteAction = plugin.getConfig().getString("menu.items.delete-action", "&cShift+direito para excluir");
            final String emptyName = plugin.getConfig().getString("menu.items.empty.name", "&cNenhum jogador morto");
            final boolean isAdmin = player.hasPermission("rpermadeath.admin");

            List<Object[]> deathData = new ArrayList<>();
            for (String name : deadPlayers) {
                UUID uuid = null;
                for (UUID id : deathManager.getDeadPlayers()) {
                    DeathInfo info = deathManager.getDeathInfo(id);
                    if (info != null && name.equals(info.getPlayerName())) {
                        uuid = id;
                        break;
                    }
                }
                if (uuid != null) {
                    deathData.add(new Object[]{
                            name,
                            uuid,
                            deathManager.getDeathDate(uuid),
                            deathManager.getDeathCause(uuid),
                            deathManager.getDeathLocationString(uuid),
                            replayManager != null && replayManager.hasRecording(uuid)
                    });
                }
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<ItemStack> skulls = new ArrayList<>();
                for (Object[] data : deathData) {
                    if (skulls.size() >= validSlots.size()) break;

                    String name = (String) data[0];
                    UUID uuid = (UUID) data[1];
                    String date = (String) data[2];
                    String cause = (String) data[3];
                    String location = (String) data[4];
                    boolean hasReplay = (boolean) data[5];

                    ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                    SkullMeta meta = (SkullMeta) skull.getItemMeta();
                    if (meta == null) {
                        continue;
                    }

                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
                    meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(skullNameFormat.replace("{player}", name)));

                    List<Component> lore = new ArrayList<>();
                    for (String line : loreFormat) {
                        if (line.contains("{replay_status}")) {
                            if (replayManager != null && !replayManager.isRecordingEnabled()) {
                                lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(replayDisabled));
                            } else {
                                lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(hasReplay ? replayAvailable : replayUnavailable));
                            }
                        } else if (line.contains("{admin_action}")) {
                            if (isAdmin) {
                                lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(adminAction));
                                lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(deleteAction));
                            }
                        } else {
                            String formattedLine = line
                                    .replace("{date}", date)
                                    .replace("{cause}", cause)
                                    .replace("{location}", location);
                            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(formattedLine));
                        }
                    }
                    meta.lore(lore);
                    meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "player_uuid"), PersistentDataType.STRING, uuid.toString());
                    skull.setItemMeta(meta);
                    skulls.add(skull);
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;

                    Inventory inv = Bukkit.createInventory(null, finalSize, LegacyComponentSerializer.legacyAmpersand().deserialize(finalTitle));
                    decorateMenu(inv);

                    if (skulls.isEmpty()) {
                        ItemStack empty = new ItemStack(Material.BARRIER);
                        ItemMeta meta = empty.getItemMeta();
                        if (meta != null) {
                            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(emptyName));
                            empty.setItemMeta(meta);
                        }
                        inv.setItem(22, empty);
                    } else {
                        for (int i = 0; i < skulls.size() && i < validSlots.size(); i++) {
                            inv.setItem(validSlots.get(i), skulls.get(i));
                        }
                    }

                    ItemStack info = new ItemStack(Material.BOOK);
                    ItemMeta infoMeta = info.getItemMeta();
                    if (infoMeta != null) {
                        infoMeta.displayName(plugin.getMessages().component("menu.main.info.name"));
                        infoMeta.lore(plugin.getMessages().componentList("menu.main.info.lore", "count", deathData.size()));
                        info.setItemMeta(infoMeta);
                    }
                    inv.setItem(finalSize - 5, info);

                    fillEmpty(inv);
                    player.openInventory(inv);
                });
            });
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao abrir menu principal: " + e.getMessage());
            plugin.getMessages().send(player, "general.open-menu-error");
        }
    }

    private List<Integer> getValidSlots(int size) {
        List<Integer> slots = new ArrayList<>();
        int rows = size / 9;
        for (int row = 1; row < rows - 1; row++) {
            for (int col = 1; col < 8; col++) {
                slots.add(row * 9 + col);
            }
        }
        return slots;
    }

    private void decorateMenu(Inventory inv) {
        ItemStack border = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta borderMeta = border.getItemMeta();
        if (borderMeta != null) {
            borderMeta.displayName(Component.empty());
            border.setItemMeta(borderMeta);
        }

        int size = inv.getSize();
        int rows = size / 9;
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, border);
            inv.setItem(size - 9 + i, border);
        }
        for (int i = 1; i < rows - 1; i++) {
            inv.setItem(i * 9, border);
            inv.setItem(i * 9 + 8, border);
        }
    }

    private void fillEmpty(Inventory inv) {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            filler.setItemMeta(meta);
        }

        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }
    }

    public void openConfirmationMenu(Player admin, UUID targetId, String targetName) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ItemStack info = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta infoMeta = (SkullMeta) info.getItemMeta();
            if (infoMeta != null) {
                infoMeta.setOwningPlayer(Bukkit.getOfflinePlayer(targetId));
                infoMeta.displayName(plugin.getMessages().component("menu.confirm-delete.target-name", "player", targetName));
                info.setItemMeta(infoMeta);
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!admin.isOnline()) return;

                Inventory inv = Bukkit.createInventory(null, 27, plugin.getMessages().component("menu.confirm-delete.title"));
                decorateMenu(inv);

                ItemStack confirm = new ItemStack(Material.LIME_CONCRETE);
                ItemMeta confirmMeta = confirm.getItemMeta();
                if (confirmMeta != null) {
                    confirmMeta.displayName(plugin.getMessages().component("menu.confirm-delete.confirm-name"));
                    confirmMeta.lore(plugin.getMessages().componentList("menu.confirm-delete.confirm-lore"));
                    confirmMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "confirm_target"), PersistentDataType.STRING, targetId.toString());
                    confirm.setItemMeta(confirmMeta);
                }

                ItemStack cancel = new ItemStack(Material.RED_CONCRETE);
                ItemMeta cancelMeta = cancel.getItemMeta();
                if (cancelMeta != null) {
                    cancelMeta.displayName(plugin.getMessages().component("menu.confirm-delete.cancel-name"));
                    cancelMeta.lore(plugin.getMessages().componentList("menu.confirm-delete.cancel-lore"));
                    cancel.setItemMeta(cancelMeta);
                }

                inv.setItem(11, confirm);
                inv.setItem(13, info);
                inv.setItem(15, cancel);

                fillEmpty(inv);
                admin.openInventory(inv);
            });
        });
    }

    public void openReplayListMenu(Player viewer, UUID targetId, String targetName, int page) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<me.ray.rpermadeath.replay.ReplayStorage.ReplayMetadata> replays = replayManager.getReplays(targetId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;
                buildReplayListMenu(viewer, targetId, targetName, page, replays);
            });
        });
    }

    private void buildReplayListMenu(Player viewer, UUID targetId, String targetName, int page,
                                     List<me.ray.rpermadeath.replay.ReplayStorage.ReplayMetadata> replays) {
        Inventory inv = Bukkit.createInventory(null, 54,
                plugin.getMessages().component("menu.replay-list.title", "player", targetName, "page", page + 1));

        decorateMenu(inv);
        List<Integer> validSlots = getValidSlots(54);

        if (replays.isEmpty()) {
            plugin.getMessages().send(viewer, "menu.replay-list.empty-message");
            return;
        }

        int itemsPerPage = validSlots.size();
        int totalPages = (int) Math.ceil((double) replays.size() / itemsPerPage);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        int startIndex = safePage * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, replays.size());

        int slotIndex = 0;
        for (int i = startIndex; i < endIndex; i++) {
            me.ray.rpermadeath.replay.ReplayStorage.ReplayMetadata replay = replays.get(i);

            ItemStack item = new ItemStack(Material.KNOWLEDGE_BOOK);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                String date = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                        .format(java.time.Instant.ofEpochMilli(replay.getTimestamp()).atZone(java.time.ZoneId.systemDefault()));

                meta.displayName(plugin.getMessages().component("menu.replay-list.entry-name", "date", date));
                meta.lore(plugin.getMessages().componentList("menu.replay-list.entry-lore", "id", replay.getId()));
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "replay_id"), PersistentDataType.INTEGER, replay.getId());
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "target_uuid"), PersistentDataType.STRING, targetId.toString());
                item.setItemMeta(meta);
            }
            inv.setItem(validSlots.get(slotIndex++), item);
        }

        if (safePage > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta meta = prev.getItemMeta();
            if (meta != null) {
                meta.displayName(plugin.getMessages().component("menu.replay-list.previous"));
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "page_nav"), PersistentDataType.INTEGER, safePage - 1);
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "target_uuid"), PersistentDataType.STRING, targetId.toString());
                prev.setItemMeta(meta);
            }
            inv.setItem(48, prev);
        }

        if (safePage < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta meta = next.getItemMeta();
            if (meta != null) {
                meta.displayName(plugin.getMessages().component("menu.replay-list.next"));
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "page_nav"), PersistentDataType.INTEGER, safePage + 1);
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "target_uuid"), PersistentDataType.STRING, targetId.toString());
                next.setItemMeta(meta);
            }
            inv.setItem(50, next);
        }

        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.displayName(plugin.getMessages().component("menu.replay-list.back"));
            back.setItemMeta(backMeta);
        }
        inv.setItem(49, back);

        fillEmpty(inv);
        viewer.openInventory(inv);
    }

    public void openReviveSelectionMenu(Player player, UUID targetId, String targetName) {
        Inventory inv = Bukkit.createInventory(null, 27, plugin.getMessages().component("menu.revive-selection.title"));

        ItemStack normal = new ItemStack(Material.LIME_WOOL);
        ItemMeta normalMeta = normal.getItemMeta();
        if (normalMeta != null) {
            normalMeta.displayName(plugin.getMessages().component("menu.revive-selection.normal-name"));
            normalMeta.lore(plugin.getMessages().componentList("menu.revive-selection.normal-lore"));
            normalMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "target_uuid"), PersistentDataType.STRING, targetId.toString());
            normalMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "revive_type"), PersistentDataType.STRING, "NORMAL");
            normal.setItemMeta(normalMeta);
        }

        ItemStack reset = new ItemStack(Material.RED_WOOL);
        ItemMeta resetMeta = reset.getItemMeta();
        if (resetMeta != null) {
            resetMeta.displayName(plugin.getMessages().component("menu.revive-selection.reset-name"));
            resetMeta.lore(plugin.getMessages().componentList("menu.revive-selection.reset-lore"));
            resetMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "target_uuid"), PersistentDataType.STRING, targetId.toString());
            resetMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "revive_type"), PersistentDataType.STRING, "RESET");
            reset.setItemMeta(resetMeta);
        }

        inv.setItem(11, normal);
        inv.setItem(15, reset);
        player.openInventory(inv);
    }

    public void openReviveConfirmationMenu(Player player, UUID targetId, String targetName, boolean reset) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(targetId));
                meta.displayName(plugin.getMessages().component("menu.revive-confirmation.target-name", "player", targetName));
                skull.setItemMeta(meta);
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;

                Inventory inv = Bukkit.createInventory(null, 27, plugin.getMessages().component(
                        reset ? "menu.revive-confirmation.reset-title" : "menu.revive-confirmation.title"
                ));

                ItemStack confirm = new ItemStack(Material.LIME_TERRACOTTA);
                ItemMeta confirmMeta = confirm.getItemMeta();
                if (confirmMeta != null) {
                    confirmMeta.displayName(plugin.getMessages().component("menu.revive-confirmation.confirm-name"));
                    confirmMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "target_uuid"), PersistentDataType.STRING, targetId.toString());
                    confirmMeta.getPersistentDataContainer().set(new NamespacedKey(plugin, "revive_type"), PersistentDataType.STRING, reset ? "RESET" : "NORMAL");
                    confirm.setItemMeta(confirmMeta);
                }

                ItemStack cancel = new ItemStack(Material.RED_TERRACOTTA);
                ItemMeta cancelMeta = cancel.getItemMeta();
                if (cancelMeta != null) {
                    cancelMeta.displayName(plugin.getMessages().component("menu.revive-confirmation.cancel-name"));
                    cancel.setItemMeta(cancelMeta);
                }

                inv.setItem(13, skull);
                inv.setItem(11, confirm);
                inv.setItem(15, cancel);

                player.openInventory(inv);
            });
        });
    }
}
