# 容器化运行手册

把整个 ModelGate 环境（网关 + 两个 Mock 上游 + Redis）打成一键启动的 compose 栈。
**网关与 Mock 是同一份 Dockerfile 的两个 target**，不是一个模块一个镜像——它们只差
一个启动参数，拆成两个 Dockerfile 会让 Spring Boot 依赖层的缓存失效两次。

```bash
docker compose up -d --build                 # redis + mock-a + mock-b + proxy
docker compose --profile observability up -d # 再加 Prometheus + Grafana
docker compose --profile loadtest run --rm jmeter
```

## 1. 为什么是这样一个结构

```
                ┌───────────────┐
   :8080  ───►  │     proxy     │     Java 21 / WebFlux
                └───┬───────┬───┘
                    │       │
       quota+cache  │       │  weighted routing
          (shared)  │       │
                ┌───▼───┐   ├──────────────┐
                │ redis │   │              │
                └───────┘   │              │
                        ┌───▼───┐      ┌───▼───┐
                        │mock-a │      │mock-b │   :9001 / :9002
                        └───────┘      └───────┘
```

三个刻意的设计选择，都踩在「网关类项目容易做错」的地方：

**① 上游按服务名寻址，不写 127.0.0.1。**
`application.yml` 里 `base-url` 用的是 `${MODELGATE_MOCK_A_URL:http://127.0.0.1:9001}`，
compose 把它覆盖成 `http://mock-a:9001`。**一份路由定义同时服务宿主机运行与容器运行**，
不需要为了容器再复制一套 `application-docker.yml` 里的 deployment 列表。
（Spring Boot 对 list 是整体替换而非逐项合并，所以「在 profile 里只覆盖 base-url」
这条路是不通的——要么全量重写，要么用占位符。这里选了后者。）

**② Redis 用 `noeviction`，不是 `allkeys-lru`。**
配额计数器被淘汰 = 租户白拿流量，这是正确性问题不是性能问题。缓存键自带 TTL，
不需要靠淘汰策略来回收。这条写进了 `docker-compose.yml` 的注释里。

**③ 容器内不提权、不写盘。**
运行时镜像里是非 root（uid 10001）。状态在 Redis、账目在 DB、日志在 stdout，
所以容器自身文件系统只读也完全能跑。GC 日志也走 stdout——`docker compose logs proxy > gc.log`
就是完整的提取步骤，不需要为「让非 root 能写宿主目录」折腾权限。

## 2. 与宿主机运行的关系

两套运行方式共用同一份配置，差别只有环境变量：

| | 宿主机直跑 | 容器内 |
|---|---|---|
| 上游地址 | `127.0.0.1:9001/9002`（默认值） | `mock-a:9001` / `mock-b:9002` |
| Redis | 通常没起 → `quota/cache` 用 memory | 真 Redis → 默认吃 Redis |
| Profile | 无 / `loadtest` | `docker` |
| JVM 参数 | 命令行 | `JAVA_OPTS` → `docker/entrypoint.sh` |

`ops/README.md` 里那套 Prometheus/Grafana 是「Prometheus 在容器、网关在宿主」的形态，
抓取目标是 `host.docker.internal:8080`；本目录的 `docker/prometheus/prometheus.yml`
是同网络形态，抓 `proxy:8080`。**两份配置并存，各管一个场景，不是一个替代另一个。**

## 3. 常用操作

```bash
# 端到端健康情况
docker compose ps
curl -s localhost:8080/actuator/health

# 非流式
curl -s localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer sk-local-test-1' -H 'Content-Type: application/json' \
  -d '{"model":"fast-medium","messages":[{"role":"user","content":"hello"}]}'

# 流式（SSE）
curl -sN localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer sk-local-test-1' -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"model":"fast-medium","stream":true,"messages":[{"role":"user","content":"hi"}]}'

# 配额计数落在 Redis 哪几个 key 上
docker compose exec redis redis-cli keys 'q:*'

# 看 GC（stdout 输出的价值）
docker compose logs proxy | grep -E 'Pause Young|Pause Full'
```

## 4. 调参开关

全部通过环境变量注入，不改文件、不重建镜像：

```bash
# 单个实例、不依赖 Redis（复现宿主机跑法）
MODELGATE_QUOTA_BACKEND=memory MODELGATE_CACHE_BACKEND=memory docker compose up -d proxy

# 制造上游延迟梯度（压测用）
MOCK_P50_MS=200 docker compose up -d mock-a

# 压测网关的上游转发路径时，先关掉语义缓存（原因见第 4.1 节）
MODELGATE_CACHE_ENABLED=false docker compose up -d proxy

# 压测：先压直连 Mock 的基线，再压网关，差值就是网关自身开销
# （两个开关都是必须的，原因见 4.1）
SPRING_PROFILES_ACTIVE=docker,loadtest docker compose up -d proxy
docker compose --profile loadtest run --rm --no-deps jmeter
JMETER_THREADS=100 JMETER_DURATION=60 docker compose --profile loadtest run --rm --no-deps jmeter
./loadtest/stats.sh loadtest/raw/docker-gateway.jtl

# JVM 对照实验：只换收集器，其余不动
JAVA_OPTS='-XX:+UseZGC -Xms256m -Xmx256m -XX:MaxDirectMemorySize=128m -Xlog:gc' \
  docker compose up -d --force-recreate proxy
```

### 4.1 沿这条路压测之前必须知道的三件事

**① 必须激活 `loadtest` profile。**
`modelgate-chat.jmx` 默认发 `Bearer sk-load-test`，而这个 key 只定义在
`application-loadtest.yml` 里。只跑 `docker` profile 的话每次迭代都是 401——实测
100% 错误率、QPS 虚高到 4000（全是拒绝）。那个 profile 同时把配额设为无限
（`rpm: -1`），这正是 `RESULTS*.md` 的方法学：让数字反映转发/缓存路径而不是 429 路径。

注意 profile 里的 `security.keys` 是**整体替换**基线的列表，所以激活 `loadtest` 后
演示用的 `sk-local-test-1/2/3` 会失效（实测 401），这是预期行为、不是故障。

**② 必须加 `--no-deps`。**
`docker compose run` 默认会先 reconcile 依赖服务，于是**把你刚配好的网关按
docker-compose.yml 的默认值重建了一遍**，profile 与后端开关全部回退。实测症状：
设好 `SPRING_PROFILES_ACTIVE=docker,loadtest` 后跑一次不带 `--no-deps` 的 `run`，
容器 env 变回 `docker`，`sk-load-test` 立即失效。压测器不该有权改动被测系统。

**③ jtl 会累加，`jmeter -f` 管不住它。**
`-f` 只删除 `-l` 指定的文件；本项目的 jtl 路径写在 jmx 内部的 ResultCollector 里，
所以第二轮会追加到第一轮之后，`stats.sh` 会把两个实验混成一个汇总（实测出现
`duration=176s` / `samples=155253` 这种明显超出单轮的值）。compose 里的 `jmeter`
服务用 `rm -f` 解决；**自己手敲命令时要记得先删**。

### 4.2 缓存开还是关：这决定你量的是哪条路径

`modelgate-chat.jmx` 每次迭代发的是**同一个 prompt**。缓存开着时第 2 次起全是命中，
QPS/P99 描述的就是缓存路径，数字会好看到失真。两条路径都实测过：

| 配置 | QPS | p50 | p99 | 错误率 |
|---|---|---|---|---|
| `MODELGATE_CACHE_ENABLED=false`（上游转发路径） | 219 | 84ms | 96ms | 0% |
| `MODELGATE_CACHE_ENABLED=true`（缓存命中路径） | 6175 | 3ms | 9ms | 0% |

缓存关那组的 p50=84ms 与 Mock 的 `p50-ms: 80` 吻合，说明量到的确实是上游转发；
开那组 p50=3ms 说明请求根本没出网关。**引用数字前先说清量的是哪条路径**，否则被问
"你这 QPS 是缓存命中还是真实转发"就答不上来。

同源的另一个坑：验证**权重分布**时也别用固定 prompt。用固定 prompt 数 `"model"` 字段，
数到的是缓存里那条响应的模型名而不是每次的真实选路——实测会把 7:3 显示成 100:0
（我因此误判过一次路由 bug）。每个请求换一个 prompt 就正常了：

```bash
for i in $(seq 1 100); do
  curl -s http://127.0.0.1:8080/v1/chat/completions \
    -H 'Authorization: Bearer sk-local-test-2' -H 'Content-Type: application/json' \
    -d "{\"model\":\"fast-medium\",\"messages\":[{\"role\":\"user\",\"content\":\"probe $i\"}]}"
done | grep -o '"model":"mock-fast-[ab]"' | sort | uniq -c
# => 70 mock-fast-a / 30 mock-fast-b
```

## 5. 已知边界

- **不含 MySQL。** 网关默认跑 H2 内存库；`ops/sql/schema.sql` 那条 MySQL 路线还需要
  往 `modelgate-proxy` 加 `com.mysql:mysql-connector-j` 依赖，那是**改项目**而不是
  改环境，所以没塞进 compose（塞一个连不上的服务只会误导）。
- **镜像内构建会跳测。** `mvn -DskipTests`：`modelgate-testkit` 需要本机 redis-server
  二进制，容器不是证明正确性的地方（CI 与宿主机是）。
- **压测写出的 jtl 归 root 所有。** jmeter 容器以 root 跑并往 `loadtest/raw` 挂载写文件，
  宿主机侧看到的是 root 属主文件。`stats.sh` 只读，不受影响；要清理用 `sudo`。
- **首次构建慢。** 需要拉 `maven:3.9-eclipse-temurin-21`（~1.2GB）并在容器内解析一次
  Spring Boot 依赖树。`.m2` 用 BuildKit cache mount 持久化，第二次构建只编译源码。
- **压测器必须在 host 网络里跑。** compose bridge 下压测器约 3.2k req/s 就崩，
  报 56% `NoRouteToHostException`（Docker 网络栈的连接 churn 上限），而**同期网关日志
  0 异常**；同一轮改 host 网络后 7.7k req/s、0% 错误、p99 7ms。所以 `jmeter` 服务配了
  `network_mode: host`——吞吐数字只能来自这个形态，bridge 形态的数字是网络伪影，
  和网关性能无关。
- **容器内 JMeter 与 `RESULTS*.md` 不同质。** 报告用的是 JMeter 5.6.3；本机镜像站的
  `apache/jmeter` 拉不动（403），回退的 `justb4/jmeter:5.1.1` 跑在 **Java 8** 上。
  它能验证编排与链路，但它产出的 QPS 不能和报告里的数字并列比较。
- **镜像偏大（proxy 482MB / mock 453MB）。** 主要是基座 `eclipse-temurin:21-jre-jammy`
  自身的 407MB。想瘦身可换 `-alpine` 变体，代价是 musl libc（本项目走 NIO，风险低，
  但没验证过）。

## 6. 验证记录

以下都是在本机实跑过的结果，不是"配置看起来对"。

| 项 | 结果 |
|---|---|
| 四个容器 | 全部 healthy；`/actuator/health` → `{"status":"UP"}` |
| 非流式 | 200，`x-modelgate-model-id` / `x-modelgate-attempted` / cache / cost / usage 头齐全 |
| 流式 SSE | 逐帧 + `[DONE]` |
| 跨组 fallback | `x-modelgate-attempted: mock-broken,mock-b`——证明按服务名寻址真的可达 |
| 语义缓存（Redis 后端） | 同 prompt 第 2 次 `hit` + `similarity 1.0000`；改述仍 `miss` |
| 配额（Redis + Lua） | 53×200 + 17×429（53 = 60 − 本会话已用 7 次）；`Retry-After: 50`、`x-ratelimit-scope: key` |
| Redis 键 | `q:key:` / `q:tenant:` / `q:model:` 三层 + 语义缓存 `sc:` 键 |
| 错误协议 | 401（缺失/伪造 key）、403（白名单外）、404（白名单为 `*` 时的未知模型） |
| 容器内 GC 日志 | `[5.242s][info][gc] GC(6) Pause Young (G1 Evacuation Pause) 153M->23M(256M)` |
| 镜像内 jar | 39MB，属主 `modelgate`（非 root）；容器内 Maven 真实构建了 11 个模块 |
| Prometheus | target `proxy:8080` health=up、lastError 空；指标带 `instance_name=gateway` |
| Grafana | 数据源 uid `prometheus` 自动 provision；面板 `modelgate-overview`「ModelGate — 网关总览」自动加载 |
| 压测 | 见 4.2 的两行对照，两条路径均 0% 错误 |

> 压测口径：容器内 `justb4/jmeter:5.1.1`（Java 8），20 线程 / 20s / host 网络。
> 与 `loadtest/RESULTS*.md` 的 JMeter 5.6.3 不同质，**数字不可直接比对**，
> 这里只用于验证容器编排与两条路径的相对差异。

