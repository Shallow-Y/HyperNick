package com.hypernick.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 颜色与组件工具类.
 * <p>
 * 支持两种颜色代码格式:
 * <ul>
 *   <li>传统代码: {@code &a} (绿色), {@code &b} (青色), {@code &l} (粗体) ...</li>
 *   <li>HEX 代码: {@code &#FF55FF} (自定义 RGB 颜色), 格式为 {@code &#RRGGBB}</li>
 * </ul>
 * 通过 LegacyComponentSerializer 转换为 Adventure 组件.
 */
public final class ColorUtil {

    /** 支持 HEX 颜色的 & 前缀序列化器 */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    /** 支持 HEX 颜色的 § 前缀序列化器 */
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .hexColors()
            .build();

    private ColorUtil() {
    }

    /**
     * 将 & 颜色代码 (含 HEX) 转换为 §.
     * <p>
     * 传统代码: {@code &a} → {@code §a}<br>
     * HEX 代码: {@code &#FF55FF} → {@code §x§F§F§5§5§F§F} (Minecraft 原生 HEX 格式)
     *
     * @param s 含 & 颜色代码的字符串
     * @return 转换为 § 代码的字符串
     */
    public static String color(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            // 检测 &#RRGGBB HEX 格式
            if (i + 1 < s.length() && s.charAt(i) == '&' && s.charAt(i + 1) == '#') {
                if (i + 8 <= s.length() && isValidHex(s.substring(i + 2, i + 8))) {
                    String hex = s.substring(i + 2, i + 8);
                    sb.append("\u00A7x");
                    for (int j = 0; j < hex.length(); j++) {
                        sb.append('\u00A7').append(hex.charAt(j));
                    }
                    i += 8;
                    continue;
                }
            }
            // 普通 & 代码: 替换为 §
            if (s.charAt(i) == '&') {
                sb.append('\u00A7');
            } else {
                sb.append(s.charAt(i));
            }
            i++;
        }
        return sb.toString();
    }

    /** 检查字符串是否为有效的 6 位 HEX */
    private static boolean isValidHex(String s) {
        if (s == null || s.length() != 6) {
            return false;
        }
        for (char c : s.toCharArray()) {
            if (!Character.isDigit(c) && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return true;
    }

    /** 将带 & 颜色代码 (含 HEX) 的字符串解析为 Adventure 组件 */
    public static Component toComponent(String s) {
        if (s == null || s.isEmpty()) {
            return Component.empty();
        }
        return LEGACY.deserialize(s);
    }

    /** 将带 § 颜色代码 (含 HEX) 的字符串解析为 Adventure 组件 */
    public static Component sectionToComponent(String s) {
        if (s == null || s.isEmpty()) {
            return Component.empty();
        }
        return SECTION.deserialize(s);
    }

    /** 移除所有颜色代码 (含 HEX) */
    public static String stripColor(String s) {
        if (s == null) {
            return "";
        }
        // 先移除 §x§R§R§G§G§B§B HEX 模式
        s = s.replaceAll("\u00A7x(\u00A7[0-9A-Fa-f]){6}", "");
        // 再移除单个 § 代码
        return s.replaceAll("(?i)\u00A7[0-9A-FK-ORX]", "");
    }

    /** 将占位符 {key} 替换为 value */
    public static String replace(String message, String key, String value) {
        if (message == null) {
            return "";
        }
        return message.replace("{" + key + "}", value == null ? "" : value);
    }

    // ==================== 颜色提取与解析 ====================

    /**
     * 从 § 编码的字符串中提取最后一个颜色.
     * <p>
     * 扫描字符串中的所有颜色代码, 返回最后一个非重置颜色.
     * 用于让聊天名称继承前缀的颜色.
     *
     * @param s 含 § 颜色代码的字符串
     * @return 最后一个 TextColor, 若无颜色或被 §r 重置则返回 null
     */
    public static TextColor getLastColor(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        TextColor color = null;
        char[] chars = s.toCharArray();
        int i = 0;
        while (i < chars.length - 1) {
            if (chars[i] == '\u00A7') {
                char code = Character.toLowerCase(chars[i + 1]);
                if (code == 'x' && i + 13 < chars.length) {
                    // HEX 格式: §x§R§R§G§G§B§B (共14个字符, 索引 i 到 i+13)
                    StringBuilder hex = new StringBuilder("#");
                    boolean valid = true;
                    for (int j = i + 2; j < i + 13 && j < chars.length; j += 2) {
                        if (chars[j] == '\u00A7' && j + 1 < chars.length) {
                            hex.append(chars[j + 1]);
                        } else {
                            valid = false;
                            break;
                        }
                    }
                    if (valid && hex.length() == 7) {
                        try {
                            color = TextColor.fromHexString(hex.toString());
                        } catch (Throwable ignored) {
                        }
                    }
                    i += 13;
                    continue;
                } else if (code == 'r') {
                    color = null;
                } else {
                    NamedTextColor named = getLegacyColor(code);
                    if (named != null) {
                        color = named;
                    }
                }
                i += 2;
            } else {
                i++;
            }
        }
        return color;
    }

    /**
     * 将传统颜色代码字符映射为 NamedTextColor.
     *
     * @param code 颜色代码字符 (0-9, a-f)
     * @return 对应的 NamedTextColor, 无效则返回 null
     */
    private static NamedTextColor getLegacyColor(char code) {
        return switch (code) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            case 'f' -> NamedTextColor.WHITE;
            default -> null;
        };
    }

    /**
     * 从 § 编码的字符串中提取最后一个颜色的 § 编码字符串.
     * <p>
     * 与 {@link #getLastColor} 类似, 但返回原始 § 编码 (如 "§x§5§5§F§F§F§F" 或 "§b"),
     * 而非 TextColor 对象. 用于将颜色代码追加到计分板前缀末尾,
     * 使玩家名称条目能继承前缀的 HEX 颜色.
     *
     * @param s 含 § 颜色代码的字符串
     * @return 最后一个颜色的 § 编码字符串; 无颜色或被 §r 重置则返回空字符串
     */
    public static String getLastColorSection(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String lastColor = "";
        char[] chars = s.toCharArray();
        int i = 0;
        while (i < chars.length - 1) {
            if (chars[i] == '\u00A7') {
                char code = Character.toLowerCase(chars[i + 1]);
                if (code == 'x' && i + 13 < chars.length) {
                    // HEX 格式: §x§R§R§G§G§B§B (共14个字符, 索引 i 到 i+13)
                    StringBuilder hex = new StringBuilder("#");
                    boolean valid = true;
                    for (int j = i + 2; j < i + 14 && j < chars.length; j += 2) {
                        if (chars[j] == '\u00A7' && j + 1 < chars.length) {
                            hex.append(chars[j + 1]);
                        } else {
                            valid = false;
                            break;
                        }
                    }
                    if (valid && hex.length() == 7) {
                        lastColor = new String(chars, i, 14);
                    }
                    i += 14;
                    continue;
                } else if (code == 'r') {
                    lastColor = "";
                } else {
                    NamedTextColor named = getLegacyColor(code);
                    if (named != null) {
                        lastColor = new String(chars, i, 2);
                    }
                }
                i += 2;
            } else {
                i++;
            }
        }
        return lastColor;
    }

    /**
     * 将颜色字符串解析为 TextColor.
     * <p>
     * 支持两种格式:
     * <ul>
     *   <li>命名颜色: "AQUA", "RED", "GREEN" ...</li>
     *   <li>HEX 颜色: "#FF55FF", "#55AA00" ...</li>
     * </ul>
     *
     * @param s 颜色字符串
     * @return 对应的 TextColor, 无效则返回 null
     */
    public static TextColor parseTextColor(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        s = s.trim();
        // HEX 格式: #RRGGBB
        if (s.startsWith("#")) {
            try {
                return TextColor.fromHexString(s);
            } catch (Throwable ignored) {
                return null;
            }
        }
        // 命名颜色
        return NamedTextColor.NAMES.value(s.toLowerCase());
    }
}
