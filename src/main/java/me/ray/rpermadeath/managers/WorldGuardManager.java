package me.ray.rpermadeath.managers;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

public class WorldGuardManager {

    private final boolean enabled;

    public WorldGuardManager(Plugin plugin) {
        this.enabled = plugin.getServer().getPluginManager().isPluginEnabled("WorldGuard");
    }

    public boolean isAllowed(Location location, List<String> allowedRegions) {
        if (!enabled || allowedRegions == null || allowedRegions.isEmpty()) {
            return true;
        }

        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            ApplicableRegionSet set = query.getApplicableRegions(BukkitAdapter.adapt(location));

            for (ProtectedRegion region : set) {
                if (allowedRegions.contains(region.getId())) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // Catch NoClassDefFoundError or other issues if WG is missing/broken
            return true; // Fail safe: allow movement if WG check fails
        }

        return false;
    }

    public boolean isInRegion(Location location, List<String> regions) {
        if (!enabled || regions == null || regions.isEmpty()) {
            return false;
        }

        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            ApplicableRegionSet set = query.getApplicableRegions(BukkitAdapter.adapt(location));

            for (ProtectedRegion region : set) {
                if (regions.contains(region.getId())) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return false;
        }

        return false;
    }

    public List<String> getAllRegions(org.bukkit.World world) {
        if (!enabled || world == null) {
            return new ArrayList<>();
        }

        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            com.sk89q.worldguard.protection.managers.RegionManager regions = container.get(BukkitAdapter.adapt(world));
            if (regions != null) {
                return new ArrayList<>(regions.getRegions().keySet());
            }
        } catch (Throwable t) {
            // Ignore
        }
        return new ArrayList<>();
    }
}
