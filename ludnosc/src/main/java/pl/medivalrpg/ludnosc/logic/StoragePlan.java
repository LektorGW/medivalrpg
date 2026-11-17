// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pure all-or-nothing inventory planning. Keys distinguish material AND metadata; null is an empty slot. */
public final class StoragePlan {
    public record Stack(String key, int amount, int max) {
        public Stack { if (key == null || amount <= 0 || max <= 0 || amount > max) throw new IllegalArgumentException("stack"); }
    }
    private StoragePlan() { }

    public static Optional<List<Stack>> plan(List<Stack> before, List<Stack> outputs, List<Stack> costs) {
        final var slots = new ArrayList<>(before);
        for (var cost : costs) {
            int left = cost.amount();
            for (int i = 0; i < slots.size() && left > 0; i++) {
                final var s = slots.get(i);
                if (s == null || !s.key().equals(cost.key())) continue;
                final int n = Math.min(left, s.amount()); left -= n;
                slots.set(i, n == s.amount() ? null : new Stack(s.key(), s.amount() - n, s.max()));
            }
            if (left != 0) return Optional.empty();
        }
        for (var output : outputs) {
            int left = output.amount();
            for (int pass = 0; pass < 2 && left > 0; pass++) for (int i = 0; i < slots.size() && left > 0; i++) {
                var s = slots.get(i);
                if (pass == 0 && s != null && s.key().equals(output.key())) {
                    int n = Math.min(left, Math.min(s.max(), output.max()) - s.amount());
                    if (n > 0) { slots.set(i, new Stack(s.key(), s.amount() + n, s.max())); left -= n; }
                } else if (pass == 1 && s == null) {
                    int n = Math.min(left, output.max()); slots.set(i, new Stack(output.key(), n, output.max())); left -= n;
                }
            }
            if (left != 0) return Optional.empty();
        }
        return Optional.of(java.util.Collections.unmodifiableList(slots));
    }
}
