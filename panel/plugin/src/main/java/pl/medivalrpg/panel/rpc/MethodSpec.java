// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import java.util.LinkedHashMap;
import java.util.Map;
import pl.medivalrpg.panel.access.Requirement;

/**
 * Static description of one RPC method from PANEL.md: who may call it, whether it changes the game (audit log,
 * mutation rate limit, in-game notification) and whether it acts on the country in {@code params.id}.
 *
 * @param name        method name
 * @param requirement permission
 * @param mutation    changes game state
 * @param targetsId   {@code params.id} is the country the permission is checked against
 * @param npcOnly     the target must be an NPC country
 */
public record MethodSpec(String name, Requirement requirement, boolean mutation, boolean targetsId, boolean npcOnly) {

    /** v2 reads of one NPC country (ADM, global NPC manager, manager of that country). */
    public static final java.util.List<String> NPC_V2_READS = java.util.List.of("admin.npc.history", "admin.npc.relations",
        "admin.npc.economy", "admin.npc.buildings", "admin.npc.army", "admin.npc.log");
    /** v2 mutations of one NPC country (audited). */
    public static final java.util.List<String> NPC_V2_WRITES = java.util.List.of("admin.npc.setOpinion", "admin.npc.setStance",
        "admin.npc.propose", "admin.npc.declareWar", "admin.npc.demandTribute", "admin.npc.setBudget", "admin.npc.food",
        "admin.npc.settlers", "admin.npc.order", "admin.npc.develop", "admin.npc.queue", "admin.npc.setPosture",
        "admin.npc.setSiegeTarget", "admin.npc.treasury", "admin.npc.disband", "admin.npc.setTaxMultiplier");

    /** Plan editor reads of one NPC country (PANEL.md "Edytor planu państwa NPC"). */
    public static final java.util.List<String> PLAN_READS = java.util.List.of("admin.npc.mapRender", "admin.npc.getPlan", "admin.npc.planPreview");
    /** Plan editor mutations (audited): the design plan and the territory tool. */
    public static final java.util.List<String> PLAN_WRITES = java.util.List.of("admin.npc.setPlan", "admin.npc.clearPlan", "admin.npc.territory");
    /** Shared plan editor; all calls are checked against params.id, including player countries. */
    public static final java.util.List<String> COUNTRY_PLAN_READS = java.util.List.of("country.plan.get", "country.plan.mapRender",
        "country.plan.preview", "country.plan.buildings");

    /** Festival (Zycie) and forced builds (Ludnosc) of one NPC country (audited). */
    public static final java.util.List<String> NPC_EXTRA_WRITES = java.util.List.of("admin.npc.festival", "admin.npc.forceRedesign",
        "admin.npc.forceRoads", "admin.npc.forceModernize");

    /** Owner-only statistics of the closed economy (reads; never cached by the Worker or the SPA). */
    public static final java.util.List<String> WORLD_STATS_READS = java.util.List.of("world.stats.summary", "world.stats.accounts",
        "world.stats.flows", "world.stats.account", "world.stats.coins", "world.stats.trail", "world.stats.merchant", "world.stats.households");

    /** Player companies (FirmyMethods → Firmy): reads; Firmy checks public/member/owner access on every call, never cached. */
    public static final java.util.List<String> FIRMY_READS = java.util.List.of("firmy.list", "firmy.get", "firmy.admin", "firmy.contracts",
        "firmy.disputes", "firmy.loans", "firmy.world.loans");
    /** Player companies: mutations (audited); Firmy checks the owner/member on every call. */
    public static final java.util.List<String> FIRMY_WRITES = java.util.List.of("firmy.admin.freeze", "firmy.dispute.resolve",
        "firmy.world.loanDecide", "firmy.world.loanSweep");

    /** All methods in PANEL.md order. */
    public static final Map<String, MethodSpec> ALL;

    static {
        final Map<String, MethodSpec> m = new LinkedHashMap<>();
        read(m, "me", Requirement.LOGGED_IN, false);
        read(m, "overview", Requirement.LOGGED_IN, false);
        // The private carrier verifies its own UUID whitelist; country ranks and admin status do not grant access.
        read(m, "railway.get", Requirement.LOGGED_IN, false);
        read(m, "world.get", Requirement.LOGGED_IN, false);
        write(m, "world.allocate", Requirement.LOGGED_IN, false, false);
        write(m, "world.return", Requirement.LOGGED_IN, false, false);
        // Reserve <-> NPC country treasuries (owner only; Panel and Skarbiec check the UUID, Skarbiec answers after a durable journal write).
        read(m, "world.npcTreasuries", Requirement.LOGGED_IN, false);
        write(m, "world.npcTransfer", Requirement.LOGGED_IN, false, false);
        // One-time deployment grants from the reserve (owner only, idempotent, durable).
        read(m, "world.grants", Requirement.LOGGED_IN, false);
        write(m, "world.grantsApply", Requirement.LOGGED_IN, false, false);
        // Owner statistics: Panel and Skarbiec both verify the personal manager's UUID on every call (WorldMethods.stats).
        for (final String read : WORLD_STATS_READS) {
            read(m, read, Requirement.LOGGED_IN, false);
        }
        write(m, "world.stats.census", Requirement.LOGGED_IN, false, false);
        // Player companies: the gate is LOGGED_IN, Firmy decides public/member/owner access on every call (FirmyMethods).
        for (final String read : FIRMY_READS) {
            read(m, read, Requirement.LOGGED_IN, false);
        }
        for (final String write : FIRMY_WRITES) {
            write(m, write, Requirement.LOGGED_IN, false, false);
        }
        for(String method:java.util.List.of("railway.request.create","railway.request.quote","railway.request.confirm","railway.request.cancel","railway.work.resume","railway.work.cancel","railway.line.create",
            "railway.line.configure","railway.train.assign","railway.crew.assign"))write(m,method,Requirement.LOGGED_IN,false,false);
        read(m, "country.get", Requirement.LOGGED_IN, true);
        // Economy of one country: ruler/advisors of params.id, admin, owner (WorldSteward) or that NPC country's manager —
        // decided in EconomyMethods.access on every call (the owner check needs Skarbiec, so the gate here is LOGGED_IN).
        read(m, "country.economy", Requirement.LOGGED_IN, true);
        // Country banners (FlagMethods → Kraje): Kraje re-checks the rank in params.id and the moderator role on every call.
        read(m, "country.flag.get", Requirement.LOGGED_IN, true);
        for (final String write : java.util.List.of("country.flag.upload", "country.flag.withdraw", "country.flag.reset", "country.flag.restore")) {
            write(m, write, Requirement.ADVISOR, true, false);
        }
        read(m, "admin.flag.queue", Requirement.LOGGED_IN, false);
        write(m, "admin.flag.approve", Requirement.LOGGED_IN, true, false);
        write(m, "admin.flag.reject", Requirement.LOGGED_IN, true, false);
        write(m, "admin.flag.rebuild", Requirement.LOGGED_IN, false, false);

        write(m, "country.setColor", Requirement.RULER, true, false);
        write(m, "country.setDescription", Requirement.RULER, true, false);
        write(m, "country.setTax", Requirement.RULER, true, false);
        write(m, "country.transferRuler", Requirement.RULER, true, false);
        write(m, "member.setRank", Requirement.RULER, true, false);
        write(m, "member.kick", Requirement.ADVISOR, true, false);
        write(m, "member.invite", Requirement.ADVISOR, true, false);
        write(m, "relation.propose", Requirement.RULER, true, false);
        write(m, "relation.declareWar", Requirement.RULER, true, false);
        write(m, "relation.breakAlliance", Requirement.RULER, true, false);
        write(m, "treasury.deposit", Requirement.MEMBER, true, false);
        write(m, "treasury.withdraw", Requirement.RULER, true, false);
        write(m, "army.recruit", Requirement.ADVISOR, true, false);
        write(m, "army.disband", Requirement.RULER, true, false);

        write(m, "admin.npc.create", Requirement.NPC_CREATE, false, false);
        write(m, "admin.npc.fromVillage", Requirement.NPC_CREATE, false, false);
        write(m, "admin.npc.set", Requirement.NPC_MANAGE, true, true);
        write(m, "admin.npc.pause", Requirement.NPC_MANAGE, true, true);
        write(m, "admin.npc.tick", Requirement.NPC_MANAGE, true, true);
        write(m, "admin.npc.armyAdd", Requirement.NPC_MANAGE, true, true);
        write(m, "admin.npc.ruler", Requirement.NPC_MANAGE, true, true);
        write(m, "admin.npc.setManager", Requirement.ADMIN, true, true);

        // v2: full management of one NPC country (PANEL.md "Rozszerzenie v2")
        for (final String read : NPC_V2_READS) {
            m.put(read, new MethodSpec(read, Requirement.NPC_MANAGE, false, true, true));
        }
        for (final String write : NPC_V2_WRITES) {
            write(m, write, Requirement.NPC_MANAGE, true, true);
        }
        // plan editor (PANEL.md "Edytor planu państwa NPC")
        for (final String read : PLAN_READS) {
            m.put(read, new MethodSpec(read, Requirement.NPC_MANAGE, false, true, true));
        }
        for (final String write : PLAN_WRITES) {
            write(m, write, Requirement.NPC_MANAGE, true, true);
        }
        for (final String read : COUNTRY_PLAN_READS) {
            read(m, read, Requirement.ADVISOR_OR_NPC_MANAGE, true);
        }
        write(m, "country.plan.set", Requirement.RULER_OR_NPC_MANAGE, true, false);
        write(m, "country.plan.clear", Requirement.RULER_OR_NPC_MANAGE, true, false);
        read(m, "country.plan.levelPreview", Requirement.ADVISOR_OR_NPC_MANAGE, true);
        write(m, "country.plan.level", Requirement.ADVISOR_OR_NPC_MANAGE, true, false);
        for (final String write : NPC_EXTRA_WRITES) {
            write(m, write, Requirement.NPC_MANAGE, true, true);
        }

        // Postep (eras and research; plugin optional)
        read(m, "postep.get", Requirement.LOGGED_IN, true);
        read(m, "postep.investPreview", Requirement.ADVISOR_OR_NPC_MANAGE, true);
        write(m, "postep.discover", Requirement.ADVISOR_OR_NPC_MANAGE, true, false);
        write(m, "postep.invest", Requirement.ADVISOR_OR_NPC_MANAGE, true, false);
        write(m, "admin.postep.setEra", Requirement.ADMIN, true, false);
        write(m, "admin.postep.points", Requirement.ADMIN, true, false);
        write(m, "admin.postep.grant", Requirement.ADMIN, true, false);

        write(m, "admin.country.delete", Requirement.ADMIN, true, false);
        write(m, "admin.country.setRuler", Requirement.ADMIN, true, false);
        write(m, "admin.relation.set", Requirement.ADMIN, false, false);
        write(m, "admin.treasury.adjust", Requirement.ADMIN, true, false);
        write(m, "admin.economy.adjust", Requirement.ADMIN, false, false);
        read(m, "admin.players", Requirement.ADMIN, false);
        read(m, "admin.audit", Requirement.ADMIN, false);
        read(m, "admin.health", Requirement.ADMIN, false);
        ALL = java.util.Collections.unmodifiableMap(m);
    }

    private static void read(final Map<String, MethodSpec> m, final String name, final Requirement r, final boolean targetsId) {
        m.put(name, new MethodSpec(name, r, false, targetsId, false));
    }

    private static void write(final Map<String, MethodSpec> m, final String name, final Requirement r, final boolean targetsId, final boolean npcOnly) {
        m.put(name, new MethodSpec(name, r, true, targetsId, npcOnly));
    }

    public static MethodSpec of(final String name) {
        return name == null ? null : ALL.get(name);
    }
}
