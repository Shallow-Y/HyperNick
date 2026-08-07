package com.hypernick.data;

import java.util.Objects;
import java.util.UUID;

/**
 * 单个玩家的匿名数据.
 * <p>
 * 真实 UUID 始终不变 (服务端数据完全继承), 仅在数据包层面使用 fakeUuid 模拟全新身份.
 * fakeUuid 采用 version 5 格式, 与正版 v4 / 离线 v3 不冲突.
 */
public class NickData {

    /** 皮肤模式: REAL=真实皮肤, RANDOM=随机皮肤(默认Steve/Alex), RESET=默认皮肤(Steve/Alex) */
    public enum SkinMode {
        REAL, RANDOM, RESET
    }

    private final UUID uuid;
    private final String originalName;
    private String nickName;
    private String rankKey;
    private long setAt;
    private UUID fakeUuid;

    /** 皮肤模式 (仅匿名时生效) */
    private SkinMode skinMode = SkinMode.REAL;
    /** 上次使用的昵称 (reset 后保留, 供 /nick reuse 使用) */
    private String lastNick;
    /** 上次使用的 Rank (reset 后保留, 供 /nick reuse 使用) */
    private String lastRank;

    public NickData(UUID uuid, String originalName, String nickName, String rankKey, long setAt, UUID fakeUuid) {
        this.uuid = Objects.requireNonNull(uuid);
        this.originalName = Objects.requireNonNull(originalName);
        this.nickName = nickName;
        this.rankKey = rankKey;
        this.setAt = setAt;
        this.fakeUuid = fakeUuid;
    }

    /** 兼容旧数据的构造器 (无 fakeUuid, 由 NickManager 自动生成) */
    public NickData(UUID uuid, String originalName, String nickName, String rankKey, long setAt) {
        this(uuid, originalName, nickName, rankKey, setAt, null);
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getRankKey() {
        return rankKey;
    }

    public void setRankKey(String rankKey) {
        this.rankKey = rankKey;
    }

    public long getSetAt() {
        return setAt;
    }

    public void setSetAt(long setAt) {
        this.setAt = setAt;
    }

    public UUID getFakeUuid() {
        return fakeUuid;
    }

    public void setFakeUuid(UUID fakeUuid) {
        this.fakeUuid = fakeUuid;
    }

    public SkinMode getSkinMode() {
        return skinMode;
    }

    public void setSkinMode(SkinMode skinMode) {
        this.skinMode = skinMode;
    }

    public String getLastNick() {
        return lastNick;
    }

    public void setLastNick(String lastNick) {
        this.lastNick = lastNick;
    }

    public String getLastRank() {
        return lastRank;
    }

    public void setLastRank(String lastRank) {
        this.lastRank = lastRank;
    }
}
