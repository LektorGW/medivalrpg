// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

/** Read-only projection of ProductionStorageService.feed, including capacity and whole-item rounding. */
public final class FoodReserve {
    private final long capacity, required;
    private long food;

    public FoodReserve(long stored, long capacity, long required) {
        this.food = Math.max(0, stored);
        this.capacity = Math.max(0, capacity);
        this.required = Math.max(0, required);
    }

    public void include(int amount, int value) {
        if (amount <= 0 || value <= 0) return;
        long need = Math.max(0, required - food);
        long room = Math.max(0, capacity - food);
        long count = Math.min(amount, Math.min(need / value + (need % value == 0 ? 0 : 1), room / value));
        food += count * value;
    }

    public long available() { return Math.min(food, required); }
    public long required() { return required; }
    public boolean sufficient() { return food >= required; }
}
