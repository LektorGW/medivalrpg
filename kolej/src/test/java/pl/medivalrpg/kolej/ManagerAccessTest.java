// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ManagerAccessTest {
    @Test void configuredOwnerNeedsRoleAndOtherOpsCannotManage(){UUID owner=UUID.randomUUID(),other=UUID.randomUUID();List<String> allowed=List.of(owner.toString());assertTrue(KolejPlugin.managementAllowed(owner,true,allowed));assertFalse(KolejPlugin.managementAllowed(owner,false,allowed));assertFalse(KolejPlugin.managementAllowed(other,true,allowed));}
    @Test void consoleAndUnconfiguredInstallStillRequirePermission(){assertTrue(KolejPlugin.managementAllowed(null,true,List.of("owner")));assertFalse(KolejPlugin.managementAllowed(null,false,List.of()));assertTrue(KolejPlugin.managementAllowed(UUID.randomUUID(),true,List.of()));}
}
