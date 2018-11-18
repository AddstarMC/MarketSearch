package au.com.addstar.marketsearch.PlotProviders;

import com.worldcretornica.plotme_core.Plot;
import com.worldcretornica.plotme_core.PlotMeCoreManager;
import com.worldcretornica.plotme_core.api.ILocation;
import com.worldcretornica.plotme_core.api.IWorld;
import com.worldcretornica.plotme_core.bukkit.PlotMe_CorePlugin;
import com.worldcretornica.plotme_core.bukkit.api.BukkitLocation;
import com.worldcretornica.plotme_core.bukkit.api.BukkitWorld;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.util.UUID;

/**
 * au.com.addstar.marketsearch.PlotProviders
 * Created for the Addstar MC for MarketSearch
 * Created by Narimm on 1/03/2018.
 */
public class PlotMePlotProvider implements PlotProvider{

    private PlotMeCoreManager PMCM;

    public PlotMePlotProvider(JavaPlugin plotMePlugin) {
        if(plotMePlugin instanceof PlotMe_CorePlugin) {
            PMCM = PlotMeCoreManager.getInstance();
        }
    }


    @Override
    public String getPlotOwner(Location location) {
        ILocation loc = new BukkitLocation(location);
        IWorld world = new BukkitWorld(location.getWorld());
        Plot p = PMCM.getPlotById(PMCM.getPlotId(loc), world);
        return p.getOwner();
    }
    
    @Override
    public void gotoPlot(Player target, Location location) {
        throw new NotImplementedException();
    }
    
}
