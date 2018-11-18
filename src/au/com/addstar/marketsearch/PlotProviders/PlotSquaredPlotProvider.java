package au.com.addstar.marketsearch.PlotProviders;

import com.intellectualcrafters.plot.api.PlotAPI;
import com.intellectualcrafters.plot.object.Plot;
import com.intellectualcrafters.plot.object.PlotPlayer;
import com.plotsquared.bukkit.util.BukkitUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
    public String getPlotOwner(Location location) {
        Plot plot =  api.getPlot(location);
        if(plot.getOwners().size() == 1){
            UUID uuid = plot.getOwners().iterator().next();
                return Bukkit.getOfflinePlayer(uuid).getName();
        }
        return null;
    }
    
    public void gotoPlot(Player player, Location loc){
        Plot plot = api.getPlot(loc);
        player.teleport(BukkitUtil.getLocation(plot.getHome()));
    }
}
