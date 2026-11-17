// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.merchant.MerchantOffer;

class ConfigTest {

    private static YamlConfiguration load() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
            ConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    private static String plain(final Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void defaultsParse() {
        final SkarbiecConfig cfg = new SkarbiecConfig(load(), Logger.getAnonymousLogger());
        assertEquals(List.of(10L, 100L, 1000L), cfg.quickWithdraw);
        assertEquals(50_000, cfg.merchantDailyLimit);
        assertTrue(cfg.rotation.enabled());
        assertEquals(3, cfg.rotation.periodHours());
        assertEquals(100_000, cfg.shopsMaxPrice);
        assertEquals(SkarbiecConfig.Territory.OWN_OR_WILD, cfg.shopTerritory);
        assertEquals(SkarbiecConfig.PayMode.ANYWHERE, cfg.payMode);
        assertFalse(cfg.merchantToAccount);
        assertEquals(10, cfg.tradeRange);
        assertEquals(3, cfg.tradeCountdownSeconds);
        assertTrue(cfg.shopContainers.contains(Material.BARREL));
    }

    /** Every default offer is a real item, and the merchant never sells below its buying price (no arbitrage loop). */
    @Test
    void priceListIsSane() {
        final SkarbiecConfig cfg = new SkarbiecConfig(load(), Logger.getAnonymousLogger());
        final List<String> raw = new ArrayList<>();
        for (final var map : load().getMapList("merchant.offers")) {
            raw.add(String.valueOf(map.get("item")));
        }
        assertEquals(raw.size(), cfg.offers.size(), "every configured offer must load: " + raw);
        for (final MerchantOffer o : cfg.offers) {
            assertTrue(o.buys() || o.sells(), o.key());
            assertTrue(o.amount() >= 1 && o.amount() <= 64, o.key());
            if (o.sells() && o.buys()) {
                assertTrue(o.sell() > o.buy(), o.key());
            }
        }
        assertTrue(cfg.offers.stream().anyMatch(o -> o.material() == Material.WHEAT));
        assertTrue(cfg.offers.stream().anyMatch(o -> o.material() == Material.IRON_INGOT));
    }

    @Test
    void allMessagesParseWithoutLeftovers() {
        final YamlConfiguration yaml = load();
        final Messages messages = new Messages(yaml, Logger.getAnonymousLogger());
        final var section = yaml.getConfigurationSection("messages");
        for (final String key : section.getKeys(true)) {
            if (!section.isString(key) && !section.isList(key)) {
                continue;
            }
            final Component c = messages.get(key, Placeholder.unparsed("player", "Gracz"), Placeholder.unparsed("amount", "1 zł"));
            assertFalse(plain(c).contains("<prefix>"), key);
            assertFalse(plain(c).contains("<amount>"), key);
        }
    }

    @Test
    void userInputIsNotParsed() {
        final Messages messages = new Messages(load(), Logger.getAnonymousLogger());
        final Component c = messages.get("unknown-player", Placeholder.unparsed("player", "<red>Zły</red>"));
        assertTrue(plain(c).contains("<red>Zły</red>"));
    }

    private static final Pattern KEY_CALL = Pattern.compile(
        "\\b(?:m|m\\(\\)|messages\\(\\)|msg)\\.(?:send\\([^,()]+(?:\\(\\))?,|get\\(|item\\(|lines\\(|raw\\()\\s*\"([a-z][a-z0-9.\\-]*[a-z0-9])\"");

    /** Every literal message key used in the code exists in the default config.yml. */
    @Test
    void everyMessageKeyInCodeExists() throws IOException {
        final Messages messages = new Messages(load(), Logger.getAnonymousLogger());
        final List<String> missing = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (final Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                final String src = Files.readString(f);
                final Matcher m = KEY_CALL.matcher(src);
                while (m.find()) {
                    found++;
                    if (!messages.has(m.group(1))) {
                        missing.add(f.getFileName() + ": " + m.group(1));
                    }
                }
                // Keys chosen by a ternary: m.get(cond ? "a" : "b")
                final Matcher t = Pattern.compile("\\?\\s*\"([a-z][a-z0-9\\-]*\\.[a-z0-9.\\-]*[a-z0-9])\"\\s*:\\s*\"([a-z][a-z0-9\\-]*\\.[a-z0-9.\\-]*[a-z0-9])\"").matcher(src);
                while (t.find()) {
                    for (final String k : List.of(t.group(1), t.group(2))) {
                        if (k.contains(".") && !messages.has(k)) {
                            missing.add(f.getFileName() + ": " + k);
                        }
                    }
                }
            }
        }
        assertTrue(found > 100, "scanner found only " + found + " keys");
        assertTrue(missing.isEmpty(), "brak komunikatów: " + missing);
    }
}
