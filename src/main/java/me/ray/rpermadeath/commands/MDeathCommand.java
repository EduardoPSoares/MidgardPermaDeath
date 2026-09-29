package me.ray.rpermadeath.commands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.ray.rpermadeath.RPermadeath;
import me.ray.rpermadeath.managers.BountyManager;
import me.ray.rpermadeath.managers.DeathManager;
import me.ray.rpermadeath.managers.MenuManager;
import me.ray.rpermadeath.replay.ReplayListener;
import me.ray.rpermadeath.replay.ReplayManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class MDeathCommand implements BasicCommand, CommandExecutor, TabCompleter {

    private final RPermadeath plugin;
    private final DeathManager deathManager;
    private final MenuManager menuManager;
    private final ReplayManager replayManager;
    private final ReplayListener replayListener;
    private final BountyManager bountyManager;

    public MDeathCommand(RPermadeath plugin, DeathManager deathManager, MenuManager menuManager, ReplayManager replayManager, ReplayListener replayListener, BountyManager bountyManager) {
        this.plugin = plugin;
        this.deathManager = deathManager;
        this.menuManager = menuManager;
        this.replayManager = replayManager;
        this.replayListener = replayListener;
        this.bountyManager = bountyManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        handleCommand(sender, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return new ArrayList<>(getSuggestions(sender, args));
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        handleCommand(source.getSender(), args);
    }

    private void handleCommand(CommandSender sender, String[] args) {
        try {
            if (args.length == 0) {
                if (sender instanceof Player player) {
                    menuManager.openMainMenu(player);
                } else {
                    sendHelp(sender);
                }
                return;
            }

            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "menu" -> {
                    if (sender instanceof Player player) {
                        menuManager.openMainMenu(player);
                    } else {
                        send(sender, "general.players-only");
                    }
                }

                case "toggle" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    boolean newGlobalState = !deathManager.isGlobalEnabled();
                    deathManager.setGlobalEnabled(newGlobalState);
                    send(sender, newGlobalState ? "commands.toggle.enabled" : "commands.toggle.disabled");
                }

                case "region" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    if (args.length < 2) {
                        send(sender, "commands.region.usage");
                        return;
                    }

                    String action = args[1].toLowerCase();
                    if (action.equals("list")) {
                        send(sender, "commands.region.list-header");
                        for (String region : deathManager.getDisabledRegions()) {
                            send(sender, "commands.region.list-entry", "region", region);
                        }
                    } else if (action.equals("add")) {
                        if (args.length < 3) {
                            send(sender, "commands.region.add-usage");
                            return;
                        }
                        String region = args[2];
                        deathManager.addDisabledRegion(region);
                        send(sender, "commands.region.added", "region", region);
                    } else if (action.equals("remove")) {
                        if (args.length < 3) {
                            send(sender, "commands.region.remove-usage");
                            return;
                        }
                        String region = args[2];
                        deathManager.removeDisabledRegion(region);
                        send(sender, "commands.region.removed", "region", region);
                    } else {
                        send(sender, "commands.region.usage");
                    }
                }

                case "setmorte" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    if (sender instanceof Player player) {
                        deathManager.setDeathSpawn(player.getLocation());
                        send(sender, "commands.set-death.success");
                    } else {
                        send(sender, "general.players-only");
                    }
                }

                case "setress" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    if (sender instanceof Player player) {
                        deathManager.setRessSpawn(player.getLocation());
                        send(sender, "commands.set-respawn.success");
                    } else {
                        send(sender, "general.players-only");
                    }
                }

                case "setpurgatory", "setpurgatorio" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    if (sender instanceof Player player) {
                        deathManager.setPurgatorySpawn(player.getLocation());
                        send(sender, "commands.set-purgatory.success");
                    } else {
                        send(sender, "general.players-only");
                    }
                }

                case "reset" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    if (args.length < 2) {
                        send(sender, "commands.reset.usage");
                        return;
                    }

                    String targetName = args[1];
                    OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

                    send(sender, "commands.reset.starting", "player", targetName);

                    boolean started = plugin.startPermanentReset(
                            target.getUniqueId(),
                            targetName,
                            plugin.getMessages().legacy("reset.permanent-kick")
                    );
                    if (started) {
                        send(sender, "commands.reset.started", "player", targetName);
                    } else {
                        send(sender, "commands.reset.already-running", "player", targetName);
                    }
                }

                case "ressucitar" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    if (args.length < 2) {
                        send(sender, "commands.ressucitar.usage");
                        return;
                    }

                    String targetName = args[1];
                    OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

                    if (replayListener.isBeingReplayed(target.getUniqueId())) {
                        send(sender, "commands.ressucitar.blocked-by-replay");
                        return;
                    }

                    if (deathManager.isDead(target.getUniqueId())) {
                        deathManager.revive(target.getUniqueId());
                        send(sender, "commands.ressucitar.success", "player", targetName);
                    } else {
                        send(sender, "general.player-not-dead");
                    }
                }

                case "ungod", "removerimortalidade" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    if (args.length < 2) {
                        send(sender, "commands.ungod.usage");
                        return;
                    }

                    String ungodTargetName = args[1];
                    if (ungodTargetName.equalsIgnoreCase("all")) {
                        int count = 0;
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            if (player.getGameMode() != org.bukkit.GameMode.CREATIVE
                                    && player.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                                if (plugin.getGhostManager().isGhost(player)) continue;
                                if (player.isInvulnerable()) {
                                    player.setInvulnerable(false);
                                    count++;
                                }
                            }
                        }
                        send(sender, "commands.ungod.all-success", "count", count);
                    } else {
                        Player player = Bukkit.getPlayer(ungodTargetName);
                        if (player == null) {
                            send(sender, "general.player-not-found");
                            return;
                        }
                        player.setInvulnerable(false);
                        send(sender, "commands.ungod.player-success", "player", player.getName());
                    }
                }

                case "unghost" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    if (args.length < 2) {
                        send(sender, "commands.unghost.usage");
                        return;
                    }

                    String targetName = args[1];
                    Player target = Bukkit.getPlayer(targetName);
                    if (target == null) {
                        send(sender, "general.player-not-found-or-offline");
                        return;
                    }

                    if (plugin.getGhostManager().isGhost(target)) {
                        deathManager.revive(target.getUniqueId());
                        target.kick(plugin.getMessages().component("ghost.removed-kick"));
                        send(sender, "commands.unghost.success", "player", target.getName());
                    } else {
                        send(sender, "commands.unghost.not-ghost");
                    }
                }

                case "stats" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    sender.sendMessage(replayManager.getPerformanceStats());
                }

                case "replaytoggle" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    boolean newState = !replayManager.isRecordingEnabled();
                    replayManager.setRecordingEnabled(newState);
                    send(sender, newState ? "commands.replay-toggle.enabled" : "commands.replay-toggle.disabled");
                }

                case "totem", "giveitem" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    if (sender instanceof Player player) {
                        giveRitualItem(player);
                        send(sender, "commands.giveitem.success");
                    } else {
                        send(sender, "general.players-only");
                    }
                }

                case "infomorte" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    if (args.length < 2) {
                        send(sender, "commands.infomorte.usage");
                        return;
                    }

                    String targetName = args[1];
                    OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

                    double bounty = bountyManager.getBounty(target.getUniqueId());
                    if (bounty > 0) {
                        send(sender, "commands.infomorte.bounty", "amount", String.format("%,.0f", bounty));
                    }

                    if (deathManager.isDead(target.getUniqueId())) {
                        send(sender, "commands.infomorte.header", "player", targetName);
                        send(sender, "commands.infomorte.date", "date", deathManager.getDeathDate(target.getUniqueId()));
                        send(sender, "commands.infomorte.cause", "cause", deathManager.getDeathCause(target.getUniqueId()));
                        send(sender, "commands.infomorte.location", "location", deathManager.getDeathLocationString(target.getUniqueId()));
                        send(sender, replayManager.hasRecording(target.getUniqueId())
                                ? "commands.infomorte.replay-available"
                                : "commands.infomorte.replay-unavailable");
                    } else {
                        send(sender, "general.player-not-dead");
                    }
                }

                case "replay" -> {
                    if (!sender.hasPermission("rpermadeath.replay.watch")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    if (args.length < 2) {
                        send(sender, "commands.replay.usage");
                        return;
                    }
                    if (!(sender instanceof Player player)) {
                        send(sender, "commands.replay.players-only");
                        return;
                    }

                    String replayTargetName = args[1];
                    OfflinePlayer replayTarget = Bukkit.getOfflinePlayer(replayTargetName);

                    if (!replayManager.hasRecording(replayTarget.getUniqueId())) {
                        send(sender, "commands.replay.not-found");
                        return;
                    }

                    send(sender, "commands.replay.loading");
                    replayManager.loadRecording(replayTarget.getUniqueId(), recording -> {
                        if (recording == null) {
                            send(sender, "commands.replay.load-error");
                            return;
                        }
                        plugin.getReplayListener().startReplay(player, recording);
                    });
                }

                case "bounty", "procurado" -> {
                    if (args.length < 2) {
                        send(sender, "commands.bounty.usage");
                        return;
                    }

                    String targetName = args[1];
                    OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                    double value = bountyManager.getBounty(target.getUniqueId());
                    if (value > 0) {
                        send(sender, "commands.bounty.has", "player", targetName, "amount", String.format("%,.0f", value));
                    } else {
                        send(sender, "commands.bounty.none", "player", targetName);
                    }
                }

                case "forcerevive", "forcarreviver" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    if (args.length < 2) {
                        send(sender, "commands.forcerevive.usage");
                        return;
                    }

                    String targetName = args[1];
                    OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                    UUID targetId = target.getUniqueId();

                    deathManager.revive(targetId);

                    Player online = Bukkit.getPlayer(targetId);
                    if (online != null) {
                        if (plugin.getGhostManager() != null) {
                            plugin.getGhostManager().removeGhost(online);
                        }
                        online.setGameMode(org.bukkit.GameMode.SURVIVAL);
                        online.setInvulnerable(false);
                    }

                    send(sender, "commands.forcerevive.success", "player", targetName);
                }

                case "forcereviveall", "forcarrevivertodos" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }

                    Set<UUID> allDead = deathManager.getDeadPlayers();
                    if (allDead.isEmpty()) {
                        send(sender, "commands.forcereviveall.none");
                        break;
                    }

                    int revived = 0;
                    for (UUID deadId : allDead) {
                        deathManager.revive(deadId);

                        Player online = Bukkit.getPlayer(deadId);
                        if (online != null) {
                            if (plugin.getGhostManager() != null) {
                                plugin.getGhostManager().removeGhost(online);
                            }
                            online.setGameMode(org.bukkit.GameMode.SURVIVAL);
                            online.setInvulnerable(false);
                        }
                        revived++;
                    }
                    send(sender, "commands.forcereviveall.success", "count", revived);
                }

                case "reload" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    plugin.reloadPlugin();
                    send(sender, "commands.reload.success");
                }

                case "performance", "perf" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    sender.sendMessage(replayManager.getPerformanceStats());
                }

                case "dbreconnect" -> {
                    if (!sender.hasPermission("rpermadeath.admin")) {
                        send(sender, "general.no-permission");
                        return;
                    }
                    send(sender, "commands.dbreconnect.starting");
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        boolean success = plugin.getDatabaseManager().reconnect();
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (success) {
                                send(sender, "commands.dbreconnect.success");
                            } else {
                                send(sender, "commands.dbreconnect.failed");
                            }
                        });
                    });
                }

                default -> sendHelp(sender);
            }
        } catch (Exception e) {
            send(sender, "general.command-error", "error", e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendHelp(CommandSender sender) {
        sendLines(sender, "commands.help.common");
        if (sender.hasPermission("rpermadeath.admin")) {
            sendLines(sender, "commands.help.admin");
        }
        if (sender.hasPermission("rpermadeath.replay.watch")) {
            sendLines(sender, "commands.help.replay");
        }
        sendLines(sender, "commands.help.footer");
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        return getSuggestions(source.getSender(), args);
    }

    private Collection<String> getSuggestions(CommandSender sender, String[] args) {
        if (args.length == 0 || args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("menu");
            suggestions.add("help");
            suggestions.add("bounty");

            if (sender.hasPermission("rpermadeath.admin")) {
                suggestions.add("setmorte");
                suggestions.add("setress");
                suggestions.add("setpurgatory");
                suggestions.add("ressucitar");
                suggestions.add("reset");
                suggestions.add("infomorte");
                suggestions.add("reload");
                suggestions.add("stats");
                suggestions.add("performance");
                suggestions.add("replaytoggle");
                suggestions.add("giveitem");
                suggestions.add("toggle");
                suggestions.add("region");
                suggestions.add("ungod");
                suggestions.add("unghost");
                suggestions.add("forcerevive");
                suggestions.add("forcereviveall");
                suggestions.add("dbreconnect");
            }
            if (sender.hasPermission("rpermadeath.replay.watch")) {
                suggestions.add("replay");
            }
            return suggestions;
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("reset")) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            }
            if (sub.equals("region")) {
                return List.of("add", "remove", "list");
            }
            if (sub.equals("ressucitar") || sub.equals("infomorte")) {
                return deathManager.getDeadPlayerNames();
            }
            if (sub.equals("replay")) {
                List<String> names = replayManager.getReplayPlayerNames();
                if (names.isEmpty()) {
                    return List.of("Nenhum-Replay");
                }
                return names;
            }
            if (sub.equals("bounty")) {
                return bountyManager.getBountyPlayerNames();
            }
            if (sub.equals("giveitem")) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            }
            if (sub.equals("ungod")) {
                List<String> suggestions = new ArrayList<>();
                suggestions.add("all");
                suggestions.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
                return suggestions;
            }
            if (sub.equals("unghost")) {
                return Bukkit.getOnlinePlayers().stream()
                        .filter(p -> plugin.getGhostManager().isGhost(p))
                        .map(Player::getName)
                        .collect(Collectors.toList());
            }
            if (sub.equals("forcerevive") || sub.equals("forcarreviver")) {
                List<String> forceReviveSuggestions = new ArrayList<>(deathManager.getDeadPlayerNames());
                Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> !forceReviveSuggestions.contains(name))
                        .forEach(forceReviveSuggestions::add);
                return forceReviveSuggestions;
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("region")) {
            if (args[1].equalsIgnoreCase("remove")) {
                return deathManager.getDisabledRegions();
            }
            if (args[1].equalsIgnoreCase("add") && sender instanceof Player player && plugin.getWorldGuardManager() != null) {
                return plugin.getWorldGuardManager().getAllRegions(player.getWorld());
            }
        }

        return List.of();
    }

    @Override
    public String permission() {
        return "rpermadeath.use";
    }

    private void giveRitualItem(Player player) {
        String materialName = plugin.getConfig().getString("ritual.item", "NETHER_STAR");
        org.bukkit.Material material = org.bukkit.Material.getMaterial(materialName);
        if (material == null) {
            send(player, "commands.giveitem.invalid-material", "material", materialName);
            return;
        }

        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = plugin.getConfig().getString("ritual.item-name", "&6&lTotem de Odin");
            meta.displayName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(name.replace("&", "§")));
            item.setItemMeta(meta);
        }

        player.getInventory().addItem(item);
    }

    private void send(CommandSender sender, String path, Object... placeholders) {
        plugin.getMessages().send(sender, path, placeholders);
    }

    private void sendLines(CommandSender sender, String path, Object... placeholders) {
        for (String line : plugin.getMessages().legacyList(path, placeholders)) {
            sender.sendMessage(line);
        }
    }
}
