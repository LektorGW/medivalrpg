// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.protection;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlastPolicyTest {
    private final UUID ours=UUID.randomUUID(),enemy=UUID.randomUUID();
    @Test void verifiedOwnTntWorksAtHome() {
        assertTrue(BlastPolicy.allows(ours,ours,true,false,false,false));
        assertFalse(BlastPolicy.allows(ours,ours,false,false,false,false));
    }
    @Test void enemyNeedsBothWarAndLiveSiegePermissionForEachBlock() {
        assertTrue(BlastPolicy.allows(ours,enemy,true,false,true,true));
        assertFalse(BlastPolicy.allows(ours,enemy,true,false,true,false));
        assertFalse(BlastPolicy.allows(ours,enemy,true,false,false,true));
        assertFalse(BlastPolicy.allows(ours,enemy,true,false,false,false));
    }
    @Test void unknownSourcesAndUntaggedTntCannotDamageAnyCountry() {
        assertFalse(BlastPolicy.allows(null,ours,false,false,false,false));
        assertFalse(BlastPolicy.allows(null,enemy,true,false,true,true));
        assertFalse(BlastPolicy.allows(ours,enemy,false,false,true,true));
    }
    @Test void ContainersAndSpawnAreasAreProtectedEvenOnOwnLand() {
        assertFalse(BlastPolicy.allows(ours,ours,true,true,false,false));
        assertFalse(BlastPolicy.allows(ours,enemy,true,true,true,true));
        assertFalse(BlastPolicy.allows(ours,null,true,true,false,false));
    }
    @Test void ForeignNeutralBorderIsProtectedButOrdinaryWildernessIsUnchanged() {
        assertFalse(BlastPolicy.allows(ours,enemy,true,false,false,false));
        assertTrue(BlastPolicy.allows(ours,null,true,false,false,false));
        assertTrue(BlastPolicy.allows(null,null,false,false,false,false));
    }
    @Test void ChainKeepsOriginalCountryRatherThanInferringFromTargetLand() {
        // A chained charge originating from ours does not become an enemy's "own" TNT when it crosses a border.
        assertFalse(BlastPolicy.allows(ours,enemy,true,false,false,false));
        assertTrue(BlastPolicy.allows(ours,ours,true,false,false,false));
    }
}
