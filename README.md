# MC Telegram Auth（mctgauth）

离线（offline-mode）Minecraft 服务器的 Telegram 登录认证服务端模组。

## 简介

离线模式服务器不校验正版身份，任何人只要知道用户名即可登录，存在冒用风险。本模组为离线服务器引入一层 Telegram 认证：

- 玩家加入服务器后立即被**冻结**（无法移动、破坏、交互、聊天、使用背包），并被设为无敌以免冻结期间受伤。
- 玩家通过 `/account register` 绑定自己的 Telegram 账号，再通过 `/account login` 发起登录请求，在 Telegram 中点击“批准”后解冻。
- 本模组**主动发起**所有网络连接（轮询配套的 Bot 服务），Minecraft 服务器本身不接受任何入站的认证连接。

系统由两部分组成：

- **本仓库**：Minecraft 侧的 Fabric 服务端模组，负责冻结玩家、提供游戏内命令、轮询登录状态。
- **Bot 服务**：独立仓库中的配套 Telegram 机器人服务，负责与 Telegram 交互、维护绑定关系与登录请求。二者通过 HTTP API 通信。

> Bot 服务是**独立的 git 仓库**，与本仓库分别部署。本仓库不包含、也不引用其源码。

## 功能特性

- 加入即冻结，认证前禁止移动 / 载具 / 破坏 / 攻击 / 使用方块与物品 / 背包操作 / 聊天。
- 冻结期间仅放行 `/account` 命令。
- 位置重同步：冻结玩家若被外力推动，会被服务端权威地拉回冻结点。
- 认证超时自动踢出（可配置）。
- 同 IP 免登录会话：认证成功后一段时间内，同一 IP 再次进入免登录（内存态，重启失效）。
- 全部玩家可见文案可在配置文件中自定义，默认简体中文，支持 `§` 颜色码。
- 零外部运行时依赖，仅使用 Minecraft 自带的 Gson 与 JDK 内置的 `java.net.http`。

## 安装部署

**环境要求**

- Minecraft 服务端 26.1.2 及以上
- Java 25 及以上
- Fabric Loader 0.19.3 及以上
- Fabric API 0.155.0+26.1.2 及以上
- 服务器需运行在 offline-mode（`online-mode=false`）

**步骤**

1. 部署 Fabric 服务端，安装对应版本的 Fabric API。
2. 将构建产物 `build/libs/mctgauth-0.1.0.jar` 放入服务器的 `mods/` 目录。
3. 首次启动服务器，模组会在 `config/mctgauth.json` 写入默认配置后可能因 `apiToken` 仍为默认值而打印警告。
4. 停止服务器，编辑 `config/mctgauth.json`，将 `apiBaseUrl`、`apiToken` 改为与 Bot 服务一致的值。
5. 启动 Bot 服务，再启动 Minecraft 服务器。

## 配置参考

配置文件位于 `config/mctgauth.json`，首次运行自动生成。

| 配置键 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `apiBaseUrl` | string | `http://127.0.0.1:8632` | Bot 服务的基础地址 |
| `apiToken` | string | `change-me` | 与 Bot 服务约定的 Bearer 令牌，务必修改 |
| `kickTimeoutSeconds` | int | `120` | 未完成认证的玩家在多少秒后被踢出 |
| `ipSessionMinutes` | int | `30` | 同 IP 免登录会话时长（分钟），`0` 表示禁用 |
| `pollIntervalTicks` | int | `40` | 轮询登录请求状态的间隔（tick，20 tick ≈ 1 秒） |
| `messages` | object | 见下表 | 全部玩家可见文案 |

**messages 文案键**

| 文案键 | 触发场景 | 占位符 |
| --- | --- | --- |
| `needRegister` | 玩家未绑定，提示注册 | — |
| `needLogin` | 玩家已绑定未登录，提示登录 | — |
| `registerToken` | 展示绑定令牌与机器人用户名 | `%token%`、`%bot%` |
| `registerLinkText` | 可点击深链的显示文字 | — |
| `loginSent` | 登录请求已发送 | — |
| `loginApproved` | 登录被批准 | — |
| `loginDenied` | 登录被拒绝 | — |
| `loginExpired` | 登录请求过期 | — |
| `loginCancelled` | 登录请求被取消 | — |
| `frozenHint` | 冻结时尝试受限操作的提示（每 2 秒节流一次） | — |
| `kickTimeout` | 认证超时踢出的原因 | — |
| `serviceUnavailable` | Bot 服务不可用 | — |
| `alreadyBound` | 已绑定，引导去登录 | — |
| `notBound` | 未绑定，引导去注册 | — |
| `sessionRestored` | 命中 IP 会话自动登录 | — |
| `alreadyPending` | 已有待处理的登录请求 | — |
| `rateLimited` | 操作过于频繁 | — |
| `alreadyLoggedIn` | 已登录后重复执行命令 | — |
| `loggedOut` | 主动登出，已重新冻结 | — |
| `notLoggedIn` | 尚未登录时执行登出 | — |
| `playersOnly` | 非玩家执行命令 | — |

## 游戏内命令

| 命令 | 说明 |
| --- | --- |
| `/account register` | 获取 Telegram 绑定令牌与深链；已绑定时提示改用登录 |
| `/account login` | 发起登录请求，随后在 Telegram 中点击“批准” |
| `/account logout` | 主动登出，重新冻结并回到待登录状态；仅本次会话失效，不解除绑定 |

以上命令在冻结期间均可使用（模组单独放行 `account` 命令）。

## 工作流程

```
玩家加入
  └─ 冻结（记录位置、设为无敌、加入冻结集、设置踢出截止）
       ├─ 命中同 IP 有效会话 ── 是 ──> 自动登录（sessionRestored）解冻
       └─ 否 ──> GET 绑定关系
                    ├─ 请求失败 ──> 提示 serviceUnavailable，5 秒后重试，保持冻结
                    ├─ 已绑定  ──> 提示 needLogin
                    └─ 未绑定  ──> 提示 needRegister
玩家 /account register ──> POST 注册令牌 ──> 展示令牌 + 可点击深链
玩家 /account login    ──> POST 登录请求 ──> 记录 request_id，按 pollIntervalTicks 轮询
                                              ├─ approved  ──> 解冻 + 记录 IP 会话
                                              ├─ denied    ──> 提示 loginDenied
                                              ├─ expired   ──> 提示 loginExpired
                                              └─ cancelled ──> 提示 loginCancelled
玩家 /account logout   ──> 重新冻结 + 回到待登录 + 清除 IP 会话（不解除绑定）
截止 tick 到达且未认证 ──> 以 kickTimeout 原因踢出
玩家离线 ──> DELETE 待处理登录请求（fire-and-forget）+ 清理状态
```

## HTTP API 契约副本

> **权威版本在 Bot 仓库 README**，此处为同步副本。修改契约必须两边同步。

- 所有请求头：`Authorization: Bearer <apiToken>`；请求 / 响应体为 JSON；时间戳为 epoch 秒。
- 错误响应体：`{"error":"<code>","message":"<中文>"}`。
- 错误码：`unauthorized`(401)、`already_bound`(409)、`rate_limited`(429)、`not_bound`(404)、`not_found`(404)、`tg_send_failed`(502)。

| 方法 | 路径 | 请求体 | 响应 |
| --- | --- | --- | --- |
| GET | `/api/v1/health` | — | `{"ok":true}` |
| GET | `/api/v1/binding/{mc_uuid}` | — | `{"bound":true,"tg_user_id":123,"mc_name":"Steve"}` 或 `{"bound":false}` |
| POST | `/api/v1/register-token` | `{"mc_uuid","mc_name"}` | 200 `{"token","expires_at","bot_username"}`（幂等重发存活令牌） |
| POST | `/api/v1/login-request` | `{"mc_uuid","mc_name","ip"}` | 201（新建）或 200（已有待处理）`{"request_id","expires_at"}` |
| GET | `/api/v1/login-request/{id}` | — | `{"status":"pending"\|"approved"\|"denied"\|"expired"\|"cancelled"}` |
| DELETE | `/api/v1/login-request/{id}` | — | 200 `{"status":...}` |

- `mc_uuid` 为 `player.getUUID().toString()`（小写带连字符）。
- `mc_name` 为玩家档案名。
- `ip` 为玩家远端 IP 字符串（不含端口）。

## 已知限制

- **离线 UUID 由用户名派生**：改名等同于新身份，会丢失原有绑定，需管理员在 Bot 侧手动解绑后用新名重新绑定。
- **IP 会话为内存态**：服务器重启后全部失效，玩家需重新登录。
- IP 会话按 UUID + IP 匹配；同一 IP 下不同玩家各自独立，不共享会话。

## 开发

本机使用工作区可移植 JDK 与独立的 Gradle 用户目录，构建前需设置环境变量：

```bash
export JAVA_HOME="/Users/palentum/2/mctelegrambotauth/.toolchains/jdk-25.0.3+9/Contents/Home"
export GRADLE_USER_HOME="/Users/palentum/2/mctelegrambotauth/.toolchains/gradle-home"

./gradlew build       # 构建，产物在 build/libs/
./gradlew runServer   # 启动开发服务器（run/ 目录）
```

- 目标平台：Minecraft 26.1.2 / Java 25 / Fabric Loader 0.19.3 / Fabric API 0.155.0+26.1.2。
- 26.1 起 Minecraft 不混淆，直接使用 Mojang 官方命名；Loom 不做 remap。
- 全部注释、文档、日志、玩家可见文案均为简体中文；标识符为英文。
