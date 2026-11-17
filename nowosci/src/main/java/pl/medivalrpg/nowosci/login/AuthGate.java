// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.login;

import org.bukkit.entity.Player;

/** Whether a player has logged in (Straznik, fallback nLogin) — announcements skip players still on the login screen. */
public interface AuthGate {

    /** Without an authentication plugin everyone online counts as logged in. */
    AuthGate NONE = player -> true;

    boolean isAuthenticated(Player player);
}
