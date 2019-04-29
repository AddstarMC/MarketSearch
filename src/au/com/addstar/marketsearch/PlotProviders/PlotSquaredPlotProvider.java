package au.com.addstar.marketsearch.PlotProviders;


import com.github.intellectualsites.plotsquared.api.PlotAPI;
import com.github.intellectualsites.plotsquared.plot.PlotSquared;
import com.github.intellectualsites.plotsquared.plot.object.Plot;
import org.bukkit.Bukkit;

import com.github.intellectualsites.plotsquared.plot.object.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * au.com.addstar.marketsearch.PlotProviders
 * Created for the Addstar MC for MarketSearch
 * Created by Narimm on 1/03/2018.
 */
public class PlotSquaredPlotProvider implements PlotProvider {

    private final PlotAPI api;

    public PlotSquaredPlotProvider() {
        api = new PlotAPI();
    }

    @Override
    public String getPlotOwner(org.bukkit.Location location) {
        Plot plot = getPlot(location);
        if(plot.getOwners().size() == 1){
            UUID uuid = plot.getOwners().iterator().next();
                return Bukkit.getOfflinePlayer(uuid).getName();
        }
        return null;
    }

    private Plot getPlot(org.bukkit.Location location) {
        if (location == null || location.getWorld() == null)
            return null;
        Location location1 = new Location(
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                location.getYaw(),
                location.getPitch());
        return location1.getPlot();
    }

    public void gotoPlot(Player player, org.bukkit.Location loc) {
        Plot plot = getPlot(loc);
        Location location = plot.getHome();
        org.bukkit.Location tpLoc = new org.bukkit.Location(Bukkit.getWorld(location.getWorld()), location.getX(), location.getY(), location.getZ());
        player.teleport(tpLoc);
    }
}
