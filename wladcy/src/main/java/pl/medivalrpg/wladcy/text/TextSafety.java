// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.text;

/**
 * Cleaning of untrusted text (player chat and model output). The result is plain text meant for
 * {@code Component.text(...)} / {@code Placeholder.unparsed(...)}; nothing here is ever parsed as MiniMessage.
 * No Bukkit calls.
 */
public final class TextSafety {

    private TextSafety() {
    }

    /**
     * Plain one-line text: control characters and line breaks become spaces, legacy colour codes ({@code §x}) and
     * zero-width/bidi characters are removed, whitespace is collapsed, and the result is cut to {@code maxChars}
     * (with an ellipsis).
     */
    public static String clean(final String input, final int maxChars) {
        if (input == null) {
            return "";
        }
        final StringBuilder b = new StringBuilder(Math.min(input.length(), maxChars + 16));
        boolean space = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '§') {
                i++; // drop the code character too
                continue;
            }
            if (isInvisible(ch)) {
                continue;
            }
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)) {
                ch = ' ';
            }
            if (ch == ' ') {
                if (space || b.isEmpty()) {
                    continue;
                }
                space = true;
            } else {
                space = false;
            }
            b.append(ch);
        }
        String s = b.toString().strip();
        if (s.length() > maxChars) {
            s = s.substring(0, Math.max(0, maxChars - 1)).stripTrailing() + "…";
        }
        return s;
    }

    /** Model output for the chat: {@link #clean} plus removal of Markdown emphasis/heading marks. */
    public static String modelReply(final String input, final int maxChars) {
        if (input == null) {
            return "";
        }
        final String noMarkdown = input
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "")
            .replaceAll("(?m)^\\s*[-*]\\s+", "");
        return clean(noMarkdown, maxChars);
    }

    /**
     * Player text placed inside the prompt between our own tags: angle brackets are replaced so a player cannot
     * close {@code <wiadomosc_gracza>} or open fake instruction tags.
     */
    public static String forPrompt(final String input, final int maxChars) {
        return clean(input, maxChars).replace('<', '‹').replace('>', '›');
    }

    private static boolean isInvisible(final char ch) {
        return (ch >= '​' && ch <= '‏') || (ch >= '‪' && ch <= '‮') || (ch >= '⁦' && ch <= '⁩') || ch == '﻿';
    }
}
