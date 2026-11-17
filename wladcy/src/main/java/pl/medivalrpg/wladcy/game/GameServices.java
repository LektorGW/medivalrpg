// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.game;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import pl.medivalrpg.armia.api.ArmiaApi;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/** APIs of the other MEDIVALRPG plugins (ServicesManager). Ludnosc is optional and looked up on every use. */
public final class GameServices {

    private final KrajeApi kraje;
    private final SkarbiecApi skarbiec;
    private final ArmiaApi armia;

    private GameServices(final KrajeApi kraje, final SkarbiecApi skarbiec, final ArmiaApi armia) {
        this.kraje = kraje;
        this.skarbiec = skarbiec;
        this.armia = armia;
    }

    public static Optional<GameServices> load() {
        final var sm = Bukkit.getServicesManager();
        final KrajeApi k = sm.load(KrajeApi.class);
        final SkarbiecApi s = sm.load(SkarbiecApi.class);
        final ArmiaApi a = sm.load(ArmiaApi.class);
        if (k == null || s == null || a == null) {
            return Optional.empty();
        }
        return Optional.of(new GameServices(k, s, a));
    }

    public KrajeApi kraje() {
        return this.kraje;
    }

    public SkarbiecApi skarbiec() {
        return this.skarbiec;
    }

    public ArmiaApi armia() {
        return this.armia;
    }

    /** Null when Ludnosc is not installed. */
    public LudnoscApi ludnosc() {
        return Bukkit.getServicesManager().load(LudnoscApi.class);
    }

    public String money(final long copper) {
        return this.skarbiec.format(copper);
    }

    public long treasury(final UUID country) {
        return this.skarbiec.balance(SkarbiecApi.countryAccount(country));
    }

    /** "około 1 200 zł"-style approximation (two significant digits) so the ruler does not recite exact balances. */
    public String approx(final long copper) {
        final long r = Approx.round(copper);
        return (r == copper ? "" : "około ") + this.money(r);
    }
}
