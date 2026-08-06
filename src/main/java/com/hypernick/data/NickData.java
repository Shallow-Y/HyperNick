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

    private final UUID uuid;
    private final String originalName;
    private String nickName;
    private String rankKey;
    private long setAt;
    private UUID fakeUuid;

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

    /** 玩家真实名称 (取消匿名时用于恢复) */
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

    /** 伪装 UUID (仅用于数据包, 服务端不使用) */
    public UUID getFakeUuid() {
        return fakeUuid;
    }

    public void setFakeUuid(UUID fakeUuid) {
        this.fakeUuid = fakeUuid;
    }
}
