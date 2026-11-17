// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import java.util.*;

public final class Policy {
    private Policy() {}
    public static String clean(String text, int max) {
        String out = text.replace('@', '＠').replace('<', '‹').replace('>', '›')
            .replaceAll("[\\p{Cntrl}§]", " ").replace('`', '\'').replace('*', '∗').replace('_', '＿');
        return out.length() <= max ? out : out.substring(0, max);
    }
    public static Set<String> wanted(boolean linked, String country, String rank, Map<String,String> ids) {
        Set<String> out = new HashSet<>();
        if (linked) {
            add(out, ids.get("player"));
            if (country != null) { add(out, ids.get("country:" + country)); add(out, ids.get(rank)); }
        }
        return Set.copyOf(out);
    }
    private static void add(Set<String> set, String id) { if (id != null && id.matches("[0-9]{15,22}")) set.add(id); }
    public static Set<String> removals(Set<String> current, Set<String> managed, Set<String> wanted) {
        Set<String> out = new HashSet<>(current); out.retainAll(managed); out.removeAll(wanted); return out;
    }
}
