# 可观测性接入（Prometheus + Grafana）

> 说明：本机的 Grafana/Prometheus 未实际启动（WSL 里没有 Docker 守护进程），所以这里的
> 面板 JSON 与抓取配置**是可直接导入的产物，但没有经过渲染验证**。下面第 3 节给出了
> 无需 Docker 的验证方式（直接看网关暴露的指标）。

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

```bash
# 用 Docker（需要 docker daemon）
docker run -d --name prometheus -p 9090:9090 \
  -v "$PWD/ops/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml" \
  prom/prometheus

docker run -d --name grafana -p 3000:3000 grafana/grafana
```

然后在 Grafana 里：Connections → Data sources → 添加 Prometheus（URL `http://prometheus:9090`，
名字取 `prometheus`），再 Dashboards → Import → 上传 `ops/grafana/modelgate-dashboard.json`。

## 3. 不用 Docker 的验证方式

```bash
curl -s localhost:8080/actuator/prometheus | grep ^modelgate_ | sort
```

这条命令是**实际跑过并且通过**的：所有 `modelgate_*` 指标都能正常导出（见
`verify/W3-verification.md` 的指标快照一节）。
