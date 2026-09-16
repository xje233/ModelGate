# ModelGate W4 功能验证记录

> 全部为实际执行输出（单机 WSL2：Mock 上游 9001/9002，网关 8080）。
> 压测与 JVM 数据见 [../loadtest/RESULTS-W3.md](../loadtest/RESULTS-W3.md)，前几轮验证见
> [W3 记录](W3-verification.md) 与 [../loadtest/RESULTS.md](../loadtest/RESULTS.md)。

## 1. 灰度分流（canary / A-B）

臂的归属由 **API key 哈希分桶**决定，同一 key 永远落在同一臂（否则实验数据没法和基线比）。

```
sk-local-test-1    x-modelgate-arm: stable  x-modelgate-model-id: mock-fast-a
sk-local-test-2    x-modelgate-arm: canary  x-modelgate-model-id: mock-fast-b
sk-local-test-3    x-modelgate-arm: canary  x-modelgate-model-id: mock-fast-b
```

- 同一 key 连发 3 次：臂与上游模型完全一致（粘性）。
- 把 `canary-percentage` 调成 0 后，两个 key 都变成 `stable` 且只走 `mock-fast-a`
  —— **stable 臂不给 canary 部署分流量**。
- 分布正确性由单元测试覆盖（5,000 个 key → 10% ±3%）。

### 1.1 顺带修掉的一个真实缺陷：分桶不能直接用 hashCode

排查"三个 key 为什么全落在 canary"时发现：`key-a`..`key-h` 的 `hashCode` 是**连续**的
（…411、…412、…413），桶号因此也连续（11、12、13…）。于是 30% 灰度对
`key-a`..`key-s` 实际是 **19/26 = 73%**——按名字顺序发放 key（发放脚本的常规做法）
会让整批调用方一起进同一臂，既不是随机抽样，也不构成受控的爆炸半径。

修复：分桶前过一遍 MurmurHash3 的 32 位 finalizer 做雪崩，仍然确定（跨 JVM/副本一致）。

```
修复前: key-a..key-h → 11,12,13,14,15,16,17,18      （连续 → 同臂）
修复后: key-a..key-h → 50,19,23, 6,85,30,32,89      （30% 覆盖 4/11 ≈ 36%）
```

回归测试 `sequentialCallerIdsDoNotClumpIntoOneArm` 锁住这个行为。

## 2. 用量与成本落库

### 2.1 内存后端（默认，零依赖）

发完混合流量（重复 prompt 触发缓存命中、流式、fallback）后的聚合：

```
dimension=MODEL
  ab-test       requests=9   prompt=60   completion=177  cached=34  cache_hits=3  cost=0.000114
  fast-medium   requests=6   prompt=24   completion=108  cached=0   cache_hits=5  cost=0.000066
  smart         requests=1   prompt=0    completion=0    ...                       cost=0
  broken        requests=1   prompt=4    completion=18   ...                       cost=0.000011

dimension=ARM        （灰度归因：一次查询就能按臂对比成本）
  stable  requests=3  prompt=20 completion=59  cached=0   cost=0.000038
  canary  requests=6  prompt=40 completion=118 cached=34  cost=0.000076
  single  requests=8  prompt=28 completion=126 cached=0   cost=0.000077

dimension=DATE
  2026-09-16  requests=17 prompt=88 completion=303 cached=34 cache_hits=8 cost=0.000191
```

`fast-medium` 的 6 次请求里 5 次是语义缓存命中（`cache_hits=5`），成本只有一次上游的钱。

### 2.2 JDBC 后端（H2 本地演示 / MySQL 生产同一套 SQL）

```
java -jar modelgate-proxy-*.jar --modelgate.usage.backend=jdbc \
  --spring.sql.init.mode=always \
  --spring.sql.init.schema-locations=classpath:db/schema-h2.sql
```

```
/admin/usage/stats                      -> {"queued":0,"written":5,"dropped":0}
/admin/usage?dimension=MODEL            -> ab-test 1 条 / fast-medium 4 条（cache_hits=3）
/admin/usage?dimension=ARM              -> canary 1 / single 4
```

聚合结果来自 SQL 的 `GROUP BY`（列名由 `UsageDimension` 映射、非用户输入），
`usage_date` 由应用写入，避免各数据库日期函数不一致。生产 DDL 见 `ops/sql/schema.sql`。

## 3. 成本核算：prompt cache 系数确实生效

同一段 ~1000 token 的 prompt，分别打到两个臂：

| 臂 | 上游 | prompt cache | `x-modelgate-cost` |
|---|---|---|---|
| stable | mock-fast-a | 无 | **0.000164** |
| canary | mock-fast-b | 90% 命中 | **0.000097** |

降幅 41%，与"90% 输入按 0.5 倍计价"的预期一致
（`(1004 tokens, 903 cached)`: 全额 150.6 → 折后 82.9，输出侧不变）。

> 排查记录：一开始用短 prompt 对比，两臂都显示 `0.000013`，看似折扣没生效。
> 实际是 6 位小数把差异四舍五入掉了——**成本对比必须用足够大的 token 量，
> 否则结论会被显示精度骗过去**。这条也写进了报告。

## 4. 观察到的异常（如实记录）

- **流式记账 1 次未采到 usage**：最早一次流式请求的收据里 `prompt_tokens=0`。
  随后 7 次流式（含 `-N` 与 `-o /dev/null` 两种客户端写法）全部正常记录 token，
  **未能复现**。影响面：该请求少记了一次账（`/admin/usage/stats` 的 `dropped` 为 0，
  说明不是队列溢出）。生产环境应加一条断言：流式请求结束时 `usage == null` 即为异常并告警。
- `smart` 流式首条记录 `prompt=0/completion=0` 与上条是同一现象。

## 5. 测试套件

```
modelgate-router  15 项（加权分布 / retry / fallback / 熔断状态机 / 灰度粘性与分布 / 分桶雪崩回归）
modelgate-quota   11 项（进程内 6 + 真实 Redis+Lua 5）
modelgate-cache   18 项（余弦 6 + 进程内 7 + 真实 Redis 5）
modelgate-usage   10 项（内存聚合 5 + H2 批量落库与 SQL 聚合 5）
modelgate-client   8 项（成本系数 / 缓存倍率 / 输入钳制）
```

## 6. 复现命令

```bash
mvn -s maven-settings.xml clean install

# 上游：B 实例模拟 90% 的 prompt cache 命中
java -jar modelgate-mock/target/modelgate-mock-*.jar --server.port=9001 --mock.name=A &
java -jar modelgate-mock/target/modelgate-mock-*.jar --server.port=9002 --mock.name=B \
  --mock.simulated-cached-ratio=0.9 &
java -jar modelgate-proxy/target/modelgate-proxy-*.jar &

# 灰度：看 x-modelgate-arm 与命中的上游模型
curl -s -D - -o /dev/null localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-local-test-2" -H "Content-Type: application/json" \
  -d '{"model":"ab-test","messages":[{"role":"user","content":"arm probe"}]}' \
  | grep -i x-modelgate-arm

# 用量：按维度聚合（管理员接口，同样需要 Bearer）
curl -s "localhost:8080/admin/usage?dimension=ARM&from=2026-09-16&to=2026-09-16" \
  -H "Authorization: Bearer sk-local-test-1"
curl -s localhost:8080/admin/usage/stats -H "Authorization: Bearer sk-local-test-1"
```
