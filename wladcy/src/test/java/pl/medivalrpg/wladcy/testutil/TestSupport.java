// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.testutil;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.medivalrpg.wladcy.config.WladcyConfig;

/** Shared helpers: the real config.yml with overrides, and a clock tests can move. */
public final class TestSupport {

    private TestSupport() {
    }

    public static YamlConfiguration yaml() {
        try (InputStream in = TestSupport.class.getResourceAsStream("/config.yml")) {
            if (in == null) {
                throw new IllegalStateException("config.yml not on the test classpath");
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static WladcyConfig config(final Map<String, Object> overrides) {
        final YamlConfiguration y = yaml();
        overrides.forEach(y::set);
        return new WladcyConfig(y, null);
    }

    public static WladcyConfig config() {
        return config(Map.of());
    }

    /** A clock that only moves when told to (UTC). */
    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(final Instant start) {
            this.now = start;
        }

        public void advanceMillis(final long ms) {
            this.now = this.now.plusMillis(ms);
        }

        public void set(final Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }
}
