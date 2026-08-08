# HyperNick

<div align="center">
  <img src="assets/icon.png" alt="HyperNick" width="200">
</div>

模仿 Hypixel Nickname 系统的 Minecraft 匿名插件。通过 ProtocolLib 数据包拦截实现完整的 UUID + 名称伪装，服务端数据（背包、权限、经济）完全继承。

## 特性

- **完整身份伪装**：使用 Fake UUID（version 5 格式）替换 GameProfile 的 UUID 与名称，客户端看到的是全新身份
- **七层拦截**：PLAYER_INFO、PLAYER_INFO_REMOVE、SPAWN_ENTITY、TAB_COMPLETE、CHAT、SYSTEM_CHAT、事件层
- **Rank 前缀系统**：通过 LuckPerms 瞬态节点设置伪装 Rank 前缀，支持传统颜色代码和 HEX 颜色
- **权限组自动刷新**：检测 LuckPerms 权限组变更，自动刷新前缀显示（无需重新登录）
- **颜色继承**：昵称自动继承 Rank 前缀中最后一个颜色代码
- **PlaceholderAPI 集成**：提供 13 个变量供其他插件使用
- **名称冲突防护**：防止设置已存在的玩家名，自动清除冲突昵称
- **每日限额**：可配置每日昵称修改次数

## 环境要求

| 依赖 | 类型 | 说明 |
|------|------|------|
| PaperMC 26.2 | 必需 | 服务端 |
| JDK 25 | 必需 | 编译环境 |
| ProtocolLib 5.4.0+ | 软依赖 | 数据包拦截（缺少则仅聊天/Tab显示名生效） |
| LuckPerms 5.5+ | 软依赖 | Rank 前缀（缺少则无前缀） |
| PlaceholderAPI 2.12.3+ | 软依赖 | 变量扩展（缺少则无变量支持） |

## 编译

```bash
# Windows
build.bat

# 或手动
gradlew.bat clean build
```

编译产物：`build/libs/HyperNick-bukkit-1.2.3.jar`

## 部署

1. 将 `HyperNick-bukkit-1.2.3.jar` 放入服务器的 `plugins/` 目录
2. 确保 ProtocolLib、LuckPerms、PlaceholderAPI 已安装
3. 启动服务器，生成默认配置文件
4. 根据需要修改 `plugins/HyperNick/config.yml`
5. 重启或执行 `/nick reload`

## 指令

| 指令 | 权限 | 说明 |
|------|------|------|
| `/nick <名称>` | `hypernick.use` | 设置自定义昵称 |
| `/nick random` | `hypernick.random` | 随机昵称 |
| `/nick rank <等级>` | `hypernick.rank` | 设置伪装 Rank 前缀 |
| `/nick reset` | `hypernick.use` | 取消匿名，恢复真实身份 |
| `/nick info` | `hypernick.use` | 查看详细信息（含 Fake UUID） |
| `/nick reload` | `hypernick.admin` | 重载配置 |

> 别名：`/nickname`、`/disguise`

## 权限

| 权限节点 | 默认 | 说明 |
|----------|------|------|
| `hypernick.use` | OP | 使用 /nick 指令 |
| `hypernick.admin` | OP | 重载配置 |

## PlaceholderAPI 变量

| 变量 | 说明 |
|------|------|
| `%hypernick_nickname%` | 当前昵称 |
| `%hypernick_prefix%` | 前缀 (匿名时为 Rank 前缀, 未匿名时为 LuckPerms 组前缀) |
| `%hypernick_rank%` | Rank 键名 |
| `%hypernick_fakeuuid%` | Fake UUID |
| `%hypernick_realuuid%` | 真实 UUID |
| `%hypernick_isnicked%` | 是否已匿名 |

## 配置

编辑 `plugins/HyperNick/config.yml`：

```yaml
# 数据包级别名称替换
packet-disguise: true

# 计分板名牌前缀
scoreboard-nametag: true

# 接管聊天格式
override-chat: true

# 指令中解析昵称
resolve-nicknames-in-commands: true

# 未匿名玩家根据 LuckPerms 组别显示前缀
enable-group-prefix: true

# LuckPerms 组别 → Rank 映射
group-mapping:
  default: default
  vip: vip
  vip_plus: vip_plus
  mvp: mvp
  mvp_plus: mvp_plus

# 聊天格式
chat-format: "{prefix}{name}&r&7: &r{message}"

# Rank 配置
ranks:
  default:
    prefix: "&7"
    color: "GRAY"
    priority: 10
  vip:
    prefix: "&a[VIP] "
    color: "GREEN"
    priority: 100
  vip_plus:
    prefix: "&a[VIP&6+&a] "
    color: "GREEN"
    priority: 200
  mvp:
    prefix: "&b[MVP] "
    color: "AQUA"
    priority: 300
  mvp_plus:
    prefix: "&b[MVP&c+&b] "
    color: "AQUA"
    priority: 400
```

## 技术细节

### Fake UUID 生成

使用 version 5（SHA-1 name-based）格式，输入为 `HyperNick|真实UUID|昵称`。与 v4（正版在线）和 v3（离线盗版）不冲突，同一玩家 + 同一昵称始终生成相同的 Fake UUID。

### 权限组变更检测

订阅 LuckPerms `UserDataRecalculateEvent`，跟踪玩家主组变更。仅在主组实际变更时触发刷新，跳过玩家加入时的首次事件，避免主线程卡顿。

### 防双前缀机制

系统消息（死亡、进度等）可能使用已含前缀的 `playerListName`。在替换前先扁平化消息为纯文本检测是否已含前缀，避免重复添加。

## 许可

MIT License