// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.nlogin;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.TreeMap;

/** Numbers only — no nicks, addresses or hashes, so it can be logged and attached to reports. */
public final class ImportReport {

    public static final String SKIP_NO_PASSWORD = "brak-hasla (konto premium/bedrock lub niedokończone)";
    public static final String SKIP_UNSUPPORTED_HASH = "nieobslugiwany-format-hasha";
    public static final String SKIP_INVALID_NICK = "nieprawidlowy-nick";
    public static final String SKIP_DUPLICATE_IN_SOURCE = "duplikat-nicku-w-nlogin (zostal nowszy)";
    public static final String SKIP_EXISTS_DIFFERENT = "konto-juz-istnieje-w-strazniku (inne haslo, nie nadpisano)";
    public static final String WARN_UUID_MISMATCH = "uuid-nlogin-rozne-od-offline-uuid-nicku (uzyto offline UUID nicku)";
    public static final String WARN_CASE_DIFFERS = "ten-sam-nick-inna-wielkosc-liter-w-strazniku (nie nadpisano)";

    public int rows;
    public int imported;
    public int alreadyImported;
    public final Map<String, Integer> skipped = new TreeMap<>();
    public final Map<String, Integer> warnings = new TreeMap<>();
    public final Map<String, Integer> hashFormats = new TreeMap<>();
    public boolean applied;
    public String sourceSha256 = "";
    public long finishedAt;

    void skip(final String reason) {
        this.skipped.merge(reason, 1, Integer::sum);
    }

    void warn(final String reason) {
        this.warnings.merge(reason, 1, Integer::sum);
    }

    void format(final String name) {
        this.hashFormats.merge(name, 1, Integer::sum);
    }

    public int skippedTotal() {
        return this.skipped.values().stream().mapToInt(Integer::intValue).sum();
    }

    public JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("zastosowano", this.applied);
        o.addProperty("wierszyNLogin", this.rows);
        o.addProperty("zaimportowano", this.imported);
        o.addProperty("juzBylo", this.alreadyImported);
        o.addProperty("pominieto", this.skippedTotal());
        final JsonObject s = new JsonObject();
        this.skipped.forEach(s::addProperty);
        o.add("powodyPominiecia", s);
        final JsonObject w = new JsonObject();
        this.warnings.forEach(w::addProperty);
        o.add("ostrzezenia", w);
        final JsonObject f = new JsonObject();
        this.hashFormats.forEach(f::addProperty);
        o.add("formatyHashy", f);
        o.addProperty("sha256PlikuZrodlowego", this.sourceSha256);
        o.addProperty("zakonczono", this.finishedAt);
        return o;
    }

    public String toPrettyJson() {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(this.toJson());
    }

    /** One line for the console. */
    public String summary() {
        return (this.applied ? "Import nLogin" : "Próba importu nLogin (bez zapisu)") + ": wierszy " + this.rows
            + ", zaimportowano " + this.imported + ", już było " + this.alreadyImported + ", pominięto " + this.skippedTotal()
            + (this.skipped.isEmpty() ? "" : " " + this.skipped) + (this.warnings.isEmpty() ? "" : ", ostrzeżenia " + this.warnings);
    }
}
