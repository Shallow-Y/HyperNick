package com.hypernick.manager;

import com.hypernick.HyperNick;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机昵称生成器.
 * <p>
 * 依据 config.yml 的形容词/名词词表与风格生成随机昵称, 控制在 GameProfile 16 字符上限内.
 */
public class NameGenerator {

    private final HyperNick plugin;
    private List<String> adjectives;
    private List<String> nouns;
    private boolean appendNumber;
    private int numberDigits;
    private int maxLength;

    public NameGenerator(HyperNick plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        this.adjectives = plugin.getConfig().getStringList("name-generator.adjectives");
        this.nouns = plugin.getConfig().getStringList("name-generator.nouns");
        this.appendNumber = plugin.getConfig().getBoolean("name-generator.append-number", true);
        this.numberDigits = plugin.getConfig().getInt("name-generator.number-digits", 2);
        this.maxLength = plugin.getConfig().getInt("nick-settings.max-length", 16);
        if (adjectives.isEmpty()) {
            adjectives = List.of("Swift");
        }
        if (nouns.isEmpty()) {
            nouns = List.of("Player");
        }
    }

    /**
     * 生成一个随机昵称.
     *
     * @return 不超过 maxLength 的随机昵称
     */
    public String generate() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String adjective = adjectives.get(random.nextInt(adjectives.size()));
        String noun = nouns.get(random.nextInt(nouns.size()));
        StringBuilder builder = new StringBuilder().append(adjective).append(noun);
        if (appendNumber) {
            int bound = (int) Math.pow(10, Math.max(1, numberDigits));
            builder.append(random.nextInt(bound));
        }
        if (builder.length() > maxLength) {
            builder.setLength(maxLength);
        }
        return builder.toString();
    }

    public int getMaxLength() {
        return maxLength;
    }
}
