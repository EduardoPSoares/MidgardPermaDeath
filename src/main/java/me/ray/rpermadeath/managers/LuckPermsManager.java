package me.ray.rpermadeath.managers;

import me.ray.rpermadeath.RPermadeath;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;

public class LuckPermsManager {

    private final RPermadeath plugin;
    private LuckPerms luckPerms;

    public LuckPermsManager(RPermadeath plugin) {
        this.plugin = plugin;
        try {
            this.luckPerms = LuckPermsProvider.get();
        } catch (Throwable e) {
            plugin.getLogger().warning("LuckPerms API nao encontrada: " + e.getMessage());
            this.luckPerms = null;
        }
    }

    public boolean isAvailable() {
        return luckPerms != null;
    }

    public boolean hasConfiguredCriminalGroup(Player player) {
        String group = plugin.getConfig().getString("luckperms.group", "criminoso");
        return hasGroup(player, group);
    }

    public boolean hasGroup(Player player, String group) {
        if (player == null || group == null || group.isBlank()) {
            return false;
        }

        if (luckPerms != null) {
            try {
                User user = luckPerms.getUserManager().getUser(player.getUniqueId());
                if (user != null) {
                    QueryOptions queryOptions = luckPerms.getContextManager().getQueryOptions(player);

                    boolean hasGroup = user.getInheritedGroups(queryOptions).stream()
                            .anyMatch(inheritedGroup -> inheritedGroup.getName().equalsIgnoreCase(group));
                    if (hasGroup) {
                        return true;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Falha ao verificar grupo LuckPerms de " + player.getName() + ": " + e.getMessage());
            }
        }

        return player.hasPermission("group." + group.toLowerCase(Locale.ROOT));
    }

    public void grantTemporaryGroup(Player killer) {
        String group = plugin.getConfig().getString("luckperms.group", "assassino");
        long horas = plugin.getConfig().getLong("luckperms.duration-hours", 24L);

        if (luckPerms != null) {
            try {
                InheritanceNode node = InheritanceNode.builder(group)
                        .expiry(Duration.ofHours(horas))
                        .build();
                luckPerms.getUserManager().modifyUser(killer.getUniqueId(), user ->
                        user.data().add(node));
                plugin.getLogger().info("Tag '" + group + "' aplicada a " + killer.getName() + " por " + horas + "h via API.");
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("Falha ao aplicar grupo via API do LuckPerms, tentando via comando: " + e.getMessage());
            }
        }

        // Fallback para comando (quando API não está disponível ou LuckPerms Velocity)
        try {
            String cmd = String.format("lpv user %s parent addtemp %s %dh", killer.getName(), group, horas);
            plugin.getLogger().info("Aplicando tag via comando: " + cmd);
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao executar comando LuckPerms: " + e.getMessage());
        }
    }

    public void addPermission(Player player, String permission) {
        if (luckPerms == null) return;
        try {
            luckPerms.getUserManager().modifyUser(player.getUniqueId(), user ->
                    user.data().add(net.luckperms.api.node.types.PermissionNode.builder(permission).build()));
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao adicionar permissão LuckPerms: " + e.getMessage());
        }
    }

    public void removePermission(Player player, String permission) {
        if (luckPerms == null) return;
        try {
            luckPerms.getUserManager().modifyUser(player.getUniqueId(), user ->
                    user.data().remove(net.luckperms.api.node.types.PermissionNode.builder(permission).build()));
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao remover permissão LuckPerms: " + e.getMessage());
        }
    }

    public void removePermission(Player player, String permission, boolean value) {
        if (luckPerms == null) return;
        try {
            luckPerms.getUserManager().modifyUser(player.getUniqueId(), user ->
                    user.data().remove(net.luckperms.api.node.types.PermissionNode.builder(permission).value(value).build()));
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao remover permissão LuckPerms: " + e.getMessage());
        }
    }

    public void addPermission(Player player, String permission, boolean value) {
        if (luckPerms == null) return;
        try {
            luckPerms.getUserManager().modifyUser(player.getUniqueId(), user ->
                    user.data().add(net.luckperms.api.node.types.PermissionNode.builder(permission).value(value).build()));
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao adicionar permissão LuckPerms: " + e.getMessage());
        }
    }

    public void setPrefix(Player player, String prefix, int priority) {
        if (luckPerms == null) return;
        try {
            luckPerms.getUserManager().modifyUser(player.getUniqueId(), user ->
                    user.data().add(net.luckperms.api.node.types.PrefixNode.builder(prefix, priority).build()));
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao definir prefixo LuckPerms: " + e.getMessage());
        }
    }

    public void removePrefix(Player player, String prefix, int priority) {
        if (luckPerms == null) return;
        try {
            luckPerms.getUserManager().modifyUser(player.getUniqueId(), user ->
                    user.data().remove(net.luckperms.api.node.types.PrefixNode.builder(prefix, priority).build()));
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao remover prefixo LuckPerms: " + e.getMessage());
        }
    }

    public void setSuffix(Player player, String suffix, int priority) {
        if (luckPerms == null) return;
        try {
            luckPerms.getUserManager().modifyUser(player.getUniqueId(), user ->
                    user.data().add(net.luckperms.api.node.types.SuffixNode.builder(suffix, priority).build()));
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao definir sufixo LuckPerms: " + e.getMessage());
        }
    }

    public void removeSuffix(Player player, String suffix, int priority) {
        if (luckPerms == null) return;
        try {
            luckPerms.getUserManager().modifyUser(player.getUniqueId(), user ->
                    user.data().remove(net.luckperms.api.node.types.SuffixNode.builder(suffix, priority).build()));
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao remover sufixo LuckPerms: " + e.getMessage());
        }
    }
}
