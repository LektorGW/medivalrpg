// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import java.nio.file.Path;
import org.bukkit.Location;

/** Model renderer behind the visual layer. {@link #NONE} keeps the vanilla block look (no BetterModel). */
public interface ModelBackend {
    String name();

    boolean ready(String model);

    ModelHandle create(String model, Location at, float yaw);

    /** {@code plugins/BetterModel/models}, or null. */
    Path modelFolder();

    /** Asks the renderer to rebuild its models and resource pack; false when it is already reloading. */
    boolean reload();

    ModelBackend NONE = new ModelBackend() {
        @Override public String name() { return "brak (wygląd z bloków)"; }
        @Override public boolean ready(final String model) { return false; }
        @Override public ModelHandle create(final String model, final Location at, final float yaw) { return null; }
        @Override public Path modelFolder() { return null; }
        @Override public boolean reload() { return false; }
    };
}
