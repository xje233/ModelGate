# ModelGate W3 压测报告 —— JVM 调优对照 + 语义缓存效果

> 本轮要回答两个问题：
> 1. **JVM 参数能不能把网关的 QPS/P99 拉起来？**（W2 的上游 80ms 把 QPS 封在 550，看不出网关上限）
> 2. **语义缓存到底值多少钱？**（命中率、省下的 token 与金额、吞吐提升倍数）

## 1. 测试条件

| 项 | 值 |
|---|---|
| 机器 | 单机同域，WSL2 Ubuntu 20.04，**20 核 / 7GB**（JMeter + 2 个 Mock + 网关 + Redis 共享） |
| JDK | Temurin 21.0.12 |
| 上游 | `modelgate-mock` × 2，**p50 = 2ms**（W2 的 80ms 会把 QPS 封在 ~560，看不出网关上限） |
| 配额 | 关闭（见 `application-loadtest.yml` 注释：进程内滑动窗口在 10k+ QPS 下会累积百万级时间戳，会污染堆对比） |
| JMeter | 5.6.3，100 线程，ramp 5s，30s，HTTP keep-alive |
| GC 日志 | `-Xlog:gc`（G1）/ `-Xlog:gc*`（ZGC：`gc` 级别不输出暂停耗时，必须用 `gc*`） |

## 2. 结果：吞吐与延迟

| 轮次 | 配置 | QPS | p50 | p95 | **P99** | max | 错误率 |
|---|---|---|---|---|---|---|---|
| R1 | 默认 JVM（未显式设堆） | 13,126 | 6 | 12 | **21** | 189 | 0.00% |
| R2 | G1 + `-Xms2g -Xmx2g -XX:MaxDirectMemorySize=1g` | 13,865 | 6 | 11 | **18** | 229 | 0.00% |
| R3 | ZGC + `-Xms2g -Xmx2g -XX:MaxDirectMemorySize=1g` | 14,764 | 6 | 10 | **14** | 142 | 0.00% |
| R4 | G1（同 R2）+ **语义缓存开启** | **53,790** | 1 | 5 | **9** | 202 | 0.00% |

## 3. 结果：GC 暂停（同一 30s 压测窗口）

| 轮次 | 暂停次数 | 总暂停 | 平均暂停 | **最长暂停** |
|---|---|---|---|---|
| R1 默认 JVM | 161 | 521.8ms | 3.241ms | **30.7ms** |
| R2 G1 固定堆 2g | 28 | 281.8ms | 10.063ms | **27.3ms** |
| R3 ZGC 固定堆 2g | 75 | **2.6ms** | **0.035ms** | **0.1ms** |
| R4 G1 + 语义缓存 | 48 | 273.8ms | 5.705ms | **19.1ms** |

## 4. 结论

### 4.1 JVM 参数：动的是尾延迟，不是吞吐

- **吞吐没有实质变化**：13.1k / 13.9k / 14.8k，差距在**同机噪声范围内**——ZGC 同一配置我跑了两次，
  分别得到 11,626 和 14,764 QPS（相差 27%）。所以任何把这三行 QPS 排个名次的结论都不成立。
  这条本身就是结论：**在这个堆大小和请求体量下，换 GC 换不来吞吐**。
- **暂停时间差了两个数量级**：ZGC 平均 **0.035ms**、最长 **0.1ms**；G1 平均 **10ms**、最长 **27.3ms**。
  总暂停时间 2.6ms vs 281.8ms，差 **108 倍**。
- **暂停直接体现在 P99 上**：默认 JVM 最长暂停 30.7ms ↔ P99 21ms；ZGC 最长暂停 0.1ms ↔ P99 14ms。
  网关这种"每个请求都要序列化/反序列化大 JSON"的负载，堆分配速率极高，
  选 GC 的判据应该是 **P99 而不是平均值**。

### 4.2 固定堆与堆外确实有用

R1（默认堆 ≈1.75g）与 R2（显式 2g）都是 G1，但 GC 次数 161 → 28、总暂停 522ms → 282ms。
`-XX:MaxDirectMemorySize=1g` 针对的是 Reactor Netty 的 ByteBuf——**流式场景堆外才是大户，只看堆曲线会误判**。

### 4.3 语义缓存是目前为止收益最大的单项优化

- 吞吐 **13.9k → 53.8k QPS（3.9×）**，P50 从 6ms 降到 **1ms**（缓存命中/合并路径不再等上游）。
- 30s 内：**115,537 次缓存命中**、**1,496,642 次请求被 in-flight 合并**、省下 **2,310,740 tokens**。
- ⚠️ **R4 的 QPS 与 R1–R3 不同质**：R1–R3 是"经过上游"的吞吐，R4 是缓存命中/合并路径的吞吐
  （几乎不打上游）。列在同一张表里是为了对比"同一份压测脚本下系统能扛多少"，不是同一件事。

## 5. 局限（如实说明）

1. **同机共享**：JMeter、2 个 Mock、网关、Redis 抢同一台 7GB 机器，吞吐波动可达 ±27%；
   只有 GC 暂停时间这一类"进程内"指标可以跨轮次放心比较。
2. **窗口只有 30s**：包含 JIT 预热，绝对 P99 偏悲观。
3. **R1 未显式设堆**（≈1.75g），R2–R4 为 2g，堆大小不是唯一变量。
4. **R4 用的是重复 prompt**：这正是网关真实流量的常见形态（重试、机器人、批量探活），
   但它不代表"随机 prompt"场景——那种场景命中率会低得多。
5. ZGC 第一轮 11.6k QPS 与第二轮 14.8k 的差异**没有找到确定原因**，按噪声处理。

## 6. 复现

```bash
# 低延迟上游（2ms）
java -jar modelgate-mock/target/modelgate-mock-*.jar --server.port=9001 --mock.name=A \
  --mock.p50-ms=2 --mock.ttft-ms=1 --mock.chunk-interval-ms=1
java -jar modelgate-mock/target/modelgate-mock-*.jar --server.port=9002 --mock.name=B \
  --mock.p50-ms=2 --mock.ttft-ms=1 --mock.chunk-interval-ms=1

# R2：G1 固定堆 + GC 日志
java -Xms2g -Xmx2g -XX:+UseG1GC -XX:MaxDirectMemorySize=1g \
  -Xlog:gc:file=loadtest/raw/gc-r2-g1.log:time,uptime \
  -jar modelgate-proxy/target/modelgate-proxy-*.jar \
  --spring.profiles.active=loadtest --modelgate.cache.enabled=false

# R3：ZGC（注意 gc* 才有暂停耗时）
java -Xms2g -Xmx2g -XX:+UseZGC -XX:MaxDirectMemorySize=1g \
  "-Xlog:gc*:file=loadtest/raw/gc-r3-zgc.log:time,uptime" \
  -jar modelgate-proxy/target/modelgate-proxy-*.jar \
  --spring.profiles.active=loadtest --modelgate.cache.enabled=false

jmeter -n -t loadtest/modelgate-chat.jmx -Jhost=127.0.0.1 -Jport=8080 -JapiKey=sk-load-test \
  -Jthreads=100 -Jramp=5 -Jduration=30 -Jjtl=loadtest/raw/r2-g1-tuned.jtl
loadtest/stats.sh loadtest/raw/r2-g1-tuned.jtl
```

原始 `.jtl` 逐样本数据在 `loadtest/raw/`（体积 45–180MB，已在 `.gitignore` 中排除；
汇总与 GC 日志入库）。重新生成：`loadtest/run-all.sh`（环境变量可调线程数与时长）。
