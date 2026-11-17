// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.runtime;

import java.util.function.Predicate;
import org.bukkit.*;
import org.bukkit.block.data.Rail;
import pl.medivalrpg.kolej.signal.PhysicalRailGuard;

/** Main-thread read-only rail and vehicle clearance probe. Every touched column must already be loaded. */
public final class BukkitRailProbe implements PhysicalRailGuard.Probe {
    private final World world;
    private final Predicate<Location> allowed;
    public BukkitRailProbe(World world,Predicate<Location> allowed){this.world=world;this.allowed=allowed;}
    public String inspect(PhysicalRailGuard.Rail expected) {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Kontrola toru wymaga głównego wątku");
        if(world==null)return "Świat toru niewczytany";
        var c=expected.cell();int height=expected.grade()?5:4;
        if(c.y()<=world.getMinHeight() || c.y()+height>=world.getMaxHeight())return "Tor poza wysokością świata";
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(!world.isChunkLoaded((c.x()+dx)>>4,(c.z()+dz)>>4))return "Chunk toru lub skrajni niewczytany";
        var block=world.getBlockAt(c.x(),c.y(),c.z());
        if(!world.getWorldBorder().isInside(block.getLocation()) || !allowed.test(block.getLocation()))return "Brak zgody na przejazd po torze";
        if(!(block.getBlockData() instanceof Rail rail))return "Brak fizycznej szyny";
        if(!rail.getShape().name().equals(expected.shape()))return "Niezgodne ułożenie fizycznej szyny";
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=1;dy<=height;dy++)
            if(!world.getBlockAt(c.x()+dx,c.y()+dy,c.z()+dz).isPassable())return "Przeszkoda w skrajni pociągu";
        return null;
    }
}
