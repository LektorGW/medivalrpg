// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.medivalrpg.kraje.api.KrajeApi;

/**
 * The animated ship model laid over the physical fleet entities (boat, invisible shulker deck, helm seat).
 * It only follows the boat: the deck colliders, carrying of walkers, helm and exits stay in FleetService.
 */
public final class ShipVisual {
    private static int live;

    private final ArmiaModels models;
    private final ModelHandle handle;
    private final ShipAnimator animator;
    private final UUID country, ship;
    private final String model;
    private boolean krajeFlag;
    private long lastFlag = Long.MIN_VALUE;
    private double flagX = Double.NaN, flagZ, flagYaw;
    private volatile float rudder;
    private float sentRudder;
    private long lastViewers = Long.MIN_VALUE, lastColour = Long.MIN_VALUE;
    private int colour = -1;
    private ItemStack flagShown;
    private boolean closed;

    private ShipVisual(final ArmiaModels models, final ModelHandle handle, final UUID ship, final UUID country, final String model) {
        this.models = models;
        this.handle = handle;
        this.ship = ship;
        this.country = country;
        this.model = model;
        this.animator = new ShipAnimator(models.settings().ship());
        handle.steer(ModelCatalog.RUDDER, () -> this.rudder);
    }

    /** Null when BetterModel or the model is missing, or the ship model budget is used up. */
    public static ShipVisual create(final UUID ship, final String shipType, final UUID country, final Location boat) {
        final String model = ModelCatalog.shipModel(shipType);
        if (model == null) return null;
        final ArmiaModels models = ArmiaModels.get();
        final ModelHandle handle = models.create(model, boat, boat.getYaw(), live, models.settings().shipLimit());
        if (handle == null) return null;
        live++;
        return new ShipVisual(models, handle, ship, country, model);
    }

    public static boolean available(final String shipType) {
        return ArmiaModels.get().ready(ModelCatalog.shipModel(shipType));
    }

    public static boolean hideBoat() { return ArmiaModels.get().settings().hideBoat(); }

    /** Follows the boat (same tick as the deck colliders); captain input steers the rudder. */
    public void sync(final Location boat, final Player captain) {
        if (this.closed) return;
        final long tick = Bukkit.getCurrentTick();
        this.handle.move(boat, boat.getYaw());
        ShipAnimator.Steer steer = ShipAnimator.Steer.UNKNOWN;
        if (captain != null) {
            final Input input = captain.getCurrentInput();
            steer = input.isLeft() == input.isRight() ? ShipAnimator.Steer.NONE
                : input.isRight() ? ShipAnimator.Steer.RIGHT : ShipAnimator.Steer.LEFT;
        }
        for (final ModelAction action : this.animator.update(tick, boat.getX(), boat.getZ(), boat.getYaw(), steer))
            this.handle.play(action, null);
        this.rudder = this.animator.rudder();
        if (Math.abs(this.rudder - this.sentRudder) > 0.5f) {
            this.sentRudder = this.rudder;
            this.handle.refresh();
        }
        if (tick - this.lastViewers >= 10) {
            this.lastViewers = tick;
            final VisualSettings s = this.models.settings();
            this.handle.viewers(s.shipShow(), s.shipHide());
        }
        if (tick - this.lastColour >= 200) {
            this.lastColour = tick;
            this.paint();
        }
        this.krajeBanner(boat, tick);
    }

    /** Country banner from Kraje on the ensign staff; moved with the ship, our dyed ensign hidden while it shows. */
    private void krajeBanner(final Location boat, final long tick) {
        final boolean moved = Double.isNaN(this.flagX) || Math.abs(boat.getX() - this.flagX) > 0.01
            || Math.abs(boat.getZ() - this.flagZ) > 0.01 || Math.abs(boat.getYaw() - this.flagYaw) > 0.1;
        if (!moved && tick - this.lastFlag < 100) return;
        this.lastFlag = tick;
        this.flagX = boat.getX(); this.flagZ = boat.getZ(); this.flagYaw = boat.getYaw();
        final double[] h = ModelCatalog.ensignHoist(this.model);
        if (h == null) return;
        final double r = Math.toRadians(boat.getYaw()), c = Math.cos(r), s = Math.sin(r);
        final Location top = new Location(boat.getWorld(), boat.getX() + h[0] * c - h[2] * s, boat.getY() + h[1],
            boat.getZ() + h[0] * s + h[2] * c, boat.getYaw(), 0);
        final boolean shown = KrajeFlags.show(this.ship, this.country, top, boat.getYaw(), ModelCatalog.ENSIGN_SCALE);
        if (shown != this.krajeFlag) {
            this.krajeFlag = shown;
            this.handle.visible(ModelCatalog.ENSIGN, !shown);
        }
    }

    private void paint() {
        final KrajeApi kraje = Bukkit.getServicesManager().load(KrajeApi.class);
        final int rgb = kraje == null ? 0xB03030 : kraje.getCountry(this.country)
            .map(c -> c.color() == null ? 0xB03030 : c.color().value()).orElse(0xB03030);
        if (rgb != this.colour) {
            this.colour = rgb;
            this.handle.tint(rgb);
        }
        final Optional<ItemStack> flag = this.models.flag(this.country);
        if (flag.isPresent() && !flag.get().isSimilar(this.flagShown)) {
            this.flagShown = flag.get().clone();
            this.handle.item(ModelCatalog.ENSIGN, this.flagShown);
        }
    }

    /** False after BetterModel dropped the model (reload without our files): the ship falls back to blocks. */
    public boolean alive() { return !this.closed && !this.handle.closed() && this.models.ready(this.model); }

    public void close() {
        if (this.closed) return;
        this.closed = true;
        live = Math.max(0, live - 1);
        if (this.krajeFlag) KrajeFlags.hide(this.ship);
        this.models.release(this.handle);
    }

    public ModelHandle handle() { return this.handle; }

    public ShipAnimator animator() { return this.animator; }

    public static int liveShips() { return live; }

    static void resetCount() { live = 0; }
}
