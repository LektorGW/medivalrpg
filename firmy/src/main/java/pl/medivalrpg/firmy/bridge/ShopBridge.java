// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.bridge;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.firmy.core.FirmService;
import pl.medivalrpg.firmy.core.Outcome;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.Role;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;

/**
 * Company shops in Skarbiec: Firmy registers a policy (who opens the chest, may the shop trade, the seat country's tax)
 * and assigns shops through public Skarbiec methods reached by reflection. A Skarbiec without them simply has no
 * company shops ({@link #available()} false) — the rest of Firmy works.
 */
public final class ShopBridge {

    public static final String NAMESPACE = "firma";

    private final Plugin firmy;
    private final Plugin skarbiec;
    private final FirmService service;
    private final KrajeApi kraje;
    private final Logger log;
    private Method infoAt;
    private Method assign;
    private Method ofAccount;
    private Method removeAccount;
    private boolean registered;

    public ShopBridge(final Plugin firmy, final Plugin skarbiec, final FirmService service, final KrajeApi kraje, final Logger log) {
        this.firmy = firmy;
        this.skarbiec = skarbiec;
        this.service = service;
        this.kraje = kraje;
        this.log = log;
        try {
            final Class<?> c = skarbiec.getClass();
            this.infoAt = c.getMethod("shopInfoAt", String.class, int.class, int.class, int.class);
            this.assign = c.getMethod("shopAssign", UUID.class, String.class, int.class, int.class, int.class, String.class, String.class);
            this.ofAccount = c.getMethod("shopsOfAccount", String.class);
            this.removeAccount = c.getMethod("shopsRemoveAccount", String.class, String.class);
            final Method register = c.getMethod("registerShopAccounts", String.class, Function.class);
            final Function<Map<String, Object>, Map<String, Object>> policy = this::policy;
            this.registered = Boolean.TRUE.equals(register.invoke(skarbiec, NAMESPACE, policy));
        } catch (final ReflectiveOperationException | RuntimeException e) {
            this.registered = false;
            log.warning("Skarbiec nie obsługuje sklepów firm (" + e.getClass().getSimpleName() + ") — sklepy firmowe wyłączone, reszta działa.");
        }
    }

    public boolean available() {
        return this.registered;
    }

    public void unregister() {
        if (!this.registered) {
            return;
        }
        try {
            this.skarbiec.getClass().getMethod("unregisterShopAccounts", String.class).invoke(this.skarbiec, NAMESPACE);
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // Skarbiec already disabled
        }
        this.registered = false;
    }

    // ------------------------------------------------------------------ policy (called by Skarbiec, main thread)

    private Optional<Firm> firmOf(final Object account) {
        final String a = String.valueOf(account);
        if (!a.startsWith(Firm.ACCOUNT_PREFIX)) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.service.ctx.data.firms.get(a.substring(Firm.ACCOUNT_PREFIX.length())));
    }

    private static Map<String, Object> deny(final String reason) {
        final Map<String, Object> m = new HashMap<>();
        m.put("allow", false);
        m.put("reason", reason);
        return m;
    }

    private static UUID uuid(final Object o) {
        try {
            return UUID.fromString(String.valueOf(o));
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    Map<String, Object> policy(final Map<String, Object> req) {
        final Optional<Firm> of = this.firmOf(req.get("account"));
        if (of.isEmpty()) {
            return deny("nieznana firma");
        }
        final Firm f = of.get();
        final String op = String.valueOf(req.get("op"));
        final UUID player = uuid(req.get("player"));
        switch (op) {
            case "access" -> {
                final boolean breaking = "break".equals(req.get("action"));
                final boolean ok = player != null && (breaking
                    ? f.active() && f.has(player, Role.WSPOLNIK) || !f.active() && this.service.treasury.chestAccess(f, player)
                    : this.service.treasury.chestAccess(f, player));
                return ok ? Map.of("allow", true) : deny("to sklep firmy " + f.name);
            }
            case "trade" -> {
                final boolean buy = "BUY".equals(req.get("type"));
                if (buy && player != null && f.members.containsKey(player.toString())) {
                    return deny("ludzie firmy nie sprzedają własnej firmie przez jej sklep");
                }
                final UUID shopCountry = this.countryAt(req);
                final Optional<String> blocked = this.service.treasury.shopBlocked(f, buy, shopCountry);
                if (blocked.isPresent()) {
                    return deny(blocked.get());
                }
                final Map<String, Object> m = new HashMap<>();
                m.put("allow", true);
                this.service.treasury.taxTarget(f).ifPresent(t -> {
                    m.put("taxAccount", t.getKey());
                    m.put("taxBp", (long) t.getValue());
                });
                return m;
            }
            case "traded" -> {
                final boolean buy = "BUY".equals(req.get("type"));
                final long gross = req.get("gross") instanceof Number n ? n.longValue() : 0L;
                final long tax = req.get("tax") instanceof Number n ? n.longValue() : 0L;
                final String what = (buy ? "Sklep: skup " : "Sklep: sprzedaż ") + req.get("amount") + "× " + req.get("item")
                    + (buy ? " od " : " dla ") + req.get("playerName");
                // never do file IO or more transfers inside Skarbiec's ledger lock: settle on the next tick
                Bukkit.getScheduler().runTask(this.firmy, () -> {
                    if (buy) {
                        f.lastActivity = this.service.ctx.now();
                        this.service.ctx.journal(f, "sklep", what, -gross, String.valueOf(req.get("playerName")));
                        this.service.ctx.save();
                    } else {
                        this.service.treasury.recordIncome(f, gross, tax, what);
                    }
                });
                return Map.of("allow", true);
            }
            case "removed" -> {
                Bukkit.getScheduler().runTask(this.firmy, () -> {
                    this.service.ctx.journal(f, "sklep", "Sklep firmy usunięty (zniszczony)", 0,
                        player == null ? "?" : this.service.ctx.world.playerName(player));
                    this.service.ctx.save();
                });
                return Map.of("allow", true);
            }
            default -> {
                return deny("nieznana operacja");
            }
        }
    }

    private UUID countryAt(final Map<String, Object> req) {
        final Object w = req.get("world");
        if (!(req.get("x") instanceof Number x) || !(req.get("z") instanceof Number z) || w == null) {
            return null;
        }
        return this.kraje.getCountryAt(String.valueOf(w), x.intValue() >> 4, z.intValue() >> 4).map(KrajInfo::id).orElse(null);
    }

    // ------------------------------------------------------------------ actions

    @SuppressWarnings("unchecked")
    public Map<String, Object> shopAt(final String world, final int x, final int y, final int z) {
        if (!this.registered) {
            return null;
        }
        try {
            return (Map<String, Object>) this.infoAt.invoke(this.skarbiec, world, x, y, z);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> shopsOf(final Firm f) {
        if (!this.registered) {
            return List.of();
        }
        try {
            return (List<Map<String, Object>>) this.ofAccount.invoke(this.skarbiec, f.account());
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }

    /** {@code /firma sklep}: the player's own shop at the block becomes a company shop. */
    @SuppressWarnings("unchecked")
    public Outcome attach(final Firm f, final UUID actor, final String world, final int x, final int y, final int z) {
        if (!this.registered) {
            return Outcome.fail("Sklepy firmowe wymagają nowszego Skarbca.");
        }
        if (!f.active()) {
            return Outcome.fail("Firma jest zlikwidowana.");
        }
        final Map<String, Object> shop = this.shopAt(world, x, y, z);
        if (shop == null) {
            return Outcome.fail("Spójrz na tabliczkę [Sklep] albo skrzynię sklepu (najpierw postaw zwykły sklep).");
        }
        if (f.account().equals(shop.get("account"))) {
            return Outcome.fail("To już jest sklep tej firmy.");
        }
        final boolean buy = "BUY".equals(shop.get("type"));
        if (!f.has(actor, buy ? Role.MISTRZ : Role.WSPOLNIK)) {
            return Outcome.fail(buy ? "Sklep „Kupię” (płaci z konta firmy) przypisuje tylko mistrz." : "Sklepy przypisuje mistrz albo wspólnik.");
        }
        final int max = this.service.ctx.settings.maxShops.getOrDefault(f.type, 6);
        if (this.shopsOf(f).size() >= max) {
            return Outcome.fail("Firma ma już najwięcej sklepów (" + max + ").");
        }
        final UUID country = this.kraje.getCountryAt(world, ((Number) shop.get("x")).intValue() >> 4, ((Number) shop.get("z")).intValue() >> 4)
            .map(KrajInfo::id).orElse(null);
        if (country == null || !country.toString().equals(f.seatCountry)) {
            return Outcome.fail("Sklep firmy musi stać na ziemi kraju siedziby (" + f.seatCountryName + ").");
        }
        try {
            final Map<String, Object> r = (Map<String, Object>) this.assign.invoke(this.skarbiec, actor, world, x, y, z, f.account(), f.name);
            if (!Boolean.TRUE.equals(r.get("ok"))) {
                return Outcome.fail(String.valueOf(r.get("message")));
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return Outcome.fail("Skarbiec odrzucił przypisanie sklepu.");
        }
        this.service.ctx.journal(f, "sklep", "Nowy sklep firmy: " + (buy ? "Kupię " : "Sprzedam ") + shop.get("amount") + "× " + shop.get("item")
            + " za " + this.service.ctx.fmt(((Number) shop.get("price")).longValue()), 0, this.service.ctx.world.playerName(actor));
        this.service.ctx.save();
        return Outcome.ok("Sklep należy teraz do firmy „" + f.name + "”: utarg trafia na konto firmy (minus podatek "
            + this.service.treasury.taxTarget(f).map(t -> t.getValue() / 100.0 + " %").orElse("—") + "), skrzynię otwierają ludzie firmy.");
    }

    /** {@code /firma sklep usun}: the master gives the shop back to its builder. */
    @SuppressWarnings("unchecked")
    public Outcome detach(final Firm f, final UUID actor, final boolean admin, final String world, final int x, final int y, final int z) {
        if (!this.registered) {
            return Outcome.fail("Sklepy firmowe wymagają nowszego Skarbca.");
        }
        if (!admin && !f.has(actor, Role.MISTRZ)) {
            return Outcome.fail("Sklep odłącza mistrz.");
        }
        final Map<String, Object> shop = this.shopAt(world, x, y, z);
        if (shop == null || !f.account().equals(shop.get("account"))) {
            return Outcome.fail("To nie jest sklep tej firmy.");
        }
        try {
            final Map<String, Object> r = (Map<String, Object>) this.assign.invoke(this.skarbiec, actor, world, x, y, z, null, null);
            if (!Boolean.TRUE.equals(r.get("ok"))) {
                return Outcome.fail(String.valueOf(r.get("message")));
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return Outcome.fail("Skarbiec odrzucił odłączenie sklepu.");
        }
        this.service.ctx.journal(f, "sklep", "Sklep odłączony od firmy (wraca do " + shop.get("ownerName") + ")", 0,
            admin ? "admin" : this.service.ctx.world.playerName(actor));
        this.service.ctx.save();
        return Outcome.ok("Sklep wraca do " + shop.get("ownerName") + " (zwykły sklep gracza).");
    }

    /** Deletes the company's shops in Skarbiec (after a liquidation and the former master's grace period). */
    public int removeAll(final Firm f) {
        if (!this.registered) {
            return 0;
        }
        try {
            return ((Number) this.removeAccount.invoke(this.skarbiec, f.account(), "Firmy (likwidacja)")).intValue();
        } catch (final ReflectiveOperationException | RuntimeException e) {
            this.log.warning("Nie udało się usunąć sklepów firmy " + f.name + ": " + e);
            return 0;
        }
    }
}
