# ModelGate W2 压测报告

> 目标：拿到 **QPS / P99 / 错误率** 三个数，并量化「网关自身开销」与「配额后端选型开销」。

## 1. 测试环境

| 项 | 值 |
|---|---|
| 机器 | 单机同进程域（WSL2，Ubuntu 20.04，x86_64） |
| JDK | Temurin 21.0.12 |
| 网关 | `modelgate-proxy`（Spring WebFlux / Reactor Netty），默认 JVM 参数，**未做任何调优** |
| 上游 | `modelgate-mock` × 2（9001/9002），**固定 p50 = 80ms**，逐帧 SSE |
| 压测客户端 | Apache JMeter 5.6.3，非 GUI 模式 |
| 压测脚本 | [`modelgate-chat.jmx`](modelgate-chat.jmx)（非流式 `POST /v1/chat/completions`） |
| Redis | 6.2.14（源码编译，本机 6379） |

**负载模型**：50 线程，ramp-up 5s，持续 30s，HTTP keep-alive，无 think time。

## 2. 结果

| 场景 | QPS | p50 | p95 | P99 | max | 错误率 |
|---|---|---|---|---|---|---|
| 直连 Mock（基线，绕过网关） | **567.6** | 81 | 82 | **83** | 111 | 0.00% |
| 网关 + 内存配额后端 | **557.6** | 82 | 86 | **90** | 373 | 0.00% |
| 网关 + Redis 配额后端 | **554.4** | 83 | 86 | **90** | 508 | 0.00% |

样本量：17000 / 16703 / 16589。

## 3. 解读

**网关自身开销（对比基线）**

- p50：81ms → 82ms（**+1ms**）
- P99：83ms → 90ms（**+7ms**）
- QPS：567.6 → 557.6（**-1.8%**）

**配额后端选型开销（Redis 相对内存）**

- p50：82ms → 83ms（**+1ms**，即一次 Lua `EVAL` 的往返）
- P99：90ms → 90ms（无差异）
- QPS：557.6 → 554.4（**-0.6%**）

结论：三层配额校验（含 Redis 场景下「一次 Lua 原子校验三个维度」）的成本在 **1ms 量级**，
在 P99 上不可分辨。这正是把三层合并进单个 Lua 脚本的价值——若拆成 3 次往返，开销会变成 3ms 量级。

## 4. 局限（必须如实说明）

1. **上游是瓶颈**：Mock 固定 80ms 延迟，理论上限 ≈ 50/0.08 = 625 QPS，三组都跑到 550~570 QPS，说明
   瓶颈在上游而不在网关。因此 **QPS 的横向对比意义有限，真正有效的是延迟差值与错误率**。
   要拿到网关自身的吞吐上限，需要把 Mock 延迟降到 1ms 量级（W3 再补一组）。
2. **未做 JVM 调优**：本轮全部是默认参数，堆大小/GC/堆外/连接池的调优对照属于 W3 内容。
3. **仅非流式**：流式（SSE 长连接）的 QPS/TTFT 需要单独的压测模型（连接数与并发不同），W3 补。
4. **单机同域**：客户端、网关、上游、Redis 在同一台机器上，网络开销被压缩到最小，
   真实跨机部署的 P99 会更高。

## 5. 复现

```bash
# 1) 启动上游与网关（负载 profile：配额仍在热路径上，但阈值足够高不会触发 429）
java -jar modelgate-mock/target/modelgate-mock-*.jar --server.port=9001 --mock.name=A &
java -jar modelgate-mock/target/modelgate-mock-*.jar --server.port=9002 --mock.name=B &
java -jar modelgate-proxy/target/modelgate-proxy-*.jar --spring.profiles.active=loadtest &

# 2) 基线：直连上游
jmeter -n -t loadtest/modelgate-chat.jmx \
  -Jhost=127.0.0.1 -Jport=9001 -Jthreads=50 -Jramp=5 -Jduration=30 -Jjtl=/tmp/mock-baseline.jtl

# 3) 经过网关
jmeter -n -t loadtest/modelgate-chat.jmx \
  -Jhost=127.0.0.1 -Jport=8080 -Jthreads=50 -Jramp=5 -Jduration=30 -Jjtl=/tmp/gateway-mem.jtl

# 4) Redis 配额后端
java -jar modelgate-proxy/target/modelgate-proxy-*.jar \
  --spring.profiles.active=loadtest --modelgate.quota.backend=redis &
jmeter -n -t loadtest/modelgate-chat.jmx \
  -Jhost=127.0.0.1 -Jport=8080 -Jthreads=50 -Jramp=5 -Jduration=30 -Jjtl=/tmp/gateway-redis.jtl

# 5) 汇总成 QPS / P99 / 错误率
loadtest/stats.sh /tmp/gateway-mem.jtl
```

## 6. 功能正确性验证（同批次完成）

| 验证项 | 结果 |
|---|---|
| key 维度（rpm=60，发 70 次） | `60 × 200` + `10 × 429`，精确 |
| 429 响应 | `Retry-After: 51`、`x-ratelimit-scope: key`、`x-ratelimit-subject: key-a`、OpenAI 风格错误体 |
| tenant 维度（key 有余量、tenant rpm=4） | `4 × 200` + `4 × 429`，scope 报 `tenant` |
| model 维度（model rpm=3） | `3 × 200` + `3 × 429`，scope 报 `model` |
| 模型白名单 | key-a 请求 `gpt-4o` → `403 permission_error` |
| 多 key 隔离 | key-a 被限流时 key-b 仍 `200` |
| Redis 后端计数落库 | 6 个 key（3 维度 × rpm/tpm），`zcard = 60`，`tpm = 840`（60 × 14 tokens） |
| Redis 后端并发原子性 | 10 路并发下仍严格 `60 × 200` / `5 × 429` |
| **集群安全性** | **重启网关 JVM 后仍返回 429**（状态在 Redis，不在堆内）— 内存后端无此特性 |
| 可观测 | `/actuator/prometheus` 导出 `modelgate_ratelimit_rejected_total{scope="key"}` |
| 单元/集成测试 | `modelgate-quota` 11 项全绿（进程内 6 + 真实 Redis+Lua 5） |
