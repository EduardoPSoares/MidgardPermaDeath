package me.ray.rpermadeath.commands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.managers.DeathInfo;
import me.ray.rpermadeath.managers.DeathManager;
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
import org.jetbrains.annotations.NotNull;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class InfoMorteCommand implements BasicCommand {

    private final RPermadeath plugin;
    private final DeathManager deathManager;
    private final ReplayManager replayManager;

    public InfoMorteCommand(RPermadeath plugin, DeathManager deathManager, ReplayManager replayManager) {
        this.plugin = plugin;
        this.deathManager = deathManager;
        this.replayManager = replayManager;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        try {
            if (!(source.getSender() instanceof Player player)) {
                plugin.getMessages().send(source.getSender(), "general.players-only");
                return;
            }

            if (args.length < 1) {
                plugin.getMessages().send(player, "commands.infomorte.usage");
                return;
            }

            Player target = Bukkit.getPlayerExact(args[0]);
            UUID targetId = target != null
                    ? target.getUniqueId()
                    : Bukkit.getOfflinePlayer(args[0]).getUniqueId();

            if (!deathManager.isDead(targetId)) {
                plugin.getMessages().send(player, "general.player-not-dead");
                return;
            }

            DeathInfo deathInfo = deathManager.getDeathInfo(targetId);
            if (deathInfo == null) {
                plugin.getMessages().send(player, "death.info-not-found");
                return;
            }

            openDeathInfoMenu(player, deathInfo);
        } catch (Exception e) {
            plugin.getMessages().send(source.getSender(), "general.command-error", "error", e.getMessage());
            e.printStackTrace();
        }
    }

    private void openDeathInfoMenu(Player viewer, DeathInfo deathInfo) {
        String title = plugin.getConfig().getString("menu.infomorte.title");
        title = title != null ? title.replace("{player}", deathInfo.getPlayerName()) : "";

        int size = plugin.getConfig().getInt("menu.infomorte.size", 27);
        Inventory inv = Bukkit.createInventory(null, size, LegacyComponentSerializer.legacyAmpersand().deserialize(title));

        if (plugin.getConfig().getBoolean("menu.decoracao.enabled", true)) {
            addDecoration(inv);
        }

        addVictimItem(inv, deathInfo);
        addTimeItem(inv, deathInfo);
        addKillerItem(inv, deathInfo);
        addLocationItem(inv, deathInfo);
        addReplayItem(inv, deathInfo);

        viewer.openInventory(inv);
    }

    private void addDecoration(Inventory inv) {
        String materialName = plugin.getConfig().getString("menu.decoracao.material", "BLACK_STAINED_GLASS_PANE");
        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.BLACK_STAINED_GLASS_PANE;
        }

        ItemStack decoration = new ItemStack(material);
        ItemMeta meta = decoration.getItemMeta();
        if (meta == null) {
            return;
        }

        String name = plugin.getConfig().getString("menu.decoracao.nome");
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name != null ? name : ""));
        decoration.setItemMeta(meta);

        for (int slot : plugin.getConfig().getIntegerList("menu.decoracao.slots")) {
            if (slot >= 0 && slot < inv.getSize()) {
                inv.setItem(slot, decoration);
            }
        }
    }

    private void addVictimItem(Inventory inv, DeathInfo deathInfo) {
        int slot = plugin.getConfig().getInt("menu.vitima.slot", 11);
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.setOwningPlayer(Bukkit.getOfflinePlayer(deathInfo.getPlayerName()));

        String name = plugin.getConfig().getString("menu.vitima.nome");
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(
                name != null ? name.replace("{player}", deathInfo.getPlayerName()) : ""
        ));

        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("menu.vitima.lore")) {
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    line.replace("{player}", deathInfo.getPlayerName())
            ));
        }
        meta.lore(lore);

        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    private void addTimeItem(Inventory inv, DeathInfo deathInfo) {
        int slot = plugin.getConfig().getInt("menu.tempo.slot", 13);
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        String date = deathInfo.getDeathTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String time = deathInfo.getDeathTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        String name = plugin.getConfig().getString("menu.tempo.nome");
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(
                name != null ? name.replace("{date}", date).replace("{time}", time) : ""
        ));

        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("menu.tempo.lore")) {
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    line.replace("{date}", date).replace("{time}", time)
            ));
        }
        meta.lore(lore);

        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    private void addKillerItem(Inventory inv, DeathInfo deathInfo) {
        int slot = plugin.getConfig().getInt("menu.assassino.slot", 15);
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        String killer = deathInfo.getKillerName() != null ? deathInfo.getKillerName() : plugin.getMessages().raw("general.unknown");
        String name = plugin.getConfig().getString("menu.assassino.nome");
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(
                name != null ? name.replace("{killer}", killer) : ""
        ));

        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("menu.assassino.lore")) {
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line.replace("{killer}", killer)));
        }
        meta.lore(lore);

        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    private void addLocationItem(Inventory inv, DeathInfo deathInfo) {
        int slot = plugin.getConfig().getInt("menu.localizacao.slot", 22);
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        String unknown = plugin.getMessages().raw("general.unknown");
        String world = deathInfo.getDeathLocation() != null && deathInfo.getDeathLocation().getWorld() != null
                ? deathInfo.getDeathLocation().getWorld().getName()
                : unknown;
        String x = deathInfo.getDeathLocation() != null ? String.format("%.0f", deathInfo.getDeathLocation().getX()) : "?";
        String y = deathInfo.getDeathLocation() != null ? String.format("%.0f", deathInfo.getDeathLocation().getY()) : "?";
        String z = deathInfo.getDeathLocation() != null ? String.format("%.0f", deathInfo.getDeathLocation().getZ()) : "?";

        String name = plugin.getConfig().getString("menu.localizacao.nome");
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(
                name != null
                        ? name.replace("{world}", world).replace("{x}", x).replace("{y}", y).replace("{z}", z)
                        : ""
        ));

        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("menu.localizacao.lore")) {
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    line.replace("{world}", world).replace("{x}", x).replace("{y}", y).replace("{z}", z)
            ));
        }
        meta.lore(lore);

        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    private void addReplayItem(Inventory inv, DeathInfo deathInfo) {
        if (!replayManager.hasRecording(deathInfo.getPlayerId())) {
            return;
        }

        int slot = plugin.getConfig().getInt("menu.replay.slot", 31);
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        String name = plugin.getConfig().getString("menu.replay.nome");
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name != null ? name : ""));

        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("menu.replay.lore")) {
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
        }
        lore.add(plugin.getMessages().component("menu.infomorte.hidden-id", "id", deathInfo.getPlayerId()));
        meta.lore(lore);

        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length == 0) {
            return deathManager.getDeadPlayerNames();
        }

        String input = args[args.length - 1].toLowerCase();
        return deathManager.getDeadPlayerNames().stream()
                .filter(name -> name.toLowerCase().startsWith(input))
                .toList();
    }

    @Override
    public String permission() {
        return "rpermadeath.admin";
    }
}
