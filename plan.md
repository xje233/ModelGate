# ModelGate 实施计划

> AI 模型网关 —— 面向大模型调用的统一接入与流量治理层
> 参考实现：`Vincent-Ye/java-litellm`（架构骨架）、`BerriAI/litellm`（概念与机制）、`zouyee/llmlite`（指标切面设计）

---

## 1. 项目定位与叙事边界

**一句话**：纯 Java 栈的高性能 LLM API 网关，对下屏蔽多家模型供应商差异，对上提供 OpenAI 兼容的统一入口，并完成路由、配额、缓存、熔断、计量、可观测六件事。

**叙事关键词（严格遵守）**：网关、路由、限流、缓存、熔断、压测、JVM、Netty/WebFlux、背压、虚拟线程。

**禁止出现在叙事里**：Agent 编排、RAG 检索、工具调用链（那是另外两个项目的地盘）。

### 岗位覆盖

| 岗位类型 | 本项目贡献的考察点 |
|---|---|
| 业务后端 | 高并发、限流、缓存、压测数据、JVM 调优 |
| AI 平台 | 多模型接入、Token 计量、语义缓存、SSE 流式透传 |
| 基础架构 | WebFlux/Reactor-Netty、路由、熔断降级、可观测性 |

---

## 2. 技术选型

| 层面 | 选型 | 说明 |
|---|---|---|
| 语言/运行时 | Java 21（LTS） | 虚拟线程用于上游调用侧 |
| 构建 | Maven 多模块 | 5 个子模块，单向依赖 |
| Web 框架 | Spring Boot 3.x + **Spring WebFlux** | 底层即 Reactor-Netty，简历可写 Netty；数据面全链路响应式 |
| 上游 HTTP 客户端 | Reactor Netty `WebClient`（响应式）+ Java 21 虚拟线程（阻塞式备用路径） | 混合调度是差异化卖点 |
| JSON | Jackson | |
| 数据库 | MySQL 8 | 控制面：Key / 租户 / 模型配置 / 用量明细 |
| 缓存与限流 | Redis 7 + Lettuce + **Lua 脚本** | 滑动窗口限流、配额计数、熔断状态、语义缓存 |
| 可观测 | Micrometer + Prometheus + Grafana | `/actuator/prometheus` |
| 压测 | JMeter + **自建 Mock 上游** | Mock 上游是拿到干净数字的前提 |
| 嵌入（语义缓存） | 上游 Embedding API（如 `text-embedding-3-small`） | 余弦相似度检索 |

**明确不做的事**：
- 不裸写 Netty（WebFlux 底层已足够，省下的时间投语义缓存与 JVM 调优）
- 不做管理后台 UI（Spring Actuator + Grafana 已覆盖观测诉求）
- 不做 100+ Provider（做 3~4 家 + OpenAI 兼容协议泛化接入即可）

---

## 3. 架构设计（参考 java-litellm 五层）

```
modelgate-proxy      Spring Boot 网关：鉴权 / 三层配额 / 缓存 / 熔断入口 / 指标
       ▲
modelgate-router     路由决策：加权 LB / retry(组内) / fallback(跨组) / cooldown(单部署)
       ▲
modelgate-client     SDK 门面：同步 / 虚拟线程异步 / 流式 + 成本计算
       ▲
modelgate-providers  Provider 适配：OpenAI / DeepSeek / Kimi / Mock，SPI 可插拔
       ▲
modelgate-core       canonical OpenAI 格式类型（唯一数据契约，零依赖）

modelgate-mock       （独立进程）Mock 上游：可调延迟 / 可控 SSE 分帧 / 可注错
```

### 3.1 五层职责（逐层说明）

**modelgate-core** —— 全系统唯一数据契约
- `ChatRequest` / `ChatResponse` / `ChatCompletionChunk`（SSE 帧）/ `Usage`（token 计量）/ `Message`
- 所有 provider 的请求/响应最终归一到该格式；对外的 `/v1/chat/completions` 直接反序列化为它
- **零依赖**，不含任何 Spring / Redis 引用

**modelgate-providers** —— SPI 插拔
- 接口：`Provider#transformRequest(ChatRequest) → ProviderHttpRequest`、`Provider#transformResponse(...) → ChatResponse`
- 实现：`OpenAiProvider`（同时作为 DeepSeek/Kimi/任意 OpenAI 兼容端点的泛化实现，仅 `baseUrl` 不同）、`MockProvider`
- 新增供应商 = 新增一个实现类 + 一条配置，核心模块零改动

**modelgate-client** —— 调用门面
- `chat()`（阻塞，虚拟线程）、`chatAsync()`（`CompletableFuture`）、`chatStream()`（`Flux<ChatCompletionChunk>`）
- 重试策略在此层收口（仅针对可重试错误：5xx / 连接失败 / 超时；**4xx 一律不重试**）
- 成本计算 `CostCalculator`：内置价目表（模型 → $/1K input tokens、$/1K output tokens），`ChatResponse.costUsd()`

**modelgate-router** —— 路由决策（纯逻辑，可单测，不依赖 HTTP）
- 核心概念两层：**Model Group（对外别名）→ Deployment（具体上游实例）**
- 决策顺序（照抄 LiteLLM 语义）：`跨组 fallback → 组内 retry → 选路`
- 三件事严格分离：
  - **Retry**：留在当前 group，换同组另一个 deployment；触发 = 5xx/连接失败/超时
  - **Fallback**：离开当前 group，按配置顺序尝试备用 group
  - **Cooldown**：摘掉单个 deployment（非整组）；触发 = 连续失败 ≥ `allowed-fails`，持续 `cooldown-time`
- 路由策略（P0 实现前两种）：`weighted-random`（加权随机）、`latency-based`（按 P99 历史延迟选路，P1）
- 状态（cooldown 标记、延迟统计）双后端：`in-process` / `Redis`，统一接口

**modelgate-proxy** —— Spring Boot 网关（对外交付形态）
- 端点：`POST /v1/chat/completions`（流式 + 非流式）、`GET /v1/models`、`GET /actuator/prometheus`、`/health`
- 过滤器链（WebFilter，顺序即执行序）：
  `鉴权 → 配额检查(Redis+Lua) → [语义缓存查询] → 路由 → 透传 → [缓存写入] → 异步记账`

### 3.2 关键链路时序

```
Client ──POST /v1/chat/completions──▶ Proxy
  │ 1. Bearer token → 哈希 → 本地 Caffeine → Redis → MySQL（三级，防击穿：in-flight 合并）
  │ 2. Lua: 检查 user / tenant / model 三层配额（RPM 窗口 + TPM 计数），超限 → 429 + Retry-After
  │ 3. (P1) 语义缓存：embedding → 检索 namespace={key}|{model} 下相似度 ≥ 0.95 → 直接回放
  │ 4. Router 选 deployment（加权随机，跳过 cooldown 中的）
  │ 5. WebClient 透传；stream=true 时 bodyToFlux 逐帧转发（不缓冲整包）
  │ 6. 响应头注入：x-modelgate-cost / x-modelgate-model-id / x-modelgate-attempted-fallbacks / x-ratelimit-remaining
  │ 7. 异步记账：Redis list 入队 → 后台线程批量刷 MySQL t_usage_log
Client ◀────── SSE chunk 逐帧 ──────┘
```

---

## 4. 功能清单（P0 / P1 / P2）

### P0 —— 第 1~2 周（做完即可写简历）

| # | 功能 | 实现要点 | 验收标准 |
|---|---|---|---|
| 1 | OpenAI 兼容入口 | `/v1/chat/completions`，支持 `stream` 两种模式 | OpenAI SDK 直连网关可正常工作 |
| 2 | 加权路由 | model group → 多 deployment，`weight` 字段加权随机；权重可热更新（配置表 + 60s 刷新） | 权重 7:3 时流量比 ≈ 7:3（压测验证） |
| 3 | SSE 流式透传 | `Flux<ServerSentEvent>`；**背压**：`onBackpressureBuffer(maxSize)` + `cancel` 传播 | 客户端断连后，上游连接被正确取消（不泄漏） |
| 4 | 三层配额 | Redis + Lua 滑动窗口；维度 `user / tenant / model`；RPM + TPM 两类 | 超限返回 429 + `Retry-After`；Lua 保证原子 |
| 5 | 压测 | JMeter + **自建 Mock 上游**（modelgate-mock） | 产出 QPS / P99 / 错误率三数 |

### P1 —— 第 3 周（最值钱的差异化）

| # | 功能 | 实现要点 | 验收标准 |
|---|---|---|---|
| 6 | 语义缓存 | 请求 Embedding → 余弦相似度 ≥ **0.95** 命中直接返回；namespace 含 `key_id`（防跨用户串答案）；**in-flight 合并**（同 key 共享一个 `Mono.cache()`，防击穿）；流式请求不走缓存 | 产出**缓存命中率** + **节省 token 成本（$）**两个数 |
| 7 | 熔断降级 | `CLOSED → OPEN → HALF_OPEN` 状态机；`allowed-fails=3`、`cooldown-time=60s`；超时率/失败率超阈值自动切备用 group | Mock 上游注错，验证自动切换与恢复 |
| 8 | 可观测 | Micrometer 指标 + Grafana 面板 | label 至少含 `provider` / `model_alias` / `tenant` |

### P2 —— 第 4 周（有余力）

| # | 功能 | 实现要点 |
|---|---|---|
| 9 | Token 计量与成本核算 | 异步批量写 `t_usage_log`；日/租户/模型三维度聚合；计费含 prompt cache 系数（Anthropic 1.25x / OpenAI 命中 0.5x 写入 2x） |
| 10 | 灰度路由 / A-B 分发 | 按 key 前缀哈希或百分比分流到不同 deployment |

---

## 5. 数据库设计（MySQL，控制面）

```sql
-- 租户
CREATE TABLE t_tenant (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_code   VARCHAR(64) NOT NULL UNIQUE,
  rpm_limit     INT,          -- 租户级 RPM
  tpm_limit     BIGINT,       -- 租户级 TPM
  max_budget_usd DECIMAL(12,4), -- 周期预算
  status        TINYINT DEFAULT 1,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- API Key（token 只存哈希！）
CREATE TABLE t_api_key (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  token_hash    CHAR(64) NOT NULL UNIQUE,   -- SHA-256
  key_alias     VARCHAR(128),
  tenant_id     BIGINT NOT NULL,
  user_id       VARCHAR(64),                 -- 用户维度配额锚点
  models        JSON,                        -- 可用 model group 白名单（* 不限）
  rpm_limit     INT, tpm_limit BIGINT,
  max_budget_usd DECIMAL(12,4),
  expires_at    DATETIME,
  status        TINYINT DEFAULT 1,
  KEY idx_tenant (tenant_id)
);

-- 模型路由配置：一个 group 多行 deployment
CREATE TABLE t_model (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  group_name    VARCHAR(64) NOT NULL,   -- 对外别名，如 fast-medium
  provider      VARCHAR(32) NOT NULL,   -- openai / deepseek / kimi / mock
  model_id      VARCHAR(128) NOT NULL, -- 真实上游模型
  base_url      VARCHAR(256),
  weight        INT DEFAULT 10,
  enabled       TINYINT DEFAULT 1,
  KEY idx_group (group_name)
);

-- 用量明细（异步批量写入，参考 LiteLLM SpendLogs）
CREATE TABLE t_usage_log (
  request_id        CHAR(32) PRIMARY KEY,
  key_id            BIGINT, tenant_id BIGINT, user_id VARCHAR(64),
  model_group       VARCHAR(64), model_id VARCHAR(128), provider VARCHAR(32),
  prompt_tokens INT, completion_tokens INT,
  cost_usd          DECIMAL(10,6),
  cache_hit         TINYINT DEFAULT 0,        -- 语义缓存命中标记
  attempted_fallbacks JSON,                    -- 实际走过的降级链
  start_time DATETIME(3),
  completion_start_time DATETIME(3),           -- TTFT 专用
  end_time DATETIME(3),
  status SMALLINT,
  KEY idx_tenant_time (tenant_id, start_time),
  KEY idx_model_time (model_group, start_time)
);
```

要点：
- **配额取最严**：生效限额 = min(key, tenant)，模型白名单取交集 `key.models ∩ tenant 可用`
- **热路径不写 MySQL**：记账走 Redis 队列 → 批量落库（每 5s 或满 200 条），代价是账单最多延迟几秒

## 6. Redis 设计

| Key 模式 | 类型 | 用途 |
|---|---|---|
| `rl:{scope}:{id}:{window}` (scope=key/tenant/model) | Lua 滑动窗口（ZSET） | RPM 限流 |
| `q:{scope}:{id}:tokens` | Lua INCRBY | TPM 计数（带 TTL 对齐窗口） |
| `cb:{deployment}` | String + TTL | cooldown 标记（TTL = cooldown_time） |
| `lat:{deployment}` | Hash | 延迟统计（count / sum / p99 采样） |
| `sc:{key_id}:{model}:{bucket}` | Hash: embedding + response | 语义缓存，按 key 隔离 namespace |
| `spendq` | List | 异步记账队列 |

语义缓存检索：MVP 用**取模分桶 + 桶内暴力余弦**（每桶 ≤ 100 条），避免引入向量库依赖；接口留 pluggable（后续可换 Redis Stack VSS）。

---

## 7. 压测与 JVM 调优计划（第 2 周末启动，贯穿后续）

### 7.1 Mock 上游（modelgate-mock）

独立 Spring Boot 进程，特性：
- 固定/可调延迟（如 P50=100ms，可注入长尾）
- SSE 分帧输出（每 20ms 一 chunk，模拟 token 级流式）
- 可注入错误率 / 429 / 超时（用于验证熔断）
- **Mock 与网关同机部署**，排除网络变量

### 7.2 压测方案

- JMeter：非流式（纯吞吐）与流式（长连接）分开测
- 梯度加压：50 → 100 → 200 → 500 并发，每档 5 分钟
- 记录：**QPS / P99 / 错误率** + 网关自身 **overhead latency**（总耗时 − 上游耗时）
- 对照组：直连 Mock（测出网关本身的损耗百分比）

### 7.3 JVM 调优（面试引子）

压测时开启 `-Xlog:gc:file=gc.log:time,uptime,level,tags`，做两轮对比：

| 参数 | 第一轮（默认） | 第二轮（调优） |
|---|---|---|
| 堆 | 默认 | `-Xms2g -Xmx2g` 对齐避免动态扩缩 |
| GC | G1 | G1 vs **ZGC** 各跑一轮 |
| 堆外 | 默认 | `-XX:MaxDirectMemorySize=1g`（**Reactor Netty ByteBuf 走堆外，流式场景堆外才是大户**） |
| 连接池 | 默认 | WebClient `maxConnections` / `pendingAcquireTimeout` 调优 |
| EventLoop | 默认 | 与 CPU 核数关系验证 |

**产出物：调优前后 QPS / P99 / GC pause 三列对照表**（简历核心数据）。

---

## 8. 可观测性指标清单

| 指标 | type | label |
|---|---|---|
| `modelgate_requests_total` | Counter | provider, model_group, status |
| `modelgate_request_latency` | Timer | provider, model_group（重点看 P99） |
| `modelgate_overhead_latency` | Timer | ——（网关自身开销，压测答辩关键） |
| `modelgate_ttft` | Timer | provider（首 token 延迟） |
| `modelgate_in_flight` | Gauge | ——（上游变慢时 CPU 不升但这个涨） |
| `modelgate_upstream_errors_total` | Counter | provider, reason |
| `modelgate_cooldown_total` | Counter | deployment（熔断触发次数） |
| `modelgate_cache_hit_total` / `_miss_total` | Counter | key_id, model_group |
| `modelgate_tokens_saved_total` | Counter | ——（语义缓存省下的 token，换算成 $） |
| `modelgate_ratelimit_rejected_total` | Counter | scope |

响应头（同步可观测，参考 LiteLLM）：`x-modelgate-cost`、`x-modelgate-model-id`、`x-modelgate-attempted-fallbacks`、`x-ratelimit-remaining`。

---

## 9. 里程碑排期（4 周）

| 周 | 目标 | 交付物 |
|---|---|---|
| W1 | 骨架 + P0#1~3 | 五模块 Maven 工程跑通；core 类型 + provider SPI + 加权路由 + SSE 透传（含背压与断连取消） |
| W2 | P0#4~5 + Mock | Redis Lua 三层配额；modelgate-mock；JMeter 首轮压测 → **QPS/P99/错误率** |
| W3 | P1 全部 + JVM | 语义缓存（命中率 + 省钱数）；熔断降级；Prometheus/Grafana；JVM 两轮调优对照表 |
| W4 | P2 + 收尾 | Token 成本核算；灰度分流（有余力）；README（架构图 + 数据）；简历话术打磨 |

每周结束跑一次全量回归（Mock 上游 + 集成测试）。

---

## 10. 避坑清单（落地时自查）

1. fallback 链 ≤ 3 层，**不兜到更贵的模型**
2. 健康检查**不真实调用上游**（用配置心跳 / 熔断状态聚合）
3. token 只存哈希；master key 走环境变量，不进配置文件
4. 语义缓存 namespace 必须含 `key_id`，防跨用户串答案
5. 流式请求不走缓存（等完整 body 判定会毁掉 TTFT）
6. 4xx 不重试；重试只换 deployment 不换语义
7. 单机 in-process 与集群 Redis 两套限流实现都要留接口（面试必被问多实例一致性）
8. 压测报告必须注明上游为 Mock，注明机器规格——数字才有可比性
9. 管理面/统计查询接口与推理面**线程池隔离**，防止分析查询拖死数据面（LiteLLM 真实事故模式）
10. 连接池预算：MySQL(Hikari) × Redis(Lettuce) × WebClient(maxConnections) 三者分别设上限并记录

---

## 11. 实施状态（滚动更新）

| 周 | 目标 | 状态 | 交付物 |
|---|---|---|---|
| W1 | 骨架 + 入口 / 加权路由 / SSE 透传 | ✅ | 6 模块 Maven 工程；canonical 类型；Provider SPI；加权路由；逐帧 flush 的 SSE 透传；Mock 上游 |
| W2 | 三层配额 + JMeter 压测 | ✅ | `modelgate-quota`（进程内 + Redis+Lua 双后端，三维度一次原子校验）；429 + Retry-After；QPS/P99/错误率三数（[报告](loadtest/RESULTS.md)） |
| W3 | 语义缓存 + 熔断 + 可观测 + JVM | ✅ | `modelgate-cache`（余弦检索、双后端、嵌入缓存、in-flight 合并）；熔断状态机（双信号 + 半开探测 + 指数退避）；15 类指标 + Grafana 面板；JVM 三档对照（[报告](loadtest/RESULTS-W3.md)） |
| W4 | 灰度 + 用量成本落库 + 收尾 | ✅ | 按调用方粘性的 canary 分桶（含哈希雪崩修复）；`modelgate-usage`（有界队列 + 批量落库 + 5 维聚合）；prompt cache 计价；架构图与[面试话术](docs/interview-narrative.md) |

### 有意留下的边界（面试时要主动交底）

1. **熔断状态是进程内的**：多副本各看各的。接口（`RouterState`）已抽好，Redis 实现照 `modelgate-quota` 的模式补即可。
2. **MySQL 控制面未接线**：`t_tenant` / `t_api_key` / `t_model` 的 DDL 已就位（`ops/sql/schema.sql`），
   当前身份与路由仍由 `application.yml` 承担；用量落库已接通（本地 H2、生产 MySQL 同一套 SQL）。
3. **Grafana 面板未渲染验证**：本机没有 Docker，只验证了 `/actuator/prometheus` 的指标导出。
4. **流式记账出现过 1 次未采到 usage**（未复现）：已记入 `verify/W4-verification.md`，
   生产应加"流式结束 usage 为空即告警"的断言。

## 12. 面试叙事要点（自我提醒）

- 三层语义讲清楚：**retry（组内换部署）/ fallback（跨组）/ cooldown（摘单部署）**——大多数自研项目把这三件事揉成一坨
- 语义缓存要能答：阈值 0.95 的依据、跨用户隔离、in-flight 合并防击穿、流式为何不缓存
- 限流要能答：为什么用 Lua（原子性）、滑动窗口 vs 固定窗口（临界突刺）、无 Redis 时多实例会漂
- JVM 要能答：流式网关堆外内存才是大户（Reactor Netty ByteBuf）、G1 vs ZGC 在 P99 长尾上的差异、EventLoop 线程数与 CPU 的关系
- 差异化故事：**虚拟线程（上游阻塞调用）+ Reactor（流式透传）混合调度**——上游变慢时线程阻塞不影响 EventLoop，这是 Python asyncio 单循环架构（LiteLLM）的结构性痛点
