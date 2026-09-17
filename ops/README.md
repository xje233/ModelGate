# 可观测性接入（Prometheus + Grafana）

> 说明：这里的抓取配置与面板 JSON **已经实际跑起来验证过**（2026-09-17，本机 Docker 可用后）：
> Prometheus 抓 `proxy:8080` 状态 `up`、指标带 `instance_name=gateway` 标签；
> Grafana 的数据源（uid `prometheus`）与面板（uid `modelgate-overview`「ModelGate — 网关总览」）
> 由 provisioning 自动加载，不需要手工导入。完整记录见 [`../docker/README.md`](../docker/README.md) 第 6 节。
>
> 本文件第 2 节是**网关在宿主机直跑**的形态（抓 `host.docker.internal:8080`）；
> 走 compose 时是另一份配置（抓 `proxy:8080`）。两份各管一个场景，不是互相替代。

## 1. 指标清单

| 指标 | 类型 | 标签 | 说明 |
|---|---|---|---|
| `modelgate_requests_total` | counter | model_group, provider, stream, status | 请求总量，status=success / 429 / 5xx |
| `modelgate_request_latency` | timer | model_group, provider, stream | 端到端延迟（看 P99） |
| `modelgate_upstream_latency` | timer | provider | 上游耗时 |
| `modelgate_overhead_latency` | timer | model_group | **端到端 − 上游**，即网关自身开销 |
| `modelgate_ttft` | timer | provider | 流式首 token 延迟 |
| `modelgate_in_flight` | gauge | —— | 在途请求（上游变慢时 CPU 不涨但这里涨） |
| `modelgate_cache_lookup_total` | counter | model_group | 缓存查询次数 |
| `modelgate_cache_hit_total` / `_miss_total` | counter | model_group | 命中/未命中 |
| `modelgate_cache_similarity` | summary | model_group | 命中时的相似度分布（调阈值的依据） |
| `modelgate_cache_degraded_total` | counter | —— | 缓存链路异常降级次数 |
| `modelgate_coalesced_total` | counter | model_group | 被 in-flight 合并掉的请求数 |
| `modelgate_tokens_saved_total` | counter | model_group | 语义缓存省下的 token |
| `modelgate_cost_saved_usd_total` | counter | model_group | 省下的钱（USD） |
| `modelgate_ratelimit_rejected_total` | counter | scope (key/tenant/model) | 配额拒绝数 |
| `modelgate_circuit_state` | gauge | deployment, provider | **0=CLOSED 1=OPEN 2=HALF_OPEN** |
| `modelgate_circuit_opened_total` | counter | deployment, provider | 熔断触发次数 |

## 2. 启动 Prometheus + Grafana

**推荐：走 compose，接线全自动**

```bash
docker compose --profile observability up -d
# Prometheus → localhost:9090    Grafana → localhost:3000
```

`docker/grafana/datasource.yml` 与 `dashboards.yml` 把数据源和面板一起 provision 掉，
省掉下面那串手工点击。**这条路已经实跑验证**（见文件开头的说明）。

**备选：网关在宿主机直跑时的形态**（本节配置，抓 `host.docker.internal:8080`）

```bash
docker run -d --name prometheus -p 9090:9090 \
  -v "$PWD/ops/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml" \
  prom/prometheus

docker run -d --name grafana -p 3000:3000 grafana/grafana
```

然后手工接线：Connections → Data sources → 添加 Prometheus、Dashboards → Import 上传
`ops/grafana/modelgate-dashboard.json`。

> 注意：这两条 `docker run` 用的是默认 bridge 网络，**两个容器之间无法用容器名互相解析**，
> 所以数据源 URL 不能填 `http://prometheus:9090`——要么显式给它们一个自定义网络，
> 要么填 `http://host.docker.internal:9090`。这条路径本次未验证（验证走的是上面的 compose）。

## 3. 不用 Docker 的验证方式

```bash
curl -s localhost:8080/actuator/prometheus | grep ^modelgate_ | sort
```

这条命令是**实际跑过并且通过**的：所有 `modelgate_*` 指标都能正常导出（见
`verify/W3-verification.md` 的指标快照一节）。容器里同样成立——
`docker compose exec proxy curl -s localhost:8080/actuator/prometheus`。
