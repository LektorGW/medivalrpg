// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.permit;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PrivateCarrierTest {
    @Test void consentBelongsToCarrierWithoutHomeOrAlliance() {
        UUID country = UUID.randomUUID();
        assertTrue(PermitRules.privateCarrierAllowed(country, Set.of(country)));
        assertFalse(PermitRules.privateCarrierAllowed(country, Set.of()));
        assertTrue(PermitRules.privateCarrierAllowed(null, Set.of()));
        assertFalse(PermitRules.privateCarrierAllowed(UUID.randomUUID(), Set.of(country)));
    }
}
