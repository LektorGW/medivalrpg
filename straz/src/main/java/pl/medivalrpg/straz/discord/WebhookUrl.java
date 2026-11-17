// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.discord;

import java.util.regex.Pattern;

/**
 * Validation and masking of the webhook address (pure). Accepted: exactly
 * {@code https://discord.com/api/webhooks/<17–20 digits>/<token>} (also {@code /api/v10/} and the
 * {@code discordapp.com} host), without query, fragment or trailing slash — like the commands synchronizer.
 * Loopback {@code http://127.0.0.1:<port>/api/webhooks/<id>/<token>} only when explicitly allowed (isolated tests).
 */
public final class WebhookUrl {

    private static final Pattern DISCORD = Pattern.compile(
        "^https://(?:discord\\.com|discordapp\\.com)/api(?:/v10)?/webhooks/\\d{17,20}/[A-Za-z0-9_-]{30,200}$");
    private static final Pattern LOOPBACK = Pattern.compile(
        "^http://127\\.0\\.0\\.1:\\d{2,5}/api(?:/v10)?/webhooks/\\d{17,20}/[A-Za-z0-9_-]{30,200}$");

    private WebhookUrl() {
    }

    public static boolean valid(final String url, final boolean allowLoopback) {
        if (url == null) {
            return false;
        }
        final String u = url.strip();
        return DISCORD.matcher(u).matches() || allowLoopback && LOOPBACK.matcher(u).matches();
    }

    /** Safe for logs: host and webhook id only. */
    public static String mask(final String url) {
        if (url == null || url.isBlank()) {
            return "(brak)";
        }
        final String u = url.strip();
        final int hooks = u.indexOf("/webhooks/");
        if (hooks < 0) {
            return "(niepoprawny adres)";
        }
        final String rest = u.substring(hooks + 10);
        final int slash = rest.indexOf('/');
        final String id = slash < 0 ? rest : rest.substring(0, slash);
        final String host = u.startsWith("http://127.0.0.1") ? "127.0.0.1 (test)" : "discord";
        return host + " webhook " + (id.length() > 6 ? "…" + id.substring(id.length() - 4) : id) + "/****";
    }
}
