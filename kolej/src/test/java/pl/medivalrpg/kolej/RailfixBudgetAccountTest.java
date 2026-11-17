// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.nio.file.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Budget account of the prepared railing-fix request, computed by the real class (never guessed). */
class RailfixBudgetAccountTest {
    @Test void railingFixRequestIsValidAndHasItsOwnBudgetAccount() throws Exception {
        Path template = Path.of("../deploy-rail-operations-20261029/railing-fix/request.template.json");
        Assumptions.assumeTrue(Files.exists(template), "railing-fix package not present");
        var request = new Gson().fromJson(Files.readString(template), NetworkCommission.Request.class);
        NetworkCommission.validate(request);
        String account = NetworkCommission.budgetAccount(request);
        assertTrue(account.matches("kolej:budzet_[a-f0-9]{16}"));
        assertNotEquals("kolej:budzet_c7f3fc34504cf3ab", account, "never the mainline budget");
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/railing-fix-budget-account.txt"), account + " maxTotalMd=" + request.maxTotalMd() + "\n");
        System.out.println("RAILFIX-ACCOUNT " + account);
        // Every part decodes with its pinned hashes and the own-structure permit accepts exactly fence → stone-brick parapet.
        int ops = 0;
        for (var part : request.parts()) {
            var decoded = NetworkCommission.decode(template.resolveSibling(part.file()), part.sha256(), 200000);
            var own = NetworkCommission.decodeOwn(template.resolveSibling(part.ownFile()), part.ownSha(), decoded);
            assertTrue(own.job().startsWith("mr-factory-s0"), own.job());
            assertDoesNotThrow(() -> pl.medivalrpg.kolej.build.NaturalClearance.forPlan(decoded, java.util.Map.of(), java.util.Map.of(), own.before()));
            assertTrue(part.maxCost() >= decoded.size());
            ops += decoded.size();
        }
        assertEquals(4023, ops);
    }
}
