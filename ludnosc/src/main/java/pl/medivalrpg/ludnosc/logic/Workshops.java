// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/**
 * Production chains of the village workshops (pure, unit tested). Items are plain material names
 * ({@code SAND}, {@code OAK_LOG}); a step only ever turns items of the workshop's own real store into other items —
 * nothing is created without its inputs:
 * <ul>
 *   <li>glassworks: sand → glass (1 smelt each),</li>
 *   <li>brickworks: clay ball → brick (1 smelt), 4 clay balls → terracotta (1 smelt),</li>
 *   <li>smithy: raw iron → iron ingot (1 smelt); 1 log + 4 coal / charcoal → 16 torches,</li>
 *   <li>sheepfold barn: 9 wheat → 1 hay bale,</li>
 *   <li>every kiln: logs → charcoal when it has nothing to burn (the very first charcoal burns a second log).</li>
 * </ul>
 * Fuel is counted in smelts: one coal / charcoal gives {@code smeltsPerFuel} (vanilla 8); smelts left over are kept
 * between steps ({@code fuel}).
 */
public final class Workshops {

    /** What a step does. */
    public enum Kind {
        GLASS("wytapia szkło"), BRICK("wypala cegły"), TERRACOTTA("wypala terakotę"), IRON("wytapia żelazo"),
        TORCHES("robi pochodnie"), CHARCOAL("wypala węgiel drzewny"), HAY("prasuje bele siana");

        private final String label;

        Kind(final String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    /** Recipe parameters (from {@code workshops.*}). */
    public record Params(int batch, int smeltsPerFuel, int brickStock, int terracottaStock, int torchStock, int hayStock,
                         boolean smithy) {
        public static final Params DEFAULTS = new Params(8, 8, 64, 16, 32, 4, true);
    }

    /**
     * One step: items taken, items made, smelts of fuel left afterwards, the material group it makes (for the
     * "does the country produce it" check).
     */
    public record Step(Kind kind, Map<String, Integer> costs, Map<String, Integer> outputs, int fuelAfter, String group) {

        public String describe() {
            final StringBuilder sb = new StringBuilder(this.kind.label());
            sb.append(" (");
            sb.append(join(this.costs)).append(" → ").append(join(this.outputs)).append(')');
            return sb.toString();
        }

        private static String join(final Map<String, Integer> m) {
            final List<String> parts = new ArrayList<>();
            m.forEach((k, v) -> parts.add(v + "× " + k.toLowerCase(Locale.ROOT)));
            return String.join(" + ", parts);
        }
    }

    /** The decision: a step to commit, or why nothing can be made now (and which group starves). */
    public record Decision(Step step, String reason, String starving) {

        static Decision of(final Step s) {
            return new Decision(s, null, null);
        }

        static Decision idle(final String reason, final String starving) {
            return new Decision(null, reason, starving);
        }
    }

    /** An input the haulers should bring into the workshop's store. */
    public enum Input {
        FUEL("węgiel"), LOG("kłody"), RAW_IRON("surowe żelazo"), WHEAT("pszenica");

        private final String label;

        Input(final String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        /** Whether a stored item counts as this input. */
        public boolean matches(final String item) {
            final String m = item == null ? "" : item.toUpperCase(Locale.ROOT);
            return switch (this) {
                case FUEL -> m.equals("COAL") || m.equals("CHARCOAL");
                case LOG -> isLog(m);
                case RAW_IRON -> m.equals("RAW_IRON");
                case WHEAT -> m.equals("WHEAT");
            };
        }
    }

    /** A request for the haulers: the input and how many items. */
    public record Want(Input input, int amount) {
    }

    private Workshops() {
    }

    /** Logs and stems that burn into charcoal (also stripped ones; not deepslate). */
    public static boolean isLog(final String item) {
        final String m = item == null ? "" : item.toUpperCase(Locale.ROOT);
        return (m.endsWith("_LOG") || m.endsWith("_WOOD")) && !m.startsWith("DEEPSLATE") && !m.startsWith("CRIMSON")
            && !m.startsWith("WARPED");
    }

    /** The workshops with a production chain. */
    public static boolean workshop(final BuildingFunction f) {
        return f == BuildingFunction.GLASSWORKS || f == BuildingFunction.BRICKWORKS || f == BuildingFunction.SHEEPFOLD
            || f == BuildingFunction.SMITHY;
    }

    /** Material groups a workshop makes. */
    public static List<String> groups(final BuildingFunction f) {
        if (f == null) {
            return List.of();
        }
        return switch (f) {
            case GLASSWORKS -> List.of(MaterialNeeds.GLASS, MaterialNeeds.SAND);
            case BRICKWORKS -> List.of(MaterialNeeds.BRICK, MaterialNeeds.TERRACOTTA);
            case SHEEPFOLD -> List.of(MaterialNeeds.WOOL, MaterialNeeds.HAY);
            case SMITHY -> List.of(MaterialNeeds.IRON, MaterialNeeds.TORCH);
            default -> List.of();
        };
    }

    /**
     * Finished goods of a workshop (what the haulers may take out to the warehouse or to a site; inputs and fuel stay).
     * Charcoal of the smithy counts as its product: it goes to the kilns.
     */
    public static boolean output(final BuildingFunction f, final String item) {
        final String m = item == null ? "" : item.toUpperCase(Locale.ROOT);
        if (f == null) {
            return false;
        }
        return switch (f) {
            case GLASSWORKS -> m.equals("GLASS");
            case BRICKWORKS -> m.equals("BRICK") || m.equals("TERRACOTTA");
            case SHEEPFOLD -> m.endsWith("_WOOL") || m.equals("HAY_BLOCK");
            case SMITHY -> m.equals("IRON_INGOT") || m.equals("TORCH") || m.equals("CHARCOAL");
            default -> false;
        };
    }

    private static int count(final Map<String, Integer> stock, final String item) {
        return stock.getOrDefault(item, 0);
    }

    private static String mostLogs(final Map<String, Integer> stock) {
        String best = null;
        int n = 0;
        for (final Map.Entry<String, Integer> e : stock.entrySet()) {
            if (isLog(e.getKey()) && e.getValue() > n) {
                best = e.getKey();
                n = e.getValue();
            }
        }
        return best;
    }

    /**
     * Fuel for {@code want} smelts with {@code fuel} smelts left: charcoal first, then coal. Returns
     * {smelts possible, charcoal used, coal used, fuel left after}.
     */
    public static int[] burn(final int want, final int fuel, final int charcoal, final int coal, final int perFuel) {
        int left = Math.max(0, fuel);
        int ch = 0;
        int co = 0;
        while (left < want && ch < charcoal) {
            ch++;
            left += perFuel;
        }
        while (left < want && co < coal) {
            co++;
            left += perFuel;
        }
        final int done = Math.min(want, left);
        return new int[] {done, ch, co, left - done};
    }

    /** A smelting step of {@code n} wanted smelts: adds the fuel to {@code costs}, null when nothing can burn. */
    private static int[] fuelFor(final int n, final Map<String, Integer> stock, final int fuel, final Params p) {
        final int[] b = burn(n, fuel, count(stock, "CHARCOAL"), count(stock, "COAL"), p.smeltsPerFuel());
        return b[0] <= 0 ? null : b;
    }

    private static void addFuel(final Map<String, Integer> costs, final int[] burnt) {
        if (burnt[1] > 0) {
            costs.merge("CHARCOAL", burnt[1], Integer::sum);
        }
        if (burnt[2] > 0) {
            costs.merge("COAL", burnt[2], Integer::sum);
        }
    }

    /** Logs → charcoal when the kiln has something to smelt but nothing to burn; null when there are no logs. */
    private static Step charcoal(final Map<String, Integer> stock, final int fuel, final Params p, final String group) {
        final String log = mostLogs(stock);
        if (log == null) {
            return null;
        }
        final int logs = count(stock, log);
        if (fuel >= 1) {
            final int n = Math.min(Math.min(p.batch(), logs), fuel);
            return new Step(Kind.CHARCOAL, Map.of(log, n), Map.of("CHARCOAL", n), fuel - n, group);
        }
        if (logs >= 2) { // the first log burns (1.5 smelts → 1), the second becomes charcoal
            return new Step(Kind.CHARCOAL, Map.of(log, 2), Map.of("CHARCOAL", 1), 0, group);
        }
        return null;
    }

    private static Decision smelt(final Kind kind, final String input, final int perOutput, final String output, final int max,
                                  final Map<String, Integer> stock, final int fuel, final Params p, final String group) {
        final int have = count(stock, input) / perOutput;
        final int n = Math.min(Math.min(p.batch(), have), max);
        if (n <= 0) {
            return null;
        }
        final int[] b = fuelFor(n, stock, fuel, p);
        if (b == null) {
            final Step c = charcoal(stock, fuel, p, group);
            return c == null ? Decision.idle("brak paliwa (węgla, węgla drzewnego albo kłód) w magazynie", group) : Decision.of(c);
        }
        final Map<String, Integer> costs = new LinkedHashMap<>();
        costs.put(input, b[0] * perOutput);
        addFuel(costs, b);
        return Decision.of(new Step(kind, costs, Map.of(output, b[0]), b[3], group));
    }

    /**
     * The next step of a glassworks, brickworks, smithy or sheepfold barn from its store contents.
     *
     * @param stock    plain items of the store by material name
     * @param fuel     smelts of fuel left from earlier steps
     * @param demanded material groups the country's building sites wait for now
     */
    public static Decision next(final BuildingFunction f, final Map<String, Integer> stock, final int fuel, final Set<String> demanded,
                                final Params p) {
        return next(f, stock, fuel, demanded, p, false);
    }

    /** Plain items of a store without its coal and charcoal (the smithy leaves them to the kilns). */
    private static Map<String, Integer> withoutFuel(final Map<String, Integer> stock) {
        final Map<String, Integer> out = new LinkedHashMap<>(stock);
        out.remove("COAL");
        out.remove("CHARCOAL");
        return out;
    }

    /**
     * The next step with fuel priorities: while a kiln of the country (glassworks, brickworks) lacks fuel
     * ({@code kilnsHungry}), the smithy burns no new coal or charcoal — it only finishes smelts already paid for and
     * turns logs into charcoal for the kilns; torches wait. Glass and bricks come first, then iron, then torches.
     */
    public static Decision next(final BuildingFunction f, final Map<String, Integer> stock, final int fuel, final Set<String> demanded,
                                final Params p, final boolean kilnsHungry) {
        if (f == BuildingFunction.SMITHY && kilnsHungry && p.smithy()) {
            final Map<String, Integer> own = withoutFuel(stock);
            final Decision iron = smelt(Kind.IRON, "RAW_IRON", 1, "IRON_INGOT", Integer.MAX_VALUE, own, fuel, p, MaterialNeeds.IRON);
            if (iron != null && iron.step() != null && iron.step().kind() == Kind.IRON) {
                return iron; // smelts already paid for
            }
            final Step c = charcoal(own, fuel, p, null);
            if (c != null) {
                return Decision.of(c); // charcoal for the hungry kilns
            }
            return Decision.idle("węgiel zostawiony dla huty szkła i cegielni (piece mają pierwszeństwo)", null);
        }
        if (f == null) {
            return Decision.idle("to nie jest zakład", null);
        }
        switch (f) {
            case GLASSWORKS -> {
                Decision d = smelt(Kind.GLASS, "SAND", 1, "GLASS", Integer.MAX_VALUE, stock, fuel, p, MaterialNeeds.GLASS);
                if (d == null) {
                    d = smelt(Kind.GLASS, "RED_SAND", 1, "GLASS", Integer.MAX_VALUE, stock, fuel, p, MaterialNeeds.GLASS);
                }
                return d != null ? d : Decision.idle("brak piasku w magazynie — hutnik kopie piasek na brzegu", MaterialNeeds.GLASS);
            }
            case BRICKWORKS -> {
                final int balls = count(stock, "CLAY_BALL");
                if (balls <= 0) {
                    return Decision.idle("brak gliny w magazynie — ceglarz kopie glinę nad wodą", MaterialNeeds.BRICK);
                }
                final int bricks = count(stock, "BRICK");
                final int terra = count(stock, "TERRACOTTA");
                final boolean wantB = demanded.contains(MaterialNeeds.BRICK) || bricks < p.brickStock();
                final boolean wantT = demanded.contains(MaterialNeeds.TERRACOTTA) || terra < p.terracottaStock();
                final boolean terracotta;
                if (demanded.contains(MaterialNeeds.TERRACOTTA) != demanded.contains(MaterialNeeds.BRICK)) {
                    terracotta = demanded.contains(MaterialNeeds.TERRACOTTA);
                } else if (wantB && wantT) {
                    terracotta = terra * 4 < bricks; // keeps about one terracotta per four bricks
                } else {
                    terracotta = wantT;
                }
                if (!wantB && !wantT) {
                    return Decision.idle("zapas cegieł i terakoty pełny", null);
                }
                Decision d = terracotta
                    ? smelt(Kind.TERRACOTTA, "CLAY_BALL", 4, "TERRACOTTA", Integer.MAX_VALUE, stock, fuel, p, MaterialNeeds.TERRACOTTA)
                    : smelt(Kind.BRICK, "CLAY_BALL", 1, "BRICK", Integer.MAX_VALUE, stock, fuel, p, MaterialNeeds.BRICK);
                if (d == null && terracotta) { // fewer than four clay balls: bricks instead
                    d = smelt(Kind.BRICK, "CLAY_BALL", 1, "BRICK", Integer.MAX_VALUE, stock, fuel, p, MaterialNeeds.BRICK);
                }
                return d == null ? Decision.idle("za mało gliny", MaterialNeeds.BRICK) : d;
            }
            case SMITHY -> {
                if (!p.smithy()) {
                    return Decision.idle("kuźnia nie produkuje (workshops.smithy.enabled: false)", null);
                }
                final Decision iron = smelt(Kind.IRON, "RAW_IRON", 1, "IRON_INGOT", Integer.MAX_VALUE, stock, fuel, p, MaterialNeeds.IRON);
                if (iron != null && iron.step() != null) {
                    return iron;
                }
                final int torches = count(stock, "TORCH");
                final String log = mostLogs(stock);
                final int coal = count(stock, "COAL") + count(stock, "CHARCOAL");
                // a demand only raises the stock target (the haulers take the torches to the sites); never endless
                if (torches < p.torchStock() * (demanded.contains(MaterialNeeds.TORCH) ? 2 : 1)) {
                    if (log != null && coal >= 4) {
                        final Map<String, Integer> costs = new LinkedHashMap<>();
                        costs.put(log, 1);
                        final int ch = Math.min(4, count(stock, "CHARCOAL"));
                        if (ch > 0) {
                            costs.put("CHARCOAL", ch);
                        }
                        if (ch < 4) {
                            costs.put("COAL", 4 - ch);
                        }
                        return Decision.of(new Step(Kind.TORCHES, costs, Map.of("TORCH", 16), fuel, MaterialNeeds.TORCH));
                    }
                    if (log != null && coal < 4 && count(stock, "RAW_IRON") == 0 && count(stock, log) >= 3) {
                        final Step c = charcoal(stock, fuel, p, MaterialNeeds.TORCH);
                        if (c != null) {
                            return Decision.of(c);
                        }
                    }
                    if (iron != null) {
                        return iron; // raw iron waits for fuel
                    }
                    return Decision.idle("brak surowego żelaza, a na pochodnie brak kłody albo 4 węgli", MaterialNeeds.TORCH);
                }
                return iron != null ? iron : Decision.idle("brak surowego żelaza (z kopalni)", MaterialNeeds.IRON);
            }
            case SHEEPFOLD -> {
                final int wheat = count(stock, "WHEAT");
                final int hay = count(stock, "HAY_BLOCK");
                if (hay >= p.hayStock() * (demanded.contains(MaterialNeeds.HAY) ? 4 : 1)) {
                    return Decision.idle("zapas siana pełny", null);
                }
                final int n = Math.min(4, wheat / 9);
                if (n <= 0) {
                    return Decision.idle("brak pszenicy na bele siana (9 na belę) — tragarze przyniosą z pól", MaterialNeeds.HAY);
                }
                return Decision.of(new Step(Kind.HAY, Map.of("WHEAT", n * 9), Map.of("HAY_BLOCK", n), fuel, MaterialNeeds.HAY));
            }
            default -> {
                return Decision.idle("to nie jest zakład", null);
            }
        }
    }

    /**
     * What the haulers should bring into the workshop's store (most important first): raw iron and fuel for the
     * smithy, fuel for a kiln holding raw material, wheat for hay bales and lambs, logs when no coal can be had.
     *
     * @param lambs lambs still wanted by the sheepfold (2 wheat each)
     */
    public static List<Want> wants(final BuildingFunction f, final Map<String, Integer> stock, final int fuel, final Set<String> demanded,
                                   final Params p, final int lambs) {
        return wants(f, stock, fuel, demanded, p, lambs, false);
    }

    /** {@link #wants} with fuel priorities: while a kiln lacks fuel the smithy asks for no coal (logs for charcoal only). */
    public static List<Want> wants(final BuildingFunction f, final Map<String, Integer> stock, final int fuel, final Set<String> demanded,
                                   final Params p, final int lambs, final boolean kilnsHungry) {
        if (f == BuildingFunction.SMITHY && kilnsHungry) {
            final List<Want> out = new ArrayList<>();
            if (p.smithy() && count(stock, "RAW_IRON") < 32) {
                out.add(new Want(Input.RAW_IRON, 32 - count(stock, "RAW_IRON")));
            }
            return out;
        }
        final List<Want> out = new ArrayList<>();
        if (f == null) {
            return out;
        }
        final int coal = count(stock, "COAL") + count(stock, "CHARCOAL");
        final boolean noFuel = fuel <= 0 && coal == 0;
        final boolean logs = mostLogs(stock) != null;
        switch (f) {
            case GLASSWORKS, BRICKWORKS -> {
                final int raw = f == BuildingFunction.GLASSWORKS ? count(stock, "SAND") + count(stock, "RED_SAND") : count(stock, "CLAY_BALL");
                if (raw > 0 && noFuel) {
                    out.add(new Want(Input.FUEL, 16));
                    if (!logs) {
                        out.add(new Want(Input.LOG, 9));
                    }
                }
            }
            case SMITHY -> {
                if (!p.smithy()) {
                    return out;
                }
                final int raw = count(stock, "RAW_IRON");
                if (raw < 32) {
                    out.add(new Want(Input.RAW_IRON, 32 - raw));
                }
                final boolean torches = count(stock, "TORCH") < p.torchStock() * (demanded.contains(MaterialNeeds.TORCH) ? 2 : 1);
                if (raw > 0 && noFuel || torches && coal < 4) {
                    out.add(new Want(Input.FUEL, 16));
                }
                if (torches && !logs || raw > 0 && noFuel && !logs) {
                    out.add(new Want(Input.LOG, torches ? 2 : 9));
                }
            }
            case SHEEPFOLD -> {
                final int wheat = count(stock, "WHEAT");
                final boolean hay = count(stock, "HAY_BLOCK") < p.hayStock() * (demanded.contains(MaterialNeeds.HAY) ? 4 : 1);
                final int want = (hay ? 9 : 0) + Math.max(0, lambs) * 2;
                if (wheat < want) {
                    out.add(new Want(Input.WHEAT, Math.min(36, want - wheat + (hay ? 9 : 0))));
                }
            }
            default -> {
            }
        }
        return out;
    }

    /**
     * Whether a natural surface block may be dug for the workshop: the right kind (sand for glass, clay for bricks),
     * solid ground below (no hole into a cave), no liquid next to a dry block (no new stream), the column is not
     * already deeper than every neighbour (the shore is lowered layer by layer, never pitted) and dry sand stays above
     * the sea level.
     *
     * @param above        block above: "air", "water" or "solid"
     * @param wetSides     liquid in one of the four horizontal neighbours
     * @param lowestSide   lowest top (y) of the four neighbour columns
     */
    public static boolean diggable(final String kind, final String material, final int y, final boolean solidBelow, final String above,
                                   final boolean wetSides, final int lowestSide, final int seaLevel) {
        final String m = material == null ? "" : material.toUpperCase(Locale.ROOT);
        final boolean sand = "SAND".equals(kind);
        if (sand ? !m.equals("SAND") && !m.equals("RED_SAND") : !m.equals("CLAY")) {
            return false;
        }
        if (!solidBelow || "solid".equals(above) || y < lowestSide) {
            return false;
        }
        if ("water".equals(above)) {
            return !sand; // clay of a riverbed may be dug under water; beach sand only when dry
        }
        if (wetSides) {
            return false;
        }
        return !sand || y >= seaLevel;
    }
}
