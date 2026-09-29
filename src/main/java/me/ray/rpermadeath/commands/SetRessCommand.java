package me.ray.rpermadeath.commands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.managers.DeathManager;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class SetRessCommand implements BasicCommand {

    private final RPermadeath plugin;
    private final DeathManager deathManager;

    public SetRessCommand(RPermadeath plugin, DeathManager deathManager) {
        this.plugin = plugin;
        this.deathManager = deathManager;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        try {
            if (!(source.getSender() instanceof Player player)) {
                plugin.getMessages().send(source.getSender(), "general.players-only");
                return;
            }

            deathManager.setRessSpawn(player.getLocation());
            plugin.getMessages().send(source.getSender(), "commands.set-respawn.success");
        } catch (Exception e) {
            plugin.getMessages().send(source.getSender(), "general.command-error", "error", e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public String permission() {
        return "rpermadeath.admin";
    }
}
