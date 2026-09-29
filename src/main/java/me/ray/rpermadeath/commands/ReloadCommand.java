package me.ray.rpermadeath.commands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.ray.rpermadeath.RPermadeath;
import org.jetbrains.annotations.NotNull;

public class ReloadCommand implements BasicCommand {

    private final RPermadeath plugin;

    public ReloadCommand(RPermadeath plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        try {
            plugin.reloadPlugin();
            plugin.getMessages().send(source.getSender(), "commands.reload.success");
        } catch (Exception e) {
            plugin.getMessages().send(source.getSender(), "commands.reload.error", "error", e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public String permission() {
        return "rpermadeath.admin";
    }
}
