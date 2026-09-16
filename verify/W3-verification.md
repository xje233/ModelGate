# ModelGate W3 功能验证记录

> 全部为实际执行输出（单机 WSL2，Mock 上游 9001/9002，网关 8080，Redis 6379）。
> 压测数据见 [../loadtest/RESULTS-W3.md](../loadtest/RESULTS-W3.md)，W2 配额验证见 [../loadtest/RESULTS.md](../loadtest/RESULTS.md)。

## 1. 语义缓存

网关启动：`java -jar modelgate-proxy.jar`（默认配置，`backend: memory`，阈值 0.95），
key = `sk-local-test-1`。

### 1.1 命中与阈值行为

| # | 请求 | 结果 | 相似度 |
|---|---|---|---|
| 1 | `semantic cache probe alpha`（冷缓存） | `x-modelgate-cache: miss` | — |
| 2 | 完全相同 | **hit** | 1.0000 |
| 3 | 只多了一个句号 | **hit** | 0.9907 |
| 4 | `什么是语义缓存`（中文，不同内容） | miss | — |
| 5 | 中文问题原样重复 | **hit** | 1.0000 |
| 6 | `how to bake sourdough bread` | miss | — |

观察：0.95 阈值能吃掉"同一问题 + 标点/空白差异"，但对内容不同的提问不放行——
这正是阈值 0.95 的设计意图（0.85 能提高命中率，但会出现答非所问）。

### 1.2 跨 key 隔离（安全属性）

```
key-a 提交 semantic cache probe alpha  ->  x-modelgate-cache: hit   1.0000
key-b 提交完全相同的问题                ->  x-modelgate-cache: miss
```

namespace = `sc:{keyId}:{modelGroup}:{paramsHash}`，**key 是隔离边界的一部分**——
一个调用方的答案不会泄露给另一个调用方。

### 1.3 缓存指标（Prometheus）

混跑 6 次请求（3 个 prompt × 2）+ 1 次流式 + 1 次 fallback 后的快照：

```
modelgate_cache_hit_total{model_group="fast-medium"} 5.0
modelgate_cache_miss_total{model_group="broken"} 1.0
modelgate_cache_miss_total{model_group="fast-medium"} 1.0
modelgate_tokens_saved_total{model_group="fast-medium"} 115.0
modelgate_requests_total{model_group="broken",provider="mock",status="success",stream="false"} 1.0
modelgate_requests_total{model_group="fast-medium",provider="mock",status="success",stream="false"} 1.0
modelgate_requests_total{model_group="smart",provider="mock",status="success",stream="true"} 1.0
```

> 注：3 个 prompt 之间因为 mock 的 bigram 向量高度相似（>0.95）而互相命中，
> 所以 6 次请求只有 1 次真实 miss。真实 embedding 模型下这种相似度会低得多。

## 2. 熔断状态机

为便于观测，网关以 `--modelgate.router.cooldown-seconds=3 --modelgate.cache.enabled=false` 启动
（缓存开启时相似 prompt 会直接命中，请求根本到不了上游，熔断器自然不会被触发——这本身是个
值得注意的交互：**缓存会掩盖上游故障**，做故障演练时要先关缓存）。

`broken` 组的 deployment `mock-broken` 被配置成恒定返回 500。

| 步骤 | 操作 | 观测 |
|---|---|---|
| 1 | 连发 3 次（缓存关） | `circuit_opened_total{mock-broken}=1`，`circuit_state=1`（**OPEN**）；客户端 6 次全部 200，靠 fallback 到 `fast-medium` |
| 2 | 等 4s（>3s 冷却）再发 1 次 | 响应头 `x-modelgate-attempted: mock-broken,mock-a` —— **熔断器确实放行了一次 HALF_OPEN 探测**，探测再次失败 → `opened_total=2`，`state=1`（退回 OPEN） |
| 3 | 退避期内（第二次熔断冷却 = 2×3s = 6s）再发 | `x-modelgate-attempted: mock-b` —— **`mock-broken` 已被摘出轮转**，连尝试都没有 |
| 4 | 更早一轮：连续 6 次 | `opened_total` 稳定在 3，`mock-broken` 始终不在轮转中，客户端 200 不中断 |

状态映射（`modelgate_circuit_state`）：**0 = CLOSED，1 = OPEN，2 = HALF_OPEN**（显式映射，不依赖 `ordinal()`）。

> 退避倍数的精确验证（1×→2×→3×）由单元测试
> `RouterServiceTest#failedProbeReopensWithBackoff` 覆盖；外面只靠指标与响应头核对，
> 因为连续手工命令之间的真实墙钟时间无法精确控制。

## 3. 测试套件

```
modelgate-router   Tests run: 9   (加权分布 / 组内 retry / 跨组 fallback / 熔断开放与半开 / 失败率熔断 / 退避)
modelgate-quota    Tests run: 11  (进程内 6 + 真实 Redis+Lua 5)
modelgate-cache    Tests run: 18  (余弦 6 + 进程内 7 + 真实 Redis 5)
```

真实 Redis 相关测试在找不到 `redis-server` 二进制时**跳过而非失败**，不会阻塞普通 `mvn test`：

```bash
mvn -s maven-settings.xml test -Dmodelgate.test.redis.binary=$HOME/.local/redis-src/bin/redis-server
```

## 4. 复现命令

```bash
# 依赖
mvn -s maven-settings.xml clean install

# 服务
$HOME/.local/redis-src/bin/redis-server --port 6379 --save '' --appendonly no &
java -jar modelgate-mock/target/modelgate-mock-*.jar --server.port=9001 --mock.name=A &
java -jar modelgate-mock/target/modelgate-mock-*.jar --server.port=9002 --mock.name=B &
java -jar modelgate-proxy/target/modelgate-proxy-*.jar &

# 语义缓存
curl -s -D - -o /dev/null localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-local-test-1" -H "Content-Type: application/json" \
  -d '{"model":"fast-medium","messages":[{"role":"user","content":"semantic cache probe alpha"}]}' | grep -i x-modelgate-cache

# 熔断（需先关缓存）
java -jar modelgate-proxy/target/modelgate-proxy-*.jar \
  --modelgate.cache.enabled=false --modelgate.router.cooldown-seconds=3 &
curl -s -D - -o /dev/null localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-local-test-1" -H "Content-Type: application/json" \
  -d '{"model":"broken","messages":[{"role":"user","content":"cb probe"}]}' | grep -i x-modelgate-attempted

# 指标
curl -s localhost:8080/actuator/prometheus | grep ^modelgate_ | sort
```
