# ModelGate

面向大模型调用的统一接入与流量治理网关：多模型路由 / 多维配额 / 语义缓存 / 熔断降级 / 成本核算。
实施计划见 [plan.md](plan.md)，压测报告见 [loadtest/RESULTS.md](loadtest/RESULTS.md)。

## 模块

```
modelgate-core        canonical OpenAI 格式类型（零依赖，全系统唯一数据契约）
modelgate-providers   Provider SPI + OpenAI 兼容泛化实现
modelgate-router      加权路由 + retry(组内) / fallback(跨组) / cooldown(单部署)
modelgate-quota       三层配额（key / tenant / model）：进程内 与 Redis+Lua 双后端
modelgate-client      WebClient 传输 + SSE 解析 + 成本计算
modelgate-mock        Mock 上游（可调延迟 / 逐帧 pacing / 注错），压测与故障演练专用
modelgate-proxy       Spring Boot WebFlux 网关（对外交付形态）
```

## 构建与运行

```bash
# 构建（本机需用项目自带的 settings：全局 settings.xml 的 localRepository 指向 Windows
# 路径、且镜像是 http 会被 Maven 3.9 拦截，详见 maven-settings.xml 内注释）
mvn -s maven-settings.xml clean package

# 启动两个 Mock 上游 + 网关
java -jar modelgate-mock/target/modelgate-mock-*.jar --server.port=9001 --mock.name=A
java -jar modelgate-mock/target/modelgate-mock-*.jar --server.port=9002 --mock.name=B
java -jar modelgate-proxy/target/modelgate-proxy-*.jar

# 非流式
curl -s localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-local-test-1" -H "Content-Type: application/json" \
  -d '{"model":"fast-medium","messages":[{"role":"user","content":"hi"}]}'

# 流式（SSE 逐帧透传）
curl -sN localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-local-test-1" -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"model":"fast-medium","stream":true,"messages":[{"role":"user","content":"hi"}]}'

# 故障演练：broken 组强制 500，验证跨组 fallback 到 fast-medium
curl -s localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-local-test-1" -H "Content-Type: application/json" \
  -d '{"model":"broken","messages":[{"role":"user","content":"hi"}]}' -D -
# 响应头 x-modelgate-attempted: mock-broken,mock-b 显示完整失败链
```

## 路由语义（面试要点）

| 机制 | 范围 | 触发 |
|---|---|---|
| retry | model group 内换 deployment | 5xx / 429 / 连接失败 / 超时（4xx 不重试） |
| fallback | 跨 group（按 `modelgate.router.fallbacks` 顺序） | 当前组全部失败 |
| cooldown | 摘掉单个 deployment（`allowed-fails` 次连续失败 → `cooldown-seconds`） | 失败率超阈值 |

流式请求的 failover 只发生在**首个 chunk 之前**——已有字节到达客户端就不再重试，否则会重复输出。

## 三层配额

维度与检查顺序：**key → tenant → model**（最具体者先拦，单个滥用 key 不会吃掉整个租户的额度）。

- **RPM**：滑动窗口，Redis 用 ZSET + `ZREMRANGEBYSCORE`，进程内用时间戳队列
- **TPM**：固定窗口（首 token 锚定），调用完成后按真实 usage 记账，**异步、不占关键路径**
- 三个维度在 Redis 场景下由**一个 Lua 脚本一次性原子校验**（1 次 RTT 而非 3 次）
- 超限返回 `429` + `Retry-After` + `x-ratelimit-scope` / `x-ratelimit-subject`
- 指标：`modelgate_ratelimit_rejected_total{scope}`

```yaml
modelgate:
  quota:
    backend: memory     # memory = 单实例；redis = 多实例共享计数
    window-seconds: 60
```

**两个后端的取舍**：`memory` 无外部依赖、零 RTT，但多副本下限流会漂；
`redis` 多一次 EVAL 往返（实测 p50 +1ms），换来集群一致——重启网关后计数不丢，已实测。

运行 Redis 集成测试需要本机有 `redis-server`（自动探测，缺失则跳过）：

```bash
mvn -s maven-settings.xml test -Dmodelgate.test.redis.binary=$HOME/.local/redis-src/bin/redis-server
```

## 压测

```bash
# 负载 profile：配额仍在热路径上，但阈值足够高不会触发 429
java -jar modelgate-proxy/target/modelgate-proxy-*.jar --spring.profiles.active=loadtest

jmeter -n -t loadtest/modelgate-chat.jmx \
  -Jhost=127.0.0.1 -Jport=8080 -Jthreads=50 -Jramp=5 -Jduration=30 -Jjtl=/tmp/gateway.jtl
loadtest/stats.sh /tmp/gateway.jtl      # 输出 QPS / P50 / P95 / P99 / 错误率
```

实测（50 并发 × 30s，上游固定 80ms）：网关开销 p50 **+1ms**、P99 **+7ms**，错误率 0%，
Redis 配额相对内存配额 p50 再 **+1ms**。完整数据与局限见 [loadtest/RESULTS.md](loadtest/RESULTS.md)。

## 响应头

| 头 | 含义 |
|---|---|
| `x-modelgate-model-id` | 实际命中的上游模型 |
| `x-modelgate-attempted` | 完整尝试链 |
| `x-modelgate-cost` | 本次调用成本（USD，按价目表计算） |
| `x-ratelimit-remaining-requests` | 当前窗口剩余请求数 |
| `Retry-After` / `x-ratelimit-scope` | 仅 429 时出现 |
