package au.com.addstar.marketsearch.PlotProviders;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * au.com.addstar.marketsearch.PlotProviders
 * Created for the Addstar MC for MarketSearch
 * Created by Narimm on 1/03/2018.
 */
public interface PlotProvider {

    String getPlotOwner(Location location);
    
    void gotoPlot(Player target, Location location);
}