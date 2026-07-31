# McQqBridge

MC 服务器 <-> QQ 群双向聊天互通插件（Paper），基于 QQ 官方机器人 API，纯 Java 实现，无第三方框架依赖。

> 状态说明：聊天互通、系统消息转发、每日日报 + 探索地图均已在服务器验证通过。

## 功能

**聊天互通：**
- QQ 群普通消息 -> 游戏内聊天（需在 QQ 开放平台开启"接收所有消息"）
- QQ 群 @机器人 -> 游戏内聊天
- 游戏内聊天 -> QQ 群（需群成员开启"允许主动发送"）
- 图片/表情消息显示为 `[图片/表情消息]`（MC 无法渲染 QQ 表情/图片）

**系统消息转发（FULL 模式）：**
- 玩家进服/退服
- 玩家死亡（含死因）
- 玩家获得成就（仅游戏内公告的真成就，小进度不转发）

**每日日报 + 探索地图（已验证）：**
- 数据采集：玩家跑图时后台按视距范围增量渲染地形（视距内区块 Paper 已加载，`getChunkAtAsync` 几乎零成本），每个区块的方块基色 + 高度 + 水深落盘到 `plugins/McQqBridge/map/tiles/<世界>/cXzZ.bin`（每块 3KB）；内存只保留已渲染坐标集合做标记位；启动时扫盘恢复，重启不丢、不重复渲染已渲染区块
- 每日轨迹/聊天/事件/统计落盘 `plugins/McQqBridge/data/YYYY-MM-DD.json`，保留 30 天自动清理，可供后续 AI 分析
- 日报推送：每晚 23:00（config 可改）自动，或 `/mcqq report now` 手动触发，推送到绑定群 = 一段文字摘要 + 一张探索地图 PNG
- 文字摘要：每玩家在线时长 / 聊天条数 / 死亡数 / 击杀数 / 行走公里数 / 最长停留时长，外加当日成就列表、死亡记录列表
- 探索地图：1 像素 = 1 方块；方块级地图色（`BlockData.getMapColor()`，与 MC 地图物品同源）；明暗用 mojang 原版算法——陆地 = 与北邻居高度差 x0.8 + 棋盘抖动，阈值 +-0.6 分 3 档，乘数 180/220/255；水 = 按水深 x0.1 + 棋盘抖动分浅/中/深蓝 3 档；叠加玩家轨迹（每人一色，地下 Y<40 画虚线）+ 停留圈 + 死亡红叉 + 成就金点 + 玩家名标签 + 标题 + 图例；无活动区块填深色
- 轨迹采样：每 5 秒记一个点，坐标未变化的点不记录（挂机不产生冗余点，停留时长由相邻点时间间隔表达）

## 指令（控制台 + OP，权限 mcqq.admin）

```
/mcqq mode <chat|full>   # 切换模式
/mcqq report now         # 立即生成并推送今日日报
/mcqq report toggle      # 开关每日日报
/mcqq status             # 查看当前状态（含日报状态）
```

| 模式 | 行为 |
|------|------|
| `chat` | 只转发聊天消息（默认） |
| `full` | 聊天 + 进服/退服/死亡/成就系统消息 |

切换立即生效并持久化到 config.yml。写操作（`mode`、`report`）需要 `mcqq.admin` 权限（控制台与 OP 默认拥有）；`status` 不限权限。

## 构建

需要 JDK 21+ 和 Maven 3.11+。

```bash
mvn package
# 产物: target/mc-qq-bridge-1.0.0.jar
```

## 部署

1. 复制 `src/main/resources/config.yml` 到 `plugins/McQqBridge/config.yml`（或先运行一次插件自动生成）
2. 填入你的 QQ 机器人 AppID 和 AppSecret
3. 将 jar 放入服务器的 `plugins/` 目录，重启服务器

日报地图为插件自渲染（视距增量落盘，无需外部底图）。服务器侧仅需：
- 安装中文字体（如 `fontconfig` + `wqy-microhei-fonts`），否则地图上的中文标签渲染为方块

## 配置

```yaml
qq:
  app-id: "你的AppID"
  app-secret: "你的AppSecret"
  group-openid: ""   # 留空自动绑定，或手动填写群ID
bridge:
  mode: chat         # chat = 只发聊天 | full = 聊天 + 系统消息
  mc-to-qq: true     # MC -> QQ 开关
  qq-to-mc: true     # QQ -> MC 开关
  mc-format: "[MC] <{player}> {message}"
  qq-format: "[QQ] <{nickname}> {message}"
report:
  enabled: true              # 每日日报开关
  time: "23:00"              # 每日推送时间
  retention-days: 30         # 每日 JSON 保留天数
  map:
    max-width: 1024          # 地图最大宽度（像素）
    padding: 64              # 轨迹外边距（格）
  trail:
    time-threshold-sec: 5    # 轨迹采样间隔（秒）
    stay-threshold-sec: 30   # 视为停留的最小间隔（秒）
  terrain:
    enabled: true            # 是否启用视距增量地形渲染（探索地图底图）
```

`report` 段所有项均有默认值，已部署服务器即使 config 缺该段也能用默认值运行。

## QQ 开放平台注意事项

- 开启"接收所有消息"后，群内**每一条**消息（不限于 @）都会以 `GROUP_MESSAGE_CREATE` 事件推送
- 主动消息（非回复）需要群成员在客户端开启"允许主动发送"，否则报错 40034105
- 被动回复（带 `msg_id`）5 分钟有效，每条消息最多回复 5 次
- 事件昵称字段在 `author.username`（不是 `member_nick`）
- 群图片消息：先上传（`file_data` base64 + `file_type=1`）拿 `file_info`，再以 `msg_type=7` 富媒体发送，无需公网图床

## 已知限制

- 成就标题转发到 QQ 时为英文（服务端无语言文件），中文翻译表待实现
- QQ 图片/表情无法在 MC 客户端渲染
- 轨迹不记 world 字段，底图固定主世界（下界轨迹无地形背景）
- 日报地图的中文标签依赖服务器中文字体

## 技术要点

- 使用 Java 11+ 内置 `HttpClient` / `WebSocket`，无额外依赖（Gson 由 Paper 提供）；地图渲染用 JDK 自带 AWT/ImageIO（headless）
- 探索地图：方块色 `BlockData.getMapColor()`（与 MC 地图物品同源）+ mojang 原版明暗与水深算法 + 视距增量渲染落盘（`map/tiles/<世界>/cXzZ.bin`，每块 3KB）+ 1 像素 = 1 方块
- WebSocket 鉴权：identify 携带 `1 << 25`（GROUP_AND_C2C_EVENT）intent
- 断线重连带指数退避（5s 起，最大 60s）
- Token 自动刷新（提前 60 秒过期）
- 代码按职责分包：`qq/`（QQ 通信）、`bridge/`（聊天桥接）、`stats/`（数据采集+落盘）、`report/`（渲染+定时+文字）、`command/`（指令）、`config/`（配置）；记录与互通在代码上分离，但同属一个 jar、共享一个 QQ 连接（不拆双插件，因 QQ 一个机器人只允许一个 WebSocket 会话）
- 采集与桥接关注点分离：数据采集不受桥接模式（chat/full）影响，始终记录
