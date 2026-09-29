package me.ray.rpermadeath.managers;

import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.database.DatabaseManager;
import net.Indyuce.mmocore.api.player.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class BountyManager {

    private final RPermadeath plugin;
    private final DatabaseManager databaseManager;
    private final Map<UUID, Double> bounties = new ConcurrentHashMap<>();
    private final Map<UUID, String> nameCache = new ConcurrentHashMap<>();

    public BountyManager(RPermadeath plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        load();
    }

    public void load() {
        bounties.clear();
        nameCache.clear();
        if (databaseManager == null || !databaseManager.isAvailable()) {
            plugin.getLogger().warning("Banco de dados indisponivel. Bounties nao carregados.");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = databaseManager.getConnection()) {
                if (conn == null) return;
                try (PreparedStatement stmt = conn.prepareStatement("SELECT player_uuid, bounty FROM midgard_bounties");
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        try {
                            UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                            double value = rs.getDouble("bounty");
                            if (value > 0) {
                                bounties.put(uuid, value);
                            }
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                plugin.getLogger().info("Bounties carregados do banco de dados: " + bounties.size() + " entradas.");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erro ao carregar bounties do banco de dados", e);
            }
        });
    }

    private void saveBounty(UUID uuid, double value) {
        if (databaseManager == null || !databaseManager.isAvailable()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = databaseManager.getConnection()) {
                if (conn == null) return;
                if (value <= 0) {
                    try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM midgard_bounties WHERE player_uuid = ?")) {
                        stmt.setString(1, uuid.toString());
                        stmt.executeUpdate();
                    }
                } else {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "INSERT INTO midgard_bounties (player_uuid, bounty) VALUES (?, ?) ON CONFLICT(player_uuid) DO UPDATE SET bounty = excluded.bounty")) {
                        stmt.setString(1, uuid.toString());
                        stmt.setDouble(2, value);
                        stmt.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erro ao salvar bounty no banco de dados para " + uuid, e);
            }
        });
    }

    public java.util.List<String> getBountyPlayerNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (UUID uuid : bounties.keySet()) {
            String cached = nameCache.get(uuid);
            if (cached != null) {
                names.add(cached);
            } else {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    nameCache.put(uuid, online.getName());
                    names.add(online.getName());
                }
            }
        }
        return names;
    }

    public double getBounty(UUID uuid) {
        return bounties.getOrDefault(uuid, 0.0);
    }

    public void addBounty(Player killer) {
        try {
            UUID uuid = killer.getUniqueId();
            nameCache.put(uuid, killer.getName());
            double currentBounty = getBounty(uuid);

            int level;
            if (Bukkit.getPluginManager().isPluginEnabled("MMOCore")) {
                try {
                    level = PlayerData.get(killer.getUniqueId()).getLevel();
                } catch (Exception e) {
                    level = killer.getLevel();
                }
            } else {
                level = killer.getLevel();
            }

            double addition = Math.max(1, level) * 5000.0;
            double newBounty = currentBounty + addition;
            bounties.put(uuid, newBounty);
            saveBounty(uuid, newBounty);

            plugin.getMessages().send(killer, "bounty.crime-line-1");
            plugin.getMessages().send(killer, "bounty.crime-line-2", "amount", String.format("%,.0f", addition));
            plugin.getMessages().send(killer, "bounty.crime-line-3", "amount", String.format("%,.0f", newBounty));
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao adicionar bounty: " + e.getMessage());
        }
    }

    public void claimBounty(Player hunter, Player target) {
        try {
            double bounty = getBounty(target.getUniqueId());
            if (bounty > 0) {
                String command = String.format("eco give %s %.0f", hunter.getName(), bounty);
                plugin.getLogger().info("Pagando recompensa: " + command);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

                resetBounty(target.getUniqueId());

                plugin.getMessages().send(hunter, "bounty.claim-hunter",
                        "amount", String.format("%,.0f", bounty),
                        "player", target.getName());

                Bukkit.broadcast(plugin.getMessages().component("bounty.claim-broadcast",
                        "hunter", hunter.getName(),
                        "amount", String.format("%,.0f", bounty),
                        "player", target.getName()));
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao processar recompensa: " + e.getMessage());
        }
    }

    public void resetBounty(UUID uuid) {
        try {
            if (bounties.containsKey(uuid)) {
                bounties.remove(uuid);
                saveBounty(uuid, 0);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Erro ao resetar bounty", e);
        }
    }
}
