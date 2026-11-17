// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.hook;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.template.BlockStates;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.FoundationMode;

/**
 * Everything that touches the WorldEdit API (softdepend). Only used after {@link Hooks#worldEdit()} returned true,
 * so the plugin loads fine without WorldEdit.
 */
public final class WorldEditHook {

    private WorldEditHook() {
    }

    /** The player's current cuboid selection, or null when there is none / it is incomplete. */
    public static BlockBox selection(final Player player) {
        final LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
        final com.sk89q.worldedit.world.World world = session.getSelectionWorld();
        if (world == null) {
            return null;
        }
        try {
            final Region region = session.getSelection(world);
            final BlockVector3 min = region.getMinimumPoint();
            final BlockVector3 max = region.getMaximumPoint();
            return BlockBox.of(world.getName(), min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
        } catch (final IncompleteRegionException e) {
            return null;
        }
    }

    /**
     * Loads a {@code .schem}/{@code .schematic} file as a template. Optional sidecar {@code <file>.yml}
     * ({@code chata.schem.yml}) with the same keys as YAML templates plus {@code front: south|north|east|west}
     * (which side of the schematic holds the door; default south). Without {@code ground-layer} the layer under the
     * copy origin (where you stood when copying) is the floor. Air is cleared, structure voids keep the terrain.
     */
    public static BuildingTemplate loadSchematic(final File file, final long defaultCost) throws IOException {
        final ClipboardFormat format = ClipboardFormats.findByPath(file.toPath());
        if (format == null) {
            throw new IOException("nieznany format schematu");
        }
        final Clipboard clipboard;
        try (InputStream in = new FileInputStream(file); ClipboardReader reader = format.getReader(in)) {
            clipboard = reader.read();
        }
        final Region region = clipboard.getRegion();
        final BlockVector3 min = region.getMinimumPoint();
        final BlockVector3 max = region.getMaximumPoint();
        final int width = max.x() - min.x() + 1;
        final int height = max.y() - min.y() + 1;
        final int depth = max.z() - min.z() + 1;
        if ((long) width * height * depth > 200_000L) {
            throw new IOException("schemat jest za duży (maks. 200000 bloków)");
        }
        final String[] cells = new String[width * height * depth];
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    final String s = clipboard.getBlock(BlockVector3.at(min.x() + x, min.y() + y, min.z() + z)).getAsString();
                    final String state;
                    if (s.startsWith("minecraft:structure_void")) {
                        state = null;
                    } else if (BlockStates.isAir(BlockStates.parse(s).id())) {
                        state = BlockStates.AIR;
                    } else {
                        state = BlockStates.normalize(s);
                    }
                    cells[(y * depth + z) * width + x] = state;
                }
            }
        }

        final String fileName = file.getName();
        final String baseId = fileName.substring(0, fileName.lastIndexOf('.')).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        final File sidecar = new File(file.getParentFile(), fileName + ".yml");
        final YamlConfiguration meta = sidecar.isFile() ? YamlConfiguration.loadConfiguration(sidecar) : new YamlConfiguration();
        final int originLayer = clipboard.getOrigin().y() - min.y() - 1;
        final int ground = Math.max(0, Math.min(height - 1, meta.getInt("ground-layer", Math.max(0, originLayer))));
        final String front = meta.getString("front", "south").trim().toLowerCase(Locale.ROOT);

        // beds are counted from the blocks; declared = counted
        final BuildingTemplate counting = new BuildingTemplate(new BuildingTemplate.Meta(baseId, baseId, "", BuildingFunction.OTHER,
            0, 0, FoundationMode.NONE, "cobblestone", ground, ""), width, height, depth, cells);
        final int beds = counting.bedCount();
        final BuildingTemplate.Meta m = new BuildingTemplate.Meta(
            meta.getString("id", baseId).toLowerCase(Locale.ROOT),
            meta.getString("name", baseId),
            meta.getString("description", "Budynek ze schematu " + fileName),
            BuildingFunction.parse(meta.getString("function", beds > 0 ? "housing" : "other")),
            beds,
            Math.max(0, meta.getLong("cost", defaultCost)),
            FoundationMode.parse(meta.getString("foundation", "fill")),
            meta.getString("foundation-block", "cobblestone"),
            ground,
            "schemat:" + fileName);
        BuildingTemplate t = new BuildingTemplate(m, width, height, depth, cells);
        // normalise so that the front (door side) is south like the YAML templates
        final int frontIndex = switch (front) {
            case "north" -> 0;
            case "east" -> 1;
            case "west" -> 3;
            default -> 2;
        };
        final int q = Math.floorMod(2 - frontIndex, 4);
        if (q != 0) {
            final BuildingTemplate r = t.rotated(q);
            final String[] rc = new String[r.volume()];
            for (int i = 0; i < rc.length; i++) {
                rc[i] = r.state(i);
            }
            t = new BuildingTemplate(m, r.width(), r.height(), r.depth(), rc);
        }
        return t;
    }
}
