package com.novel.browse.service.epub;

/**
 * 文本处理小工具：字数统计与长度截断。
 */
public final class TextUtil {

    /**
     * 字数统计：与演示数据保持一致，按非空白字符计数（中日韩文字与英文单词内的字符均计入）。
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

    /** 按字符截断，超长时保留前 max 个字符 */
    public static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 归一化空白：换行最多保留两个，去掉行尾空格 */
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
