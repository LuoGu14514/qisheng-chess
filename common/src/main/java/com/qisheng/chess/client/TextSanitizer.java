package com.qisheng.chess.client;

/**
 * Text sanitiser for the chess GUI.
 *
 * <p>Player names and chat lines are attacker-controlled: a name containing
 * the legacy formatting marker {@code §} (U+00A7) would otherwise be rendered
 * as a colour / obfuscation code ({@code §c}, {@code §k}, …), which lets a
 * player forge styled or scrambled text inside somebody else's GUI. Every
 * string that reaches a {@code Component.literal(...)} or a direct
 * {@code drawString} is passed through {@link #strip(String)} first.
 */
final class TextSanitizer {

    /** Legacy Minecraft formatting marker. */
    private static final char SECTION_SIGN = '\u00A7';

    private TextSanitizer() {}

    /**
     * Removes every {@code §} from {@code s}.
     *
     * <p>Dropping the marker alone is enough: without it the following
     * character is ordinary text ({@code "§cRed"} renders as {@code "cRed"})
     * and the renderer can no longer switch style mid-string.
     */
    static String strip(String s) {
        if (s == null) return "";
        if (s.indexOf(SECTION_SIGN) < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != SECTION_SIGN) out.append(c);
        }
        return out.toString();
    }
}
