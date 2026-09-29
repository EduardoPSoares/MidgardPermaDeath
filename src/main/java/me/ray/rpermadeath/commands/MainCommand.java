package me.ray.rpermadeath.commands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.managers.MenuManager;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class MainCommand implements BasicCommand {
    private final RPermadeath plugin;
    private final MenuManager menuManager;

    public MainCommand(RPermadeath plugin, MenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        try {
            if (source.getSender() instanceof Player player) {
                menuManager.openMainMenu(player);
            } else {
                plugin.getMessages().send(source.getSender(), "general.players-only");
            }
        } catch (Exception e) {
            plugin.getMessages().send(source.getSender(), "general.command-error", "error", e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public String permission() {
        return "rpermadeath.use";
    }
}
