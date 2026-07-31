# McQqBridge

MC 服务器 <-> QQ 群双向聊天互通插件（Paper），基于 QQ 官方机器人 API，纯 Java 实现，无第三方框架依赖。

## 功能

- QQ 群普通消息 -> 游戏内聊天（需在 QQ 开放平台开启"接收所有消息"）
- QQ 群 @机器人 -> 游戏内聊天
- 游戏内聊天 -> QQ 群（需群成员开启"允许主动发送"）
- 自动绑定第一个 @机器人 的群（可在 config 中修改）
- 图片/表情消息显示为 `[图片/表情消息]`

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

## 配置

```yaml
qq:
  app-id: "你的AppID"
  app-secret: "你的AppSecret"
  group-openid: ""   # 留空自动绑定，或手动填写群ID
bridge:
  mc-to-qq: true     # MC -> QQ 开关
  qq-to-mc: true     # QQ -> MC 开关
  mc-format: "[MC] <{player}> {message}"
  qq-format: "[QQ] <{nickname}> {message}"
```

## QQ 开放平台注意事项

- 开启"接收所有消息"后，群内**每一条**消息（不限于 @）都会以 `GROUP_MESSAGE_CREATE` 事件推送
- 主动消息（非回复）需要群成员在客户端开启"允许主动发送"，否则报错 40034105
- 被动回复（带 `msg_id`）5 分钟有效，每条消息最多回复 5 次
- 事件昵称字段在 `author.username`（不是 `member_nick`）

## 技术要点

- 使用 Java 11+ 内置 `HttpClient` / `WebSocket`，无额外依赖（Gson 由 Paper 提供）
- WebSocket 鉴权：identify 携带 `1 << 25`（GROUP_AND_C2C_EVENT）intent
- 断线重连带指数退避（5s 起，最大 60s）
- Token 自动刷新（提前 60 秒过期）
