// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/** A packet-only model shown to nearby players. No server entity, nothing saved to disk. Main thread only. */
public interface ModelHandle {
    /** Moves the model; {@code yaw} is the Minecraft yaw the model's bow/front must face. */
    void move(Location at, float yaw);

    boolean play(ModelAction action, Supplier<Float> liveSpeed);

    /** Dyes every tinted bone (flags, pennants, shields, sail emblems) with {@code 0xRRGGBB}. */
    void tint(int rgb);

    /** Replaces the item shown by the bones starting with {@code bonePrefix}; null restores the model item. */
    boolean item(String bonePrefix, ItemStack item);

    /** Shows or hides the bones starting with {@code bonePrefix} (the dyed ensign under a Kraje banner). */
    void visible(String bonePrefix, boolean visible);

    /** Installs a code-driven local Y rotation (degrees, read on the renderer thread) on one bone. */
    boolean steer(String bone, Supplier<Float> degrees);

    /** Re-sends the pose after a code-driven change (rudder). */
    void refresh();

    /** Spawns the model for players inside the show range and removes it beyond the hide range. */
    int viewers(double show, double hide);

    int viewerCount();

    boolean closed();

    void close();

    /**
     * Test/diagnostic access: centre of a bone's cubes (blocks) relative to the model origin, with the current animation
     * pose and the model rotation applied; and the clip currently driving the bone.
     */
    Optional<Vector> bone(String name);

    Optional<String> running(String bone);
}
