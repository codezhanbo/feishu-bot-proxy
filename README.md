# feishu-bot-proxy

飞书自定义机器人 webhook 的**中转服务**。应用不再直连 `open.feishu.cn`，改为调这个服务，由它统一转发。

Java 8 · Spring Boot 2.7.18 · Maven · 第三方依赖只有 `spring-boot-starter-web` 和 `postgresql`（测试另用 H2）。

## 解决什么问题

| 直连飞书 | 走中转 |
|---|---|
| webhook 地址散落在各应用配置里，换群要改代码发版 | 换群只改中转服务一处配置 |
| 每个应用各自管加签密钥 | 密钥集中在中转服务，应用完全不用管 |
| 各应用各自触发飞书 100 次/分钟频控，失败就丢消息 | 统一限流 + 失败自动重试 |
| 出问题查不到发过什么 | 每条消息原文落 Postgres，重启不丢 |
| 开了 IP 白名单要放行每台应用机器 | 只放行中转服务一个出口 IP |

## 应用侧怎么改

**只换 URL，请求体一个字不动。**

```diff
- POST https://open.feishu.cn/open-apis/bot/v2/hook/9c8f7e6d-xxxx-xxxx
+ POST http://your-proxy:8080/webhook/dev-group
```

请求体仍然是飞书原生格式，`text` / `post` 富文本 / `image` / `share_chat` / `interactive` 卡片全都原样透传：

```json
{"msg_type":"text","content":{"text":"构建失败 ⚠️"}}
```

**返回体与直连飞书完全一致**（`{"code":0,"msg":"success"}`），所以应用里原有的 `code == 0` 判断逻辑不用改。

## 配置

`src/main/resources/application.yml` 里的 `feishu.bots`，key 就是 URL 里的 `{botKey}`：

```yaml
feishu:
  default-bot: dev-group        # POST /webhook（不带 botKey）时用哪个群
  access-token: ""              # 非空则要求调用方带 X-Api-Token 头；空=不鉴权
  max-body-bytes: 20480         # 飞书上限 20KB

  retry:
    max-attempts: 3
    initial-backoff-ms: 500
    multiplier: 2.0
    retryable-codes: [9499, 19003, 11232]

  rate-limit:
    enabled: true
    per-minute: 100             # 飞书限制，每个机器人
    per-second: 5               # 飞书限制，每个机器人
    wait-timeout-ms: 1000       # 排队等不到令牌就返回 429

  store:
    enabled: true               # 关掉则不落库，/admin/logs 返回 503
    jdbc-url: ${FEISHU_STORE_JDBC_URL}   # Supabase 的 Session Pooler 连接串
    username: ${FEISHU_STORE_USERNAME}   # 形如 postgres.<project-ref>
    password: ${FEISHU_STORE_PASSWORD}

  bots:
    dev-group:
      webhook: https://open.feishu.cn/open-apis/bot/v2/hook/aaaa-bbbb
      secret: ""                # 群「安全设置-加签」的密钥；配了才自动签名
      enabled: true
    ops-group:
      webhook: ${FEISHU_OPS_WEBHOOK}   # 生产环境建议用环境变量注入
      secret: ${FEISHU_OPS_SECRET}
      keywords: [告警]          # 仅供 /admin/test 构造能过关键词校验的消息
```

**加签是自动的**：给某个 bot 配上 `secret`，中转服务就会在转发时往请求体顶层注入 `timestamp` 和 `sign`；没配 `secret` 的 bot 走**字节级原样透传**，连 JSON 都不会重新序列化。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/webhook/{botKey}` | 转发到指定群 |
| POST | `/webhook` | 发到 `default-bot` |
| GET | `/health` | 健康检查（不需要 token） |
| GET | `/admin/bots` | 已配置的机器人（webhook 脱敏） |
| GET | `/admin/logs?botKey=&success=&limit=50&offset=0` | 历史消息，最新在前（`limit` 上限 200） |
| GET | `/admin/stats` | 成功率、耗时、按群/按错误码分布（内存计数，重启清零） |
| POST | `/admin/test/{botKey}` | 往该群发一条测试消息 |

**一次请求发一个群。**`/webhook/{botKey}` 的返回体与直连飞书完全一致，没有聚合层、没有包装。

### 消息留档

**每一次带请求体的调用都会在 Postgres 里落一行**，无论成败——包括被判为非法 JSON、超长、botKey 不存在而根本没发出去的那些。

```sql
-- 在 Supabase 的 SQL Editor（或任意 Postgres 客户端）里跑：
select id, create_datetime, bot_keys, msg_type, title from message_log order by id desc;
```

`create_datetime` 是落库时间的可读形式（`yyyy-MM-dd HH:mm:ss.SSS`，与 `/admin/logs` 里的 `time` 同值）；`created_at` 是同一时刻的 epoch 毫秒。`create_datetime` 是后加的列，旧行为 NULL，旧行仍可用 `to_timestamp(created_at/1000.0)` 换算（少了毫秒部分）。

`title` 和 `text_preview` 是从飞书报文里提取出来的，`post` 富文本取标题和正文纯文本、`interactive` 取卡片标题，方便直接用 SQL 翻历史，不用一条条读 `body`。`body` 存的是**调用方原始的 JSON 全文**（不是加签后的）。

`/admin/logs` 的一行长这样：

```json
{
  "id": 128, "time": "2026-09-01 09:00:03.117", "createDatetime": "2026-09-01 09:00:03.117",
  "botKeys": "dev-group",
  "msgType": "post", "title": "微凉Pro游戏数据统计 2026-09-01",
  "textPreview": "🎮累计对局:0 | 💰累计BP:0 📌通行经验:0 …",
  "body": "{\"msg_type\":\"post\", …}", "bodyBytes": 412, "clientIp": "10.0.0.7",
  "success": true, "code": 0, "msg": "success",
  "results": [
    {"botKey":"dev-group","success":true,"code":0,"msg":"success","attempts":1,"costMs":87}
  ],
  "stats": {
    "statDate": "2026-09-01", "survivalLevel": 6,
    "expGained": 70, "bpGained": 250, "duration": "00:30:20"
  }
}
```

> `botKeys` / `results` 是复数形态，因为早先支持过一次发多个群，历史行里可能有多个目标；现在写入的行永远只有一个。

顶层的 `code`/`msg` 就是当时回给调用方的那个。落库是同步的，但**失败只降级告警，绝不影响转发**——库连不上、连接被回收，消息照发，只是查不到档。

### 战报数值

游戏战报把数值内嵌在文本里（`💰累计BP:1200`），写入时会顺手解析成 5 个独立的列，方便直接用 SQL 统计：

| 列 | 含义 |
|---|---|
| `stat_date` | 战报自称的日期，取自标题（不是落库时间） |
| `survival_level` | 生存等级，**原值** |
| `exp_gained` | 本次新增的**生存经验**（不是通行经验） |
| `bp_gained` | 本次新增的 BP |
| `duration` | 本次新增的对局时长，`HH:MM:SS` |

```sql
select stat_date, survival_level, bp_gained, exp_gained, duration
  from message_log where stat_date is not null order by id desc;
```

报文给的全是**累计值**，后三列是「当前值 − 同 botKey 上一条战报的值」。几条需要知道的规则：

- **没有可比对的上一条时增量为 NULL**，不是 0——「算不出」和「没涨」是两回事。同理，两边缺任一边的字段也是 NULL。
- **赛季重置导致累计值倒退时，如实存负数**（`duration` 会带负号），不夹到 0，否则重置这件事就被抹掉了。
- 找「上一条」时只往回翻 10 条 post，中间夹着的 text 消息或畸形请求不会打断增量链；但同一个群连续来 10 条以上非战报的 post 会把链断在那一行。
- **发送失败的战报照样算作「上一条」**：游戏侧的累计值已经涨上去了，跳过它会让下一条的增量翻倍。
- 非战报消息这 5 列全为 NULL，`stats` 字段为 `null`。

这 5 列是后加的。已有的库启动时会自动 `ALTER TABLE` 补上，日志里会打 `message store migrated: added column ...`；**旧行不回填**，保持 NULL。

## 错误码

中转服务自己产生的错误用 `4xxxx`/`5xxxx` 段，不与飞书的 code 冲突：

| HTTP | code | 含义 |
|---|---|---|
| 400 | 40001 | 请求体为空或不是合法 JSON 对象 |
| 400 | 40002 | 没有指定 botKey / 未配置 default-bot / botKey 里含逗号 |
| 401 | 40100 | `X-Api-Token` 校验失败（启用鉴权时） |
| 403 | 40301 | 该 bot 被 `enabled: false` 停用 |
| 404 | 40401 | botKey 不存在 |
| 413 | 41301 | 请求体超过 20KB |
| 429 | 42900 | 被本地限流拦下，没有发给飞书 |
| 500 | 50001 / 50002 | bot 没配 webhook / 加签失败 |
| 502 | 50200 | 重试耗尽仍连不上飞书 |
| 503 | 50301 | 消息库不可用，`/admin/logs` 查不了（不影响转发） |
| 200 | 飞书原始 code | 飞书返回的业务错误（如 19024 关键词不匹配）原样透传 |

**飞书侧常见 code**：`9499` 频控、`11232` 频率超限、`19021` 签名或时间戳不对、`19024` 关键词没命中、`19022` IP 白名单、`10001` webhook 不存在。其中 `9499`、`19003`、`11232` 会自动重试；`19021`/`19024` 这类确定性失败不重试（重试只会浪费频控额度）。

> `11232` 是**租户级**限流，整个租户下所有自定义机器人共享额度，本地限流器管不到。它在**整点、半点尤其高发**——大量系统都把定时推送排在 `10:00`、`17:30` 这种时刻。如果你的定时任务撞在整点上，把它挪到 `19:03`、`19:07` 这类零散时间点，比任何重试都管用。

## 运行

```bash
mvn package
java -jar target/feishu-bot-proxy-1.0.0.jar

# 覆盖配置
java -jar target/feishu-bot-proxy-1.0.0.jar \
  --server.port=8080 \
  --feishu.bots.dev-group.webhook=https://open.feishu.cn/open-apis/bot/v2/hook/xxx
```

配好真实 webhook 后，用 `curl -X POST http://localhost:8080/admin/test/dev-group` 确认群里能收到消息。

> 本机 Maven 全局 `settings.xml` 把所有仓库镜像到了公司内网 Nexus（`maven.zishantech.com`）。不在公司网络时构建会卡住，可加 `-s <指向公共仓库的 settings.xml>`，或直接 `mvn -o` 走本地仓库。

### 冒烟验证

```bash
curl -X POST http://localhost:8080/webhook/dev-group \
  -H 'Content-Type: application/json' \
  --data-binary '{"msg_type":"text","content":{"text":"hello"}}'
```

> Windows 的 Git Bash 里，`-d '{"...":"..."}'` 这种行内 JSON 会被参数转换搞坏，全部报 `40001`。用 `--data-binary @body.json` 从文件传。

## 测试

```bash
mvn test    # 88 个用例
```

用 JDK 自带的 `com.sun.net.httpserver` 起本地 mock 顶替 open.feishu.cn，**不需要真实机器人**即可跑通全链路，覆盖：字节级透传（含中文和 emoji）、加签注入（对照 openssl 独立复算的黄金向量）、9499 与 11232 重试、19021 不重试、限流拦截、各类错误响应、消息落库与重开库后仍在、战报数值解析与增量差分、旧库自动补列。

## 已知限制

- **消息库只增不删。** 没有自动清理，磁盘占用要自己盯。粗算：一条 500B 的消息约占 1KB，每天 1000 条一年约 350MB。要瘦身就自己 `delete from message_log where created_at < ...` 再 `vacuum`。
- **统计（`/admin/stats`）仍然在内存里，重启清零。** 只有消息本身是持久的。
- **重试是 at-least-once。** 网络超时重试可能造成飞书其实已收到、中转判为失败又重发的重复消息。告警通知场景一般可接受。
- **中转服务是单点**：它挂了所有群都发不出去。生产建议双实例 + 负载均衡，但注意：**限流是每实例本地计数**，N 个实例的实际上限是 N × `per-minute`，要相应下调；消息库是共享的一张 Postgres 表，`/admin/logs` 看得到全部，但**战报差分不是跨实例原子的**——两个实例并发写同一群时可能读到同一条「上一条」，算重增量。
- 改配置需要重启。

## 安全

- **webhook URL 和 secret 是凭据**，拿到就能往群里发消息。生产用环境变量注入，不要提交进 Git；`.gitignore` 已排除 `application-local.yml` / `application-prod.yml` / `bots.yml`。
- 日志和 `/admin/bots` 里的 webhook 一律脱敏。
- **`message_log` 表里是真实消息原文**，敏感程度等同于群聊记录，且 `/admin/logs` 会把 `body` 整个吐出来。数据库（Supabase）的访问权限和 `/admin/*` 的暴露面要自己把关（见下面的 `access-token`）。
- 若群开了 **IP 白名单**，要放行的是**中转服务的公网出口 IP**，不再是各应用的 IP。
- 中转服务默认不鉴权（内网场景）。暴露到不可信网络时请设置 `feishu.access-token`，调用方带 `X-Api-Token` 头。
