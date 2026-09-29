package me.ray.rpermadeath.commands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.managers.DeathManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.UUID;

public class RessucitarCommand implements BasicCommand {

    private final RPermadeath plugin;
    private final DeathManager deathManager;

    public RessucitarCommand(RPermadeath plugin, DeathManager deathManager) {
        this.plugin = plugin;
        this.deathManager = deathManager;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        try {
            if (args.length < 1) {
                plugin.getMessages().send(source.getSender(), "commands.ressucitar.usage");
                return;
            }

            Player online = Bukkit.getPlayerExact(args[0]);
            UUID targetId = online != null
                    ? online.getUniqueId()
                    : Bukkit.getOfflinePlayer(args[0]).getUniqueId();

            if (!deathManager.isDead(targetId)) {
                plugin.getMessages().send(source.getSender(), "general.player-not-dead");
                return;
            }

            deathManager.revive(targetId);

            if (online != null) {
                plugin.getMessages().send(online, "commands.ressucitar.player-success");
            }

            plugin.getMessages().send(source.getSender(), "commands.ressucitar.admin-success", "player", args[0]);
        } catch (Exception e) {
            plugin.getMessages().send(source.getSender(), "general.command-error", "error", e.getMessage());
            e.printStackTrace();
        }
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
