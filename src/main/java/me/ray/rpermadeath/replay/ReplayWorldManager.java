package me.ray.rpermadeath.replay;

import me.ray.rpermadeath.RPermadeath;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ReplayWorldManager {

    private final RPermadeath plugin;
    private final File worldsContainer;
    private final Map<String, BukkitTask> activeCopyTasks;

    public ReplayWorldManager(RPermadeath plugin) {
        this.plugin = plugin;
        this.worldsContainer = plugin.getServer().getWorldContainer();
        this.activeCopyTasks = new ConcurrentHashMap<>();
    }

    public CompletableFuture<ReplayWorldInfo> createReplayWorld(Location origin, int radius) {
        CompletableFuture<ReplayWorldInfo> future = new CompletableFuture<>();
        String worldName = "replay_" + UUID.randomUUID().toString().substring(0, 8);

        if (origin == null || origin.getWorld() == null) {
            future.completeExceptionally(new IllegalArgumentException("Localizacao de origem invalida para criar mundo de replay"));
            return future;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                WorldCreator creator = new WorldCreator(worldName);
                creator.generator(new VoidChunkGenerator());
                creator.generateStructures(false);
                creator.type(WorldType.FLAT);

                World world = creator.createWorld();
                if (world == null) {
                    future.completeExceptionally(new RuntimeException("Falha ao criar mundo de replay"));
                    return;
                }

                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
                world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
                world.setGameRule(GameRule.SPECTATORS_GENERATE_CHUNKS, false);
                world.setTime(origin.getWorld().getTime());

                Location targetCenter = new Location(world, 0, 100, 0);
                loadChunks(origin, radius);

                copyArea(origin, targetCenter, radius, () -> {
                    future.complete(new ReplayWorldInfo(world, targetCenter, origin));
                }, error -> {
                    deleteReplayWorld(world);
                    future.completeExceptionally(error);
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private void loadChunks(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        int minX = (center.getBlockX() - radius) >> 4;
        int maxX = (center.getBlockX() + radius) >> 4;
        int minZ = (center.getBlockZ() - radius) >> 4;
        int maxZ = (center.getBlockZ() + radius) >> 4;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                world.getChunkAt(x, z);
            }
        }
    }

    private void copyArea(Location sourceCenter, Location targetCenter, int radius, Runnable onComplete, java.util.function.Consumer<Throwable> onError) {
        try {
            World sourceWorld = sourceCenter.getWorld();
            World targetWorld = targetCenter.getWorld();

            if (sourceWorld == null || targetWorld == null) {
                onError.accept(new IllegalStateException("Mundo de origem ou destino nulo"));
                return;
            }

            int minX = sourceCenter.getBlockX() - radius;
            int maxX = sourceCenter.getBlockX() + radius;
            int minZ = sourceCenter.getBlockZ() - radius;
            int maxZ = sourceCenter.getBlockZ() + radius;
            int minY = Math.max(sourceWorld.getMinHeight(), sourceCenter.getBlockY() - 40);
            int maxY = Math.min(sourceWorld.getMaxHeight(), sourceCenter.getBlockY() + 40);

            int offsetX = targetCenter.getBlockX() - sourceCenter.getBlockX();
            int offsetY = targetCenter.getBlockY() - sourceCenter.getBlockY();
            int offsetZ = targetCenter.getBlockZ() - sourceCenter.getBlockZ();
            String taskKey = targetWorld.getName();

            BukkitTask existingTask = activeCopyTasks.remove(taskKey);
            if (existingTask != null && !existingTask.isCancelled()) {
                existingTask.cancel();
            }

            BukkitTask copyTask = new BukkitRunnable() {
                int currentX = minX;
                final int batchSize = 32;

                @Override
                public void run() {
                    try {
                        long startTime = System.currentTimeMillis();

                        while (currentX <= maxX) {
                            for (int x = currentX; x < currentX + batchSize && x <= maxX; x++) {
                                for (int z = minZ; z <= maxZ; z++) {
                                    for (int y = minY; y <= maxY; y++) {
                                        Block sourceBlock = sourceWorld.getBlockAt(x, y, z);
                                        if (sourceBlock.getType() != Material.AIR
                                                && sourceBlock.getType() != Material.CAVE_AIR
                                                && sourceBlock.getType() != Material.VOID_AIR) {
                                            BlockData data = sourceBlock.getBlockData();
                                            Block targetBlock = targetWorld.getBlockAt(x + offsetX, y + offsetY, z + offsetZ);
                                            targetBlock.setType(sourceBlock.getType(), false);
                                            targetBlock.setBlockData(data, false);
                                        }
                                    }
                                }
                            }
                            currentX += batchSize;

                            if (System.currentTimeMillis() - startTime > 40) {
                                return;
                            }
                        }

                        cancel();
                        activeCopyTasks.remove(taskKey);
                        onComplete.run();
                    } catch (Exception e) {
                        plugin.getLogger().severe("Erro ao copiar area do mundo: " + e.getMessage());
                        e.printStackTrace();
                        cancel();
                        activeCopyTasks.remove(taskKey);
                        onError.accept(e);
                    }
                }
            }.runTaskTimer(plugin, 0, 1);

            activeCopyTasks.put(taskKey, copyTask);
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    public void deleteReplayWorld(World world) {
        if (world == null) {
            return;
        }

        BukkitTask copyTask = activeCopyTasks.remove(world.getName());
        if (copyTask != null && !copyTask.isCancelled()) {
            copyTask.cancel();
        }

        try {
            String worldName = world.getName();

            for (org.bukkit.entity.Player p : world.getPlayers()) {
                p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }

            Bukkit.unloadWorld(world, false);

            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        deleteDirectory(new File(worldsContainer, worldName));
                    } catch (Exception e) {
                        plugin.getLogger().severe("Erro ao deletar diretorio do mundo " + worldName + ": " + e.getMessage());
                    }
                }
            }.runTaskAsynchronously(plugin);
        } catch (Exception e) {
            plugin.getLogger().severe("Erro ao deletar mundo de replay: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean deleteDirectory(File path) {
        if (path.exists()) {
            File[] files = path.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
        }
        return path.delete();
    }

    public static class ReplayWorldInfo {
        public final World world;
        public final Location center;
        public final Location originalCenter;
        public final double offsetX;
        public final double offsetY;
        public final double offsetZ;

        public ReplayWorldInfo(World world, Location center, Location originalCenter) {
            this.world = world;
            this.center = center;
            this.originalCenter = originalCenter;
            this.offsetX = center.getX() - originalCenter.getX();
            this.offsetY = center.getY() - originalCenter.getY();
            this.offsetZ = center.getZ() - originalCenter.getZ();
        }

        public Location transform(Location original) {
            return new Location(world, original.getX() + offsetX, original.getY() + offsetY, original.getZ() + offsetZ, original.getYaw(), original.getPitch());
        }

        public double transformX(double x) {
            return x + offsetX;
        }

        public double transformY(double y) {
            return y + offsetY;
        }

        public double transformZ(double z) {
            return z + offsetZ;
        }
    }
}
