// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.panel.rpc.MethodSpec;

/** Permission matrix: every RPC method of PANEL.md × every kind of user × own / foreign / NPC country. */
class AccessMatrixTest {

    static final UUID OWN = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID FOREIGN = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    static final UUID NPC_OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    enum Kind { OUTSIDER, CITIZEN, ADVISOR, RULER, ADMIN, NPC_GLOBAL, NPC_ASSIGNED }

    static Actor actor(final Kind k) {
        final UUID me = UUID.randomUUID();
        return switch (k) {
            case OUTSIDER -> new Actor(me, "Obcy", false, false, Set.of(), null, null);
            case CITIZEN -> new Actor(me, "Obywatel", false, false, Set.of(), OWN, Rank.CITIZEN);
            case ADVISOR -> new Actor(me, "Doradca", false, false, Set.of(), OWN, Rank.ADVISOR);
            case RULER -> new Actor(me, "Wladca", false, false, Set.of(), OWN, Rank.RULER);
            case ADMIN -> new Actor(me, "Admin", true, false, Set.of(), null, null);
            case NPC_GLOBAL -> new Actor(me, "Zarzadca", false, true, Set.of(), OWN, Rank.CITIZEN);
            case NPC_ASSIGNED -> new Actor(me, "ZarzadcaJeden", false, false, Set.of(NPC), OWN, Rank.CITIZEN);
        };
    }

    /** v2 methods of PANEL.md "Rozszerzenie v2" (+ treasury, disband, setTaxMultiplier from "Kształty odpowiedzi v2"). */
    static final List<String> V2_READS = List.of("admin.npc.history", "admin.npc.relations", "admin.npc.economy", "admin.npc.buildings",
        "admin.npc.army", "admin.npc.log");
    static final List<String> V2 = List.of("admin.npc.history", "admin.npc.relations", "admin.npc.setOpinion", "admin.npc.setStance",
        "admin.npc.propose", "admin.npc.declareWar", "admin.npc.demandTribute", "admin.npc.economy", "admin.npc.setBudget", "admin.npc.food",
        "admin.npc.settlers", "admin.npc.buildings", "admin.npc.order", "admin.npc.develop", "admin.npc.queue", "admin.npc.army",
        "admin.npc.setPosture", "admin.npc.setSiegeTarget", "admin.npc.log", "admin.npc.treasury", "admin.npc.disband", "admin.npc.setTaxMultiplier");

    /** Plan editor (PANEL.md "Edytor planu państwa NPC"): reads and audited mutations. */
    static final List<String> PLAN_READS = List.of("admin.npc.mapRender", "admin.npc.getPlan", "admin.npc.planPreview");
    static final List<String> PLAN = List.of("admin.npc.mapRender", "admin.npc.getPlan", "admin.npc.setPlan", "admin.npc.clearPlan",
        "admin.npc.planPreview", "admin.npc.territory", "admin.npc.festival", "admin.npc.forceRedesign", "admin.npc.forceRoads",
        "admin.npc.forceModernize");

    /** Requirement of each method exactly as written in PANEL.md (independent copy of the spec). */
    static final Map<String, Requirement> SPEC = new LinkedHashMap<>();

    static {
        SPEC.put("me", Requirement.LOGGED_IN);
        SPEC.put("overview", Requirement.LOGGED_IN);
        SPEC.put("country.get", Requirement.LOGGED_IN);
        // Economy page: role decided per call in EconomyMethods.access (EconomyAccessTest).
        SPEC.put("country.economy", Requirement.LOGGED_IN);
        // Country banners: rank and moderator role are re-checked by Kraje (FlagMethodsTest).
        SPEC.put("country.flag.get", Requirement.LOGGED_IN);
        for (final String m : List.of("country.flag.upload", "country.flag.withdraw", "country.flag.reset", "country.flag.restore")) {
            SPEC.put(m, Requirement.ADVISOR);
        }
        for (final String m : List.of("admin.flag.queue", "admin.flag.approve", "admin.flag.reject", "admin.flag.rebuild")) {
            SPEC.put(m, Requirement.LOGGED_IN);
        }
        for (final String m : List.of("country.setColor", "country.setDescription", "country.setTax", "country.transferRuler",
            "member.setRank", "relation.propose", "relation.declareWar", "relation.breakAlliance", "treasury.withdraw", "army.disband")) {
            SPEC.put(m, Requirement.RULER);
        }
        for (final String m : List.of("member.kick", "member.invite", "army.recruit")) {
            SPEC.put(m, Requirement.ADVISOR);
        }
        SPEC.put("treasury.deposit", Requirement.MEMBER);
        SPEC.put("admin.npc.create", Requirement.NPC_CREATE);
        SPEC.put("admin.npc.fromVillage", Requirement.NPC_CREATE);
        for (final String m : List.of("admin.npc.set", "admin.npc.pause", "admin.npc.tick", "admin.npc.armyAdd", "admin.npc.ruler")) {
            SPEC.put(m, Requirement.NPC_MANAGE);
        }
        for (final String m : V2) {
            SPEC.put(m, Requirement.NPC_MANAGE);
        }
        for (final String m : PLAN) {
            SPEC.put(m, Requirement.NPC_MANAGE);
        }
        for (final String m : List.of("country.plan.get", "country.plan.mapRender", "country.plan.preview", "country.plan.buildings",
            "country.plan.levelPreview", "country.plan.level")) {
            SPEC.put(m, Requirement.ADVISOR_OR_NPC_MANAGE);
        }
        SPEC.put("country.plan.set", Requirement.RULER_OR_NPC_MANAGE);
        SPEC.put("country.plan.clear", Requirement.RULER_OR_NPC_MANAGE);
        SPEC.put("postep.get", Requirement.LOGGED_IN);
        for(final String method:List.of("world.get","world.allocate","world.return","world.npcTreasuries","world.npcTransfer","world.grants","world.grantsApply"))SPEC.put(method,Requirement.LOGGED_IN);
        // Owner statistics: the generic gate is LOGGED_IN; the personal manager's UUID is enforced by Panel+Skarbiec (WorldStatsMethodsTest).
        for(final String method:List.of("world.stats.summary","world.stats.accounts","world.stats.flows","world.stats.account","world.stats.coins",
            "world.stats.trail","world.stats.merchant","world.stats.census","world.stats.households"))SPEC.put(method,Requirement.LOGGED_IN);
        for(final String method:List.of("railway.get","railway.request.create","railway.request.quote","railway.request.confirm","railway.request.cancel","railway.work.resume","railway.work.cancel","railway.line.create",
            "railway.line.configure","railway.train.assign","railway.crew.assign"))SPEC.put(method,Requirement.LOGGED_IN);
        // Player companies: Firmy decide public/member/owner access on every call (FirmyMethodsTest).
        for (final String method : MethodSpec.FIRMY_READS) SPEC.put(method, Requirement.LOGGED_IN);
        for (final String method : MethodSpec.FIRMY_WRITES) SPEC.put(method, Requirement.LOGGED_IN);
        SPEC.put("postep.investPreview", Requirement.ADVISOR_OR_NPC_MANAGE);
        SPEC.put("postep.discover", Requirement.ADVISOR_OR_NPC_MANAGE);
        SPEC.put("postep.invest", Requirement.ADVISOR_OR_NPC_MANAGE);
        for (final String m : List.of("admin.postep.setEra", "admin.postep.points", "admin.postep.grant")) {
            SPEC.put(m, Requirement.ADMIN);
        }
        for (final String m : List.of("admin.npc.setManager", "admin.country.delete", "admin.country.setRuler", "admin.relation.set",
            "admin.treasury.adjust", "admin.economy.adjust", "admin.players", "admin.audit", "admin.health")) {
            SPEC.put(m, Requirement.ADMIN);
        }
    }

    @Test
    void everyMethodOfTheSpecIsRegisteredWithItsRequirement() {
        assertEquals(SPEC.keySet(), MethodSpec.ALL.keySet(), "methods");
        SPEC.forEach((name, req) -> assertEquals(req, MethodSpec.of(name).requirement(), name));
    }

    @Test
    void readsAreNotMutationsAndMutationsAreAudited() {
        final List<String> reads = new java.util.ArrayList<>(List.of("me", "overview", "country.get", "admin.players", "admin.audit",
            "admin.health", "railway.get", "world.get", "postep.get", "postep.investPreview", "country.economy"));
        reads.addAll(MethodSpec.WORLD_STATS_READS);
        reads.add("world.npcTreasuries");
        reads.add("world.grants");
        reads.addAll(MethodSpec.FIRMY_READS);
        reads.addAll(List.of("country.flag.get", "admin.flag.queue"));
        reads.addAll(V2_READS);
        reads.addAll(PLAN_READS);
        reads.addAll(List.of("country.plan.get", "country.plan.mapRender", "country.plan.preview", "country.plan.buildings", "country.plan.levelPreview"));
        for (final String read : reads) {
            assertFalse(MethodSpec.of(read).mutation(), read);
        }
        MethodSpec.ALL.values().stream().filter(s -> !reads.contains(s.name()))
            .forEach(s -> assertTrue(s.mutation(), s.name()));
        final List<String> npcMethods = new java.util.ArrayList<>(List.of("admin.npc.set", "admin.npc.pause", "admin.npc.tick", "admin.npc.armyAdd",
            "admin.npc.ruler", "admin.npc.setManager"));
        npcMethods.addAll(V2);
        npcMethods.addAll(PLAN);
        for (final String npc : npcMethods) {
            assertTrue(MethodSpec.of(npc).npcOnly(), npc);
            assertTrue(MethodSpec.of(npc).targetsId(), npc);
        }
        assertFalse(MethodSpec.of("admin.npc.create").targetsId());
        assertFalse(MethodSpec.of("admin.npc.fromVillage").targetsId());
        assertTrue(MethodSpec.of("admin.npc.fromVillage").mutation());
        assertNotNull(MethodSpec.of("country.get"));
    }

    /** Expected outcome for a requirement, user kind and target. */
    static boolean expected(final Requirement r, final Kind k, final UUID target) {
        final boolean npcTarget = target.equals(NPC) || target.equals(NPC_OTHER);
        final boolean own = target.equals(OWN);
        return switch (r) {
            case LOGGED_IN -> true;
            case MEMBER -> k == Kind.ADMIN || (own && k != Kind.OUTSIDER);
            case ADVISOR -> k == Kind.ADMIN || (own && (k == Kind.ADVISOR || k == Kind.RULER));
            case ADVISOR_OR_NPC_MANAGE -> k == Kind.ADMIN || (own && (k == Kind.ADVISOR || k == Kind.RULER))
                || (npcTarget && (k == Kind.NPC_GLOBAL || (k == Kind.NPC_ASSIGNED && target.equals(NPC))));
            case RULER -> k == Kind.ADMIN || (own && k == Kind.RULER);
            case RULER_OR_NPC_MANAGE -> k == Kind.ADMIN || (own && k == Kind.RULER)
                || (npcTarget && (k == Kind.NPC_GLOBAL || (k == Kind.NPC_ASSIGNED && target.equals(NPC))));
            case NPC_MANAGE -> npcTarget && (k == Kind.ADMIN || k == Kind.NPC_GLOBAL || (k == Kind.NPC_ASSIGNED && target.equals(NPC)));
            case NPC_CREATE -> k == Kind.ADMIN || k == Kind.NPC_GLOBAL;
            case ADMIN -> k == Kind.ADMIN;
        };
    }

    @Test
    void matrix() {
        int checked = 0;
        for (final MethodSpec spec : MethodSpec.ALL.values()) {
            for (final Kind k : Kind.values()) {
                final Actor a = actor(k);
                for (final UUID target : List.of(OWN, FOREIGN, NPC, NPC_OTHER)) {
                    final boolean npc = target.equals(NPC) || target.equals(NPC_OTHER);
                    final UUID t = spec.targetsId() ? target : null;
                    final boolean want = expected(spec.requirement(), k, target);
                    final boolean got = Access.allowed(a, spec.requirement(), t, npc);
                    if (spec.targetsId() || target.equals(OWN)) {
                        assertEquals(want, got, spec.name() + " / " + k + " / " + target);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 300, "checked " + checked);
    }

    @Test
    void postepMethods() {
        for (final String m : List.of("postep.get", "postep.investPreview", "postep.discover", "postep.invest",
            "admin.postep.setEra", "admin.postep.points", "admin.postep.grant")) {
            assertTrue(MethodSpec.of(m).targetsId(), m);
            assertFalse(MethodSpec.of(m).npcOnly(), m);
        }
        // advisors of their own country, NPC managers of their NPC country, nobody else
        assertTrue(Access.allowed(actor(Kind.ADVISOR), Requirement.ADVISOR_OR_NPC_MANAGE, OWN, false));
        assertFalse(Access.allowed(actor(Kind.CITIZEN), Requirement.ADVISOR_OR_NPC_MANAGE, OWN, false));
        assertFalse(Access.allowed(actor(Kind.ADVISOR), Requirement.ADVISOR_OR_NPC_MANAGE, FOREIGN, false));
        assertTrue(Access.allowed(actor(Kind.NPC_ASSIGNED), Requirement.ADVISOR_OR_NPC_MANAGE, NPC, true));
        assertFalse(Access.allowed(actor(Kind.NPC_ASSIGNED), Requirement.ADVISOR_OR_NPC_MANAGE, NPC_OTHER, true));
        assertFalse(Access.allowed(actor(Kind.NPC_GLOBAL), Requirement.ADVISOR_OR_NPC_MANAGE, FOREIGN, false));
        assertTrue(Access.allowed(actor(Kind.NPC_GLOBAL), Requirement.ADVISOR_OR_NPC_MANAGE, NPC_OTHER, true));
    }

    @Test
    void rulerExamples() {
        final Actor ruler = actor(Kind.RULER);
        assertTrue(Access.allowed(ruler, Requirement.RULER, OWN, false));
        assertFalse(Access.allowed(ruler, Requirement.RULER, FOREIGN, false));
        assertFalse(Access.allowed(ruler, Requirement.ADMIN, null, false));
        assertFalse(Access.allowed(ruler, Requirement.NPC_MANAGE, NPC, true));
        final Actor citizen = actor(Kind.CITIZEN);
        assertTrue(Access.allowed(citizen, Requirement.MEMBER, OWN, false));
        assertFalse(Access.allowed(citizen, Requirement.ADVISOR, OWN, false));
        assertFalse(Access.allowed(actor(Kind.OUTSIDER), Requirement.MEMBER, OWN, false));
    }

    @Test
    void v2MethodsForAdminAndManagersOfThatNpcCountryOnly() {
        for (final String m : V2) {
            final Requirement r = MethodSpec.of(m).requirement();
            assertTrue(Access.allowed(actor(Kind.ADMIN), r, NPC, true), m);
            assertTrue(Access.allowed(actor(Kind.NPC_GLOBAL), r, NPC_OTHER, true), m);
            assertTrue(Access.allowed(actor(Kind.NPC_ASSIGNED), r, NPC, true), m);
            assertFalse(Access.allowed(actor(Kind.NPC_ASSIGNED), r, NPC_OTHER, true), m + " (cudzy kraj NPC)");
            assertFalse(Access.allowed(actor(Kind.RULER), r, NPC, true), m);
            assertFalse(Access.allowed(actor(Kind.ADMIN), r, OWN, false), m + " (kraj gracza)");
        }
    }

    @Test
    void planEditorForAdminAndManagersOfThatNpcCountryOnly() {
        for (final String m : PLAN) {
            final MethodSpec spec = MethodSpec.of(m);
            assertTrue(spec.npcOnly() && spec.targetsId(), m);
            final Requirement r = spec.requirement();
            assertTrue(Access.allowed(actor(Kind.ADMIN), r, NPC, true), m);
            assertTrue(Access.allowed(actor(Kind.NPC_GLOBAL), r, NPC_OTHER, true), m);
            assertTrue(Access.allowed(actor(Kind.NPC_ASSIGNED), r, NPC, true), m);
            assertFalse(Access.allowed(actor(Kind.NPC_ASSIGNED), r, NPC_OTHER, true), m + " (cudzy kraj NPC)");
            for (final Kind k : List.of(Kind.OUTSIDER, Kind.CITIZEN, Kind.ADVISOR, Kind.RULER)) {
                assertFalse(Access.allowed(actor(k), r, NPC, true), m + " / " + k);
                assertFalse(Access.allowed(actor(k), r, OWN, false), m + " / " + k + " (własny kraj gracza)");
            }
            assertFalse(Access.allowed(actor(Kind.ADMIN), r, OWN, false), m + " (kraj gracza)");
        }
        assertTrue(MethodSpec.of("admin.npc.setPlan").mutation(), "setPlan w audycie");
        assertTrue(MethodSpec.of("admin.npc.territory").mutation(), "territory w audycie");
        assertFalse(MethodSpec.of("admin.npc.mapRender").mutation(), "mapRender to odczyt");
    }

    @Test
    void npcManagersOnlyManageNpcCountries() {
        final Actor global = actor(Kind.NPC_GLOBAL);
        final Actor assigned = actor(Kind.NPC_ASSIGNED);
        // no admin powers
        for (final String m : List.of("admin.country.delete", "admin.country.setRuler", "admin.relation.set", "admin.treasury.adjust",
            "admin.economy.adjust", "admin.players", "admin.audit", "admin.health", "admin.npc.setManager")) {
            assertFalse(Access.allowed(global, MethodSpec.of(m).requirement(), NPC, true), m);
            assertFalse(Access.allowed(assigned, MethodSpec.of(m).requirement(), NPC, true), m);
        }
        // the role gives no rights in player countries
        assertFalse(Access.allowed(global, Requirement.NPC_MANAGE, FOREIGN, false));
        assertFalse(Access.allowed(global, Requirement.RULER, FOREIGN, false));
        // global: all NPC countries, may create; assigned: only its country, may not create
        assertTrue(Access.allowed(global, Requirement.NPC_MANAGE, NPC_OTHER, true));
        assertTrue(Access.allowed(global, Requirement.NPC_CREATE, null, false));
        assertTrue(Access.allowed(assigned, Requirement.NPC_MANAGE, NPC, true));
        assertFalse(Access.allowed(assigned, Requirement.NPC_MANAGE, NPC_OTHER, true));
        assertFalse(Access.allowed(assigned, Requirement.NPC_CREATE, null, false));
        // country.get "npc" part
        assertTrue(Access.canManageNpc(assigned, NPC, true));
        assertFalse(Access.canManageNpc(assigned, NPC_OTHER, true));
        assertFalse(Access.canManageNpc(actor(Kind.RULER), NPC, true));
        assertFalse(Access.canManageNpc(actor(Kind.ADMIN), OWN, false), "player country has no npc part");
    }

    @Test
    void playerPlanDoesNotGrantForeignOrAdministrativePowers() {
        final var ruler = actor(Kind.RULER);
        final var advisor = actor(Kind.ADVISOR);
        for (final String name : List.of("country.plan.get", "country.plan.mapRender", "country.plan.preview", "country.plan.buildings",
            "country.plan.levelPreview", "country.plan.level", "country.plan.set", "country.plan.clear")) {
            final var spec = MethodSpec.of(name);
            assertTrue(spec.targetsId()); assertFalse(spec.npcOnly());
            assertTrue(Access.allowed(ruler, spec.requirement(), OWN, false), name);
            assertFalse(Access.allowed(ruler, spec.requirement(), FOREIGN, false), name);
            assertFalse(Access.allowed(actor(Kind.CITIZEN), spec.requirement(), OWN, false), name);
            assertFalse(Access.allowed(actor(Kind.NPC_GLOBAL), spec.requirement(), FOREIGN, false), name);
            assertTrue(Access.allowed(actor(Kind.ADMIN), spec.requirement(), FOREIGN, false), name);
        }
        assertFalse(Access.allowed(advisor, MethodSpec.of("country.plan.set").requirement(), OWN, false));
        assertTrue(Access.allowed(advisor, MethodSpec.of("country.plan.level").requirement(), OWN, false));
        assertTrue(MethodSpec.of("country.plan.level").mutation());
        assertFalse(MethodSpec.of("country.plan.levelPreview").mutation());
        assertTrue(MethodSpec.of("admin.npc.territory").npcOnly(), "force claims remain NPC-only");
    }

    @Test
    void sensitivePartsOfCountryGet() {
        assertTrue(Access.canSeeMembers(actor(Kind.CITIZEN), OWN));
        assertFalse(Access.canSeeMembers(actor(Kind.CITIZEN), FOREIGN));
        assertFalse(Access.canSeeTreasury(actor(Kind.CITIZEN), OWN));
        assertTrue(Access.canSeeTreasury(actor(Kind.ADVISOR), OWN));
        assertTrue(Access.canSeeTreasury(actor(Kind.ADMIN), FOREIGN));
        assertFalse(Access.canSeeTreasury(actor(Kind.NPC_GLOBAL), NPC));
    }

    @Test
    void nullActorOrRequirementIsDenied() {
        assertFalse(Access.allowed(null, Requirement.LOGGED_IN, null, false));
        assertFalse(Access.allowed(actor(Kind.ADMIN), null, null, false));
    }
}
