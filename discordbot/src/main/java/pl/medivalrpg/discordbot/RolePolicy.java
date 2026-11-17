// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import java.util.*;
public final class RolePolicy {
    public static Set<String> removable(Set<String> current,Set<String> managed,Set<String> desired,Set<String> botGranted,Set<String> neverRevoke) {
        Set<String> out=new HashSet<>(current);out.retainAll(managed);out.retainAll(botGranted);out.removeAll(desired);out.removeAll(neverRevoke);return Set.copyOf(out);
    }
}
