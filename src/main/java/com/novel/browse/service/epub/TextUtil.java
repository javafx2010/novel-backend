package com.novel.browse.service.epub;

/**
 * Small text helpers: word counting and length truncation.
 */
public final class TextUtil {

    /**
     * Word count: consistent with the demo data, counts non-whitespace characters (CJK ideographs and characters inside English words included).
     */
    public static long wordCount(CharSequence text) {
        if (text == null) {
            return 0L;
        }
        long count = 0L;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /** Truncates by characters, keeps the first max characters */
    public static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** Normalizes whitespace: at most two consecutive line breaks, no trailing spaces */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replace('\u00a0', ' ').replace("\r\n", "\n").replace('\r', '\n');
        cleaned = cleaned.replaceAll("[ \t]+\n", "\n").replaceAll("\n{3,}", "\n\n");
        return cleaned.trim();
    }

    private TextUtil() {
    }
}
