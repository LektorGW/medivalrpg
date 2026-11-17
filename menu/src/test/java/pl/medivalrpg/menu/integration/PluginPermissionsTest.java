// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.integration;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PluginPermissionsTest {
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test void configuredManagerCanUseDesignGateWithoutPermissionNode() {
        assertTrue(PluginPermissions.configuredManager("szlaki.zarzadzanie", PLAYER, List.of(PLAYER.toString())));
        assertFalse(PluginPermissions.configuredManager("szlaki.zarzadzanie", PLAYER, List.of(UUID.randomUUID().toString())));
        assertFalse(PluginPermissions.configuredManager("szlaki.zarzadzanie", PLAYER, List.of("name-of-player")));
    }

    @Test void removingManagerRevokesTheNextAccessCheckWithoutCachedPrivileges() {
        final var managers = new HashSet<>(List.of(PLAYER.toString()));
        assertTrue(PluginPermissions.configuredManager("szlaki.zarzadzanie", PLAYER, managers));
        managers.clear();
        assertFalse(PluginPermissions.configuredManager("szlaki.zarzadzanie", PLAYER, managers));
    }

    @Test void localManagerListDoesNotGrantAuditApprovalOrOtherPluginPermissions() {
        for (String permission : List.of("szlaki.admin", "menu.admin", "kolej.admin", "kolej.maszynista"))
            assertFalse(PluginPermissions.configuredManager(permission, PLAYER, List.of(PLAYER.toString())), permission);
    }
}
