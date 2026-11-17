// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Typed, validated access to RPC {@code params}. Every getter throws {@link RpcException} with a Polish message
 * naming the parameter. Pure (Gson only), no Bukkit.
 */
public final class Params {

    /** Minecraft nick. */
    public static final Pattern NICK = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** Mojang skin name (1–16). */
    public static final Pattern SKIN = Pattern.compile("[A-Za-z0-9_]{1,16}");
    /** Unit type / config ids. */
    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern HEX = Pattern.compile("#?([0-9a-fA-F]{6})");
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cntrl}§\\u200B-\\u200F\\u2028-\\u202E\\u2066-\\u2069\\uFEFF]");

    private final JsonObject json;

    public Params(final JsonObject json) {
        this.json = json == null ? new JsonObject() : json;
    }

    /** {@code params} of a message: object, or missing/null → empty; anything else is invalid. */
    public static Params of(final JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return new Params(new JsonObject());
        }
        if (!element.isJsonObject()) {
            throw new RpcException("Parametry muszą być obiektem JSON.");
        }
        return new Params(element.getAsJsonObject());
    }

    static RpcException bad(final String name, final String what) {
        return new RpcException("Nieprawidłowy parametr „" + name + "”: " + what + ".");
    }

    private JsonElement raw(final String name) {
        final JsonElement e = this.json.get(name);
        return e == null || e.isJsonNull() ? null : e;
    }

    /** Raw JSON value of a parameter (null when missing or JSON null) — for structured parameters like a plan. */
    public JsonElement element(final String name) {
        return this.raw(name);
    }

    /** Present and not JSON null. */
    public boolean has(final String name) {
        return this.raw(name) != null;
    }

    /** Missing, JSON null or an empty string. */
    public boolean absentOrEmpty(final String name) {
        final JsonElement e = this.raw(name);
        return e == null || (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && clean(e.getAsString()).isEmpty());
    }

    /** Whether the key is present with an explicit JSON null. */
    public boolean isNull(final String name) {
        return this.json.has(name) && this.json.get(name) instanceof JsonNull;
    }

    private JsonPrimitive primitive(final String name) {
        final JsonElement e = this.raw(name);
        if (e == null) {
            throw new RpcException("Brak parametru „" + name + "”.");
        }
        if (!e.isJsonPrimitive()) {
            throw bad(name, "oczekiwano wartości prostej");
        }
        return e.getAsJsonPrimitive();
    }

    /** Text with control/invisible characters removed and trimmed; length checked after cleaning. */
    public String string(final String name, final int min, final int max) {
        final JsonPrimitive p = this.primitive(name);
        if (!p.isString()) {
            throw bad(name, "oczekiwano tekstu");
        }
        final String raw = p.getAsString();
        if (raw.length() > max * 4 + 16) {
            throw bad(name, "długość " + min + "–" + max + " znaków");
        }
        final String s = clean(raw);
        if (s.length() < min || s.length() > max) {
            throw bad(name, "długość " + min + "–" + max + " znaków");
        }
        return s;
    }

    public String optString(final String name, final int min, final int max, final String def) {
        return this.has(name) ? this.string(name, min, max) : def;
    }

    /** Removes control characters, bidi/zero-width characters and the legacy section sign; trims. */
    public static String clean(final String s) {
        return s == null ? "" : INVISIBLE.matcher(s).replaceAll("").trim();
    }

    public String matching(final String name, final Pattern pattern, final String what) {
        final String s = this.string(name, 1, 64);
        if (!pattern.matcher(s).matches()) {
            throw bad(name, what);
        }
        return s;
    }

    public String nick(final String name) {
        return this.matching(name, NICK, "nick gracza (3–16 znaków: litery, cyfry, _)");
    }

    public UUID uuid(final String name) {
        final String s = this.string(name, 1, 64);
        try {
            final UUID u = UUID.fromString(s);
            if (!u.toString().equalsIgnoreCase(s)) {
                throw new IllegalArgumentException();
            }
            return u;
        } catch (final IllegalArgumentException e) {
            throw bad(name, "oczekiwano identyfikatora UUID");
        }
    }

    public long integer(final String name, final long min, final long max) {
        final JsonPrimitive p = this.primitive(name);
        final double d;
        if (p.isNumber()) {
            d = p.getAsDouble();
        } else if (p.isString() && p.getAsString().trim().matches("-?\\d{1,15}")) {
            d = Double.parseDouble(p.getAsString().trim());
        } else {
            throw bad(name, "oczekiwano liczby całkowitej");
        }
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) {
            throw bad(name, "oczekiwano liczby całkowitej");
        }
        if (d < min || d > max) {
            throw bad(name, "zakres " + min + "–" + max);
        }
        return (long) d;
    }

    public int integer(final String name, final int min, final int max) {
        return (int) this.integer(name, (long) min, (long) max);
    }

    public boolean bool(final String name) {
        final JsonPrimitive p = this.primitive(name);
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        throw bad(name, "oczekiwano true/false");
    }

    /** Money in copper: JSON integer or text like {@code "3zł 5sr"}; {@code signed} allows negative (non-zero). */
    public long amount(final String name, final boolean signed) {
        final JsonPrimitive p = this.primitive(name);
        final OptionalLong v;
        if (p.isNumber()) {
            v = AmountParser.ofNumber(p.getAsDouble(), signed);
        } else if (p.isString()) {
            v = AmountParser.parse(p.getAsString(), signed);
        } else {
            v = OptionalLong.empty();
        }
        if (v.isEmpty()) {
            throw bad(name, signed ? "kwota różna od zera, np. 150, \"3zł 5sr\", \"-2zł\"" : "kwota dodatnia, np. 150 (miedziaki) albo \"3zł 5sr\"");
        }
        return v.getAsLong();
    }

    /** Tax: like {@link #amount} but 0 (no tax) is allowed. */
    public long amountOrZero(final String name) {
        final JsonPrimitive p = this.primitive(name);
        if ((p.isNumber() && p.getAsDouble() == 0) || (p.isString() && p.getAsString().trim().matches("0|-|brak"))) {
            return 0;
        }
        return this.amount(name, false);
    }

    /** {@code #RRGGBB} / {@code RRGGBB} or an integer {@code 0xRRGGBB}. */
    public int color(final String name) {
        final JsonPrimitive p = this.primitive(name);
        if (p.isNumber()) {
            return (int) this.integer(name, 0, 0xFFFFFF);
        }
        final String s = p.isString() ? p.getAsString().trim() : "";
        final Matcher m = HEX.matcher(s);
        if (!m.matches()) {
            throw bad(name, "kolor w formacie #RRGGBB");
        }
        return Integer.parseInt(m.group(1), 16);
    }

    /** One of {@code allowed} (case-insensitive); returned as listed. */
    public String oneOf(final String name, final List<String> allowed) {
        final String s = this.string(name, 1, 32);
        for (final String a : allowed) {
            if (a.equalsIgnoreCase(s)) {
                return a;
            }
        }
        throw bad(name, "dozwolone: " + String.join(", ", allowed));
    }

    /** A setting value given as text, number or boolean, as text (max 16 chars). */
    public String scalar(final String name) {
        final JsonPrimitive p = this.primitive(name);
        final String s;
        if (p.isBoolean()) {
            s = p.getAsBoolean() ? "tak" : "nie";
        } else if (p.isNumber()) {
            final double d = p.getAsDouble();
            if (d != Math.rint(d) || Math.abs(d) > 1_000_000) {
                throw bad(name, "oczekiwano liczby całkowitej");
            }
            s = Long.toString((long) d);
        } else {
            s = clean(p.getAsString());
        }
        if (s.isEmpty() || s.length() > 16) {
            throw bad(name, "długość 1–16 znaków");
        }
        return s.toLowerCase(Locale.ROOT);
    }
}
