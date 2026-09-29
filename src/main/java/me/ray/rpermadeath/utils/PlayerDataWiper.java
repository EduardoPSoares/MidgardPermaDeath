package me.ray.rpermadeath.utils;

import me.ray.rpermadeath.RPermadeath;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class PlayerDataWiper {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Set<UUID> PENDING_PERMANENT_WIPES = ConcurrentHashMap.newKeySet();

    private final RPermadeath plugin;

    public PlayerDataWiper(RPermadeath plugin) {
        this.plugin = plugin;
    }

    public void wipeData(UUID playerId, String playerName) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> wipeData(playerId, playerName));
            return;
        }

        String resolvedName = resolvePlayerName(playerId, playerName);
        Player onlinePlayer = Bukkit.getPlayer(playerId);

        if (onlinePlayer != null) {
            clearLivePlayerState(onlinePlayer);
        }

        runConsoleCommands(plugin.getConfig().getStringList("reset-commands"), playerId, resolvedName);

        if (onlinePlayer == null) {
            deleteVanillaPlayerData(playerId);
            deleteReplayBackupFile(playerId);
            deletePluginData(resolveLegacyPluginDataDirs(), playerId, resolvedName);
        }
    }

    public boolean wipePermanentData(UUID playerId, String playerName) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> wipePermanentData(playerId, playerName));
            return true;
        }

        if (!PENDING_PERMANENT_WIPES.add(playerId)) {
            return false;
        }

        String resolvedName = resolvePlayerName(playerId, playerName);
        preparePermanentReset(playerId);

        Player onlinePlayer = Bukkit.getPlayer(playerId);
        if (onlinePlayer != null) {
            if (plugin.getGhostManager() != null) {
                plugin.getGhostManager().removeGhost(onlinePlayer);
            }

            clearLivePlayerState(onlinePlayer);
            revokeActivePet(onlinePlayer.getName());
            onlinePlayer.kick(plugin.getMessages().component("reset.deleted-kick"));
        }

        waitForLogout(playerId, resolvedName);
        return true;
    }

    private void preparePermanentReset(UUID playerId) {
        if (plugin.getBountyManager() != null) {
            plugin.getBountyManager().resetBounty(playerId);
        }

        if (plugin.getDeathManager() != null && plugin.getDeathManager().isDead(playerId)) {
            plugin.getDeathManager().removeDeadPlayer(playerId);
        }

        if (plugin.getReplayManager() != null) {
            plugin.getReplayManager().deleteReplays(playerId);
        }
    }

    private void waitForLogout(UUID playerId, String playerName) {
        Player onlinePlayer = Bukkit.getPlayer(playerId);
        long intervalTicks = Math.max(1L, plugin.getConfig().getLong("permanent-reset.check-interval-ticks", 10L));
        long postLogoutDelay = Math.max(0L, plugin.getConfig().getLong("permanent-reset.post-logout-delay-ticks", 20L));

        if (onlinePlayer == null || !onlinePlayer.isOnline()) {
            schedulePermanentCleanup(playerId, playerName, postLogoutDelay);
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                Player current = Bukkit.getPlayer(playerId);
                if (current != null && current.isOnline()) {
                    return;
                }

                cancel();
                schedulePermanentCleanup(playerId, playerName, postLogoutDelay);
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);
    }

    private void schedulePermanentCleanup(UUID playerId, String playerName, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> finishPermanentCleanup(playerId, playerName), delayTicks);
    }

    private void finishPermanentCleanup(UUID playerId, String playerName) {
        Player onlinePlayer = Bukkit.getPlayer(playerId);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            waitForLogout(playerId, playerName);
            return;
        }

        try {
            runConsoleCommands(resolvePermanentResetCommands(), playerId, playerName);
        } catch (Exception e) {
            plugin.getLogger().warning("Erro ao executar comandos do reset permanente de " + playerName + ": " + e.getMessage());
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                clearCustomFishingOfflineData(playerId);
                deleteVanillaPlayerData(playerId);
                deleteReplayBackupFile(playerId);
                deletePluginData(resolvePermanentPluginDataDirs(), playerId, playerName);
                plugin.getLogger().info("Reset permanente concluido para " + playerName + " (" + playerId + ").");
            } catch (Exception e) {
                plugin.getLogger().warning("Erro no reset permanente de " + playerName + ": " + e.getMessage());
            } finally {
                PENDING_PERMANENT_WIPES.remove(playerId);
            }
        });
    }

    private void clearLivePlayerState(Player player) {
        player.closeInventory();
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setExtraContents(new ItemStack[player.getInventory().getExtraContents().length]);
        player.getEnderChest().clear();
        player.setTotalExperience(0);
        player.setLevel(0);
        player.setExp(0F);
        player.setFoodLevel(20);
        player.setSaturation(5F);
        player.setExhaustion(0F);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0F);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));

        if (player.getAttribute(Attribute.MAX_HEALTH) != null) {
            player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        }

        player.updateInventory();
    }

    private void runConsoleCommands(List<String> commands, UUID playerId, String playerName) {
        for (String rawCommand : commands) {
            if (rawCommand == null) {
                continue;
            }

            String command = rawCommand.trim();
            if (command.isEmpty()) {
                continue;
            }

            String parsedCommand = command
                    .replace("%player%", playerName)
                    .replace("%uuid%", playerId.toString());

            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand);
            } catch (Exception e) {
                plugin.getLogger().warning("Falha ao executar comando de reset: " + parsedCommand + " -> " + e.getMessage());
            }
        }
    }

    private List<String> resolvePermanentResetCommands() {
        List<String> commands = plugin.getConfig().getStringList("permanent-reset.commands");
        if (!commands.isEmpty()) {
            return commands;
        }
        return plugin.getConfig().getStringList("reset-commands");
    }

    private List<String> resolveLegacyPluginDataDirs() {
        return plugin.getConfig().getStringList("plugin-data-dirs");
    }

    private List<String> resolvePermanentPluginDataDirs() {
        List<String> dirs = plugin.getConfig().getStringList("permanent-reset.plugin-data-dirs");
        if (!dirs.isEmpty()) {
            return dirs;
        }
        return resolveLegacyPluginDataDirs();
    }

    private void revokeActivePet(String playerName) {
        Plugin mcPets = Bukkit.getPluginManager().getPlugin("MCPets");
        if (mcPets == null || !mcPets.isEnabled()) {
            return;
        }

        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mcpets revoke " + playerName);
        } catch (Exception e) {
            plugin.getLogger().warning("Nao foi possivel revogar o pet ativo de " + playerName + ": " + e.getMessage());
        }
    }

    private void clearCustomFishingOfflineData(UUID playerId) {
        Plugin customFishing = Bukkit.getPluginManager().getPlugin("CustomFishing");
        if (customFishing == null || !customFishing.isEnabled()) {
            return;
        }

        try {
            ClassLoader classLoader = customFishing.getClass().getClassLoader();
            Class<?> apiClass = classLoader.loadClass("net.momirealms.customfishing.api.BukkitCustomFishingPlugin");
            Class<?> storageClass = classLoader.loadClass("net.momirealms.customfishing.api.storage.StorageManager");
            Class<?> userDataClass = classLoader.loadClass("net.momirealms.customfishing.api.storage.user.UserData");

            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object storageManager = apiClass.getMethod("getStorageManager").invoke(api);

            Object loadFuture = storageClass.getMethod("getOfflineUserData", UUID.class, boolean.class)
                    .invoke(storageManager, playerId, true);

            Object loadResult = toCompletableFuture(loadFuture).get(15, TimeUnit.SECONDS);
            if (!(loadResult instanceof Optional<?> optional) || optional.isEmpty()) {
                return;
            }

            Object userData = optional.get();
            CompletableFuture<Boolean> saveCompletion = new CompletableFuture<>();

            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    Object holder = userDataClass.getMethod("holder").invoke(userData);
                    Object inventoryObject = holder.getClass().getMethod("getInventory").invoke(holder);
                    if (inventoryObject instanceof Inventory inventory) {
                        inventory.clear();
                    }

                    Object statistics = userDataClass.getMethod("statistics").invoke(userData);
                    statistics.getClass().getMethod("reset").invoke(statistics);

                    Object saveFuture = storageClass.getMethod("saveUserData", userDataClass, boolean.class)
                            .invoke(storageManager, userData, true);

                    toCompletableFuture(saveFuture).whenComplete((saved, throwable) -> {
                        if (throwable != null) {
                            saveCompletion.completeExceptionally(throwable);
                            return;
                        }
                        saveCompletion.complete(Boolean.TRUE.equals(saved));
                    });
                } catch (Exception e) {
                    saveCompletion.completeExceptionally(e);
                }
            });

            Boolean saved = saveCompletion.get(15, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(saved)) {
                plugin.getLogger().info("CustomFishing limpo para " + playerId + ".");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Falha ao limpar dados offline do CustomFishing para " + playerId + ": " + e.getMessage());
        }
    }

    private CompletableFuture<?> toCompletableFuture(Object futureObject) {
        if (futureObject instanceof CompletableFuture<?> completableFuture) {
            return completableFuture;
        }
        if (futureObject instanceof CompletionStage<?> completionStage) {
            return completionStage.toCompletableFuture();
        }
        throw new IllegalStateException("Objeto nao eh um CompletionStage: " + futureObject);
    }

    private void deleteVanillaPlayerData(UUID playerId) {
        String uuid = playerId.toString();
        Set<Path> filesToDelete = new LinkedHashSet<>();

        for (World world : Bukkit.getWorlds()) {
            Path worldPath = world.getWorldFolder().toPath();
            filesToDelete.add(worldPath.resolve("playerdata").resolve(uuid + ".dat"));
            filesToDelete.add(worldPath.resolve("playerdata").resolve(uuid + ".dat_old"));
            filesToDelete.add(worldPath.resolve("stats").resolve(uuid + ".json"));
            filesToDelete.add(worldPath.resolve("advancements").resolve(uuid + ".json"));
        }

        for (Path path : filesToDelete) {
            deleteIfExists(path);
        }
    }

    private void deleteReplayBackupFile(UUID playerId) {
        deleteIfExists(plugin.getDataFolder().toPath().resolve("userdata").resolve("replay_inv_" + playerId + ".yml"));
    }

    private void deletePluginData(List<String> relativeDirectories, UUID playerId, String playerName) {
        if (relativeDirectories.isEmpty()) {
            return;
        }

        Path pluginsRoot = plugin.getDataFolder().getParentFile().toPath().toAbsolutePath().normalize();
        String uuidLower = playerId.toString().toLowerCase(Locale.ROOT);
        String nameLower = playerName.toLowerCase(Locale.ROOT);

        for (String relativeDirectory : relativeDirectories) {
            if (relativeDirectory == null || relativeDirectory.isBlank()) {
                continue;
            }

            Path targetDirectory = pluginsRoot.resolve(relativeDirectory).normalize();
            if (!targetDirectory.startsWith(pluginsRoot)) {
                plugin.getLogger().warning("Diretorio ignorado no reset permanente por sair da pasta plugins: " + relativeDirectory);
                continue;
            }

            if (!Files.exists(targetDirectory)) {
                continue;
            }

            try (Stream<Path> stream = Files.walk(targetDirectory)) {
                List<Path> matches = stream
                        .filter(path -> !path.equals(targetDirectory))
                        .filter(path -> matchesPlayerPath(path, uuidLower, nameLower))
                        .sorted(Comparator.comparingInt((Path path) -> path.getNameCount()).reversed())
                        .toList();

                for (Path match : matches) {
                    deletePathRecursively(match);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Falha ao limpar diretorio de plugin " + relativeDirectory + ": " + e.getMessage());
            }
        }
    }

    private boolean matchesPlayerPath(Path path, String uuidLower, String nameLower) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        String baseName = stripExtension(fileName);

        return fileName.contains(uuidLower)
                || baseName.contains(uuidLower)
                || matchesPlayerName(fileName, nameLower)
                || matchesPlayerName(baseName, nameLower);
    }

    private boolean matchesPlayerName(String candidate, String playerName) {
        if (playerName.isBlank()) {
            return false;
        }

        return candidate.equals(playerName)
                || candidate.startsWith(playerName + ".")
                || candidate.startsWith(playerName + "-")
                || candidate.startsWith(playerName + "_")
                || candidate.endsWith("-" + playerName)
                || candidate.endsWith("_" + playerName);
    }

    private String stripExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) {
            return fileName;
        }
        return fileName.substring(0, lastDot);
    }

    private void deletePathRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(path)) {
            List<Path> paths = stream
                    .sorted(Comparator.comparingInt((Path current) -> current.getNameCount()).reversed())
                    .toList();

            for (Path current : paths) {
                deleteIfExists(current);
            }
        } catch (IOException e) {
            deleteIfExists(path);
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            plugin.getLogger().warning("Nao foi possivel deletar " + path + ": " + e.getMessage());
        }
    }

    private String resolvePlayerName(UUID playerId, String preferredName) {
        if (preferredName != null && !preferredName.isBlank()) {
            return preferredName;
        }

        if (plugin.getDeathManager() != null) {
            me.ray.rpermadeath.managers.DeathInfo info = plugin.getDeathManager().getDeathInfo(playerId);
            if (info != null && info.getPlayerName() != null && !info.getPlayerName().isBlank()) {
                return info.getPlayerName();
            }
        }

        Player onlinePlayer = Bukkit.getPlayer(playerId);
        if (onlinePlayer != null) {
            return onlinePlayer.getName();
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
        if (offlinePlayer.getName() != null && !offlinePlayer.getName().isBlank()) {
            return offlinePlayer.getName();
        }

        return playerId.toString();
    }
}
