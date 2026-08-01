package net.enderlink.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Blocks messages containing configured words, in <b>both</b> directions.
 *
 * <p>Both directions matters. A bridge is a hole in your moderation: in-game chat bypasses
 * Discord's AutoMod entirely (webhook posts are not scanned), and Discord messages bypass every
 * in-game chat plugin. Filtering only one way leaves the other open.
 *
 * <p>Matching is deliberately fuzzy. Comparing raw text catches nothing — spacing, punctuation
 * and digit-for-letter swaps defeat it instantly, and anyone trying to get something past a
 * filter tries those first. So text is normalised to letters and digits only, with common
 * substitutions folded in, before the comparison.
 *
 * <p><b>The trade-off is false positives.</b> Normalising to a bare letter sequence means a
 * blocked word also matches inside longer words — the classic example being an innocent town
 * name that contains a rude substring. The word list is the operator's, so this is a choice they
 * can make deliberately; it is called out in the README rather than hidden.
 */
public final class ContentFilter {
    private final List<String> blocked = new ArrayList<>();

    public ContentFilter(List<String> words) {
        if (words != null) {
            for (String word : words) {
                // A hand-edited config can produce nulls — `"blocked-words": ["bad", null]` is
                // valid JSON, and Gson hands it straight through. Without this guard that is an
                // NPE during mod init, i.e. the server fails to start over a stray comma.
                if (word == null) {
                    continue;
                }
                String normalised = normalise(word);
                if (!normalised.isEmpty()) {
                    blocked.add(normalised);
                }
            }
        }
    }

    public boolean isEnabled() {
        return !blocked.isEmpty();
    }

    /** @return the word that matched, or null if the message is clean */
    public String firstMatch(String message) {
        if (blocked.isEmpty() || message == null || message.isEmpty()) {
            return null;
        }
        String haystack = normalise(message);
        for (String word : blocked) {
            if (haystack.contains(word)) {
                return word;
            }
        }
        return null;
    }

    /**
     * Folds a message down to comparable form: lower-cased, common letter-for-symbol swaps
     * undone, and everything that is not a letter or digit removed — so "n.i.c.e", "n i c e" and
     * "n1ce" all reduce to the same thing.
     */
    static String normalise(String input) {
        StringBuilder out = new StringBuilder(input.length());
        for (char raw : input.toLowerCase(Locale.ROOT).toCharArray()) {
            char c = switch (raw) {
                case '0' -> 'o';
                case '1', '!', '|' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                default -> raw;
            };
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
