// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.nowosci.Fixtures;
import pl.medivalrpg.nowosci.config.NowosciConfig;
import pl.medivalrpg.nowosci.model.Entry;

class DiscordFormatTest {

    private final NowosciConfig cfg = Fixtures.format().config();

    @Test
    void commandsBecomeCodeAndHighlightsBold() {
        assertEquals("Wpisz `/armia flota zbuduj transportowiec|karawela` i **szybko** płyń.",
            DiscordFormat.point("Wpisz <cmd>/armia flota zbuduj transportowiec|karawela</cmd> i <hl>szybko</hl> płyń."));
    }

    @Test
    void otherTagsAreDroppedAndMarkdownEscapedOutsideCode() {
        assertEquals("Uwaga \\*gwiazdki\\* i \\_podkreślenia\\_ `a*b_c`",
            DiscordFormat.point("<red>Uwaga</red> *gwiazdki* i _podkreślenia_ <cmd>a*b_c</cmd>"));
        assertEquals("/armia rekrutuj <typ>", DiscordFormat.point("/armia rekrutuj \\<typ>"));
    }

    @Test
    void payloadHasEmbedWithCategoryColourFooterAndMentionRules() {
        final Entry entry = new Entry(42, LocalDate.of(2026, 10, 27), "Pokład statku", "nowe",
            List.of("Pierwszy <cmd>/armia flota wejdz</cmd>", "Drugi"));
        final JsonObject payload = DiscordFormat.payload(entry, this.cfg.category("nowe"), this.cfg.dateFormat,
            "MEDIVALRPG", "", "<@&123>");
        assertEquals("MEDIVALRPG", payload.get("username").getAsString());
        assertFalse(payload.has("avatar_url"));
        assertEquals("<@&123>", payload.get("content").getAsString());
        assertEquals("roles", payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").get(0).getAsString());
        final JsonObject embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("✦ Pokład statku", embed.get("title").getAsString());
        assertEquals("• Pierwszy `/armia flota wejdz`\n• Drugi", embed.get("description").getAsString());
        assertEquals(0x55FF55, embed.get("color").getAsInt());
        assertTrue(embed.getAsJsonObject("footer").get("text").getAsString().contains("27.10.2026 · wpis #42"));
    }

    @Test
    void withoutMentionNothingCanPing() {
        final Entry entry = new Entry(1, null, "x", "zmiana", List.of());
        final JsonObject payload = DiscordFormat.payload(entry, this.cfg.category("zmiana"), this.cfg.dateFormat, "", "", "");
        assertFalse(payload.has("content"));
        assertFalse(payload.has("username"));
    }
}
