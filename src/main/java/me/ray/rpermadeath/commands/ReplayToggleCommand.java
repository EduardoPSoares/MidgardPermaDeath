package me.ray.rpermadeath.commands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.replay.ReplayManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class ReplayToggleCommand implements BasicCommand, CommandExecutor {
    private final RPermadeath plugin;
    private final ReplayManager replayManager;

    public ReplayToggleCommand(RPermadeath plugin, ReplayManager replayManager) {
        this.plugin = plugin;
        this.replayManager = replayManager;
    }

    @Override
    public String permission() {
        return "rpermadeath.admin";
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        handleCommand(sender);
        return true;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        handleCommand(source.getSender());
    }

    private void handleCommand(CommandSender sender) {
        try {
            if (!sender.hasPermission("rpermadeath.admin")) {
                plugin.getMessages().send(sender, "general.no-permission");
                return;
            }

            boolean newState = !replayManager.isRecordingEnabled();
            replayManager.setRecordingEnabled(newState);

            if (newState) {
                plugin.getMessages().send(sender, "commands.replay-toggle.enabled");
            } else {
                plugin.getMessages().send(sender, "commands.replay-toggle.disabled");
            }
        } catch (Exception e) {
            plugin.getMessages().send(sender, "general.command-error", "error", e.getMessage());
            e.printStackTrace();
        }
    }
}
