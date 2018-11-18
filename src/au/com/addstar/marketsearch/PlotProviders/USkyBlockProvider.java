package au.com.addstar.marketsearch.PlotProviders;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import us.talabrek.ultimateskyblock.api.IslandInfo;
import us.talabrek.ultimateskyblock.api.uSkyBlockAPI;

/**
 * Created for the AddstarMC Project. Created by Narimm on 19/11/2018.
 */
public class USkyBlockProvider implements PlotProvider{
    
    private uSkyBlockAPI provider;
    
    public USkyBlockProvider(uSkyBlockAPI provider) {
        this.provider = provider;
    }
    
    @Override
    public String getPlotOwner(Location location) {
        IslandInfo info = provider.getIslandInfo(location);
        return info.getLeader();
    }
    
    @Override
    public void gotoPlot(Player target, Location location) {
        IslandInfo info = provider.getIslandInfo(location);
        target.teleport(info.getWarpLocation());
    }
}
