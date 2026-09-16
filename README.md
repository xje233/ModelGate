# ModelGate

面向大模型调用的统一接入与流量治理网关：多模型路由 / 多维配额 / 语义缓存 / 熔断降级 / 成本核算。
实施计划见 [plan.md](plan.md)。

## 模块

```
modelgate-core        canonical OpenAI 格式类型（零依赖，全系统唯一数据契约）
modelgate-providers   Provider SPI + OpenAI 兼容泛化实现
modelgate-router      加权路由 + retry(组内) / fallback(跨组) / cooldown(单部署)
modelgate-client      WebClient 传输 + SSE 解析 + 成本计算
modelgate-mock        Mock 上游（可调延迟 / 逐帧 pacing / 注错），压测与故障演练专用
modelgate-proxy       Spring Boot WebFlux 网关（对外交付形态）
```

## 构建与运行

```bash
# 构建（本机注意：全局 settings.xml 的 localRepository 指向 Windows 路径，需覆盖）
mvn -Dmaven.repo.local=$HOME/.m2/repository clean package

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

## 响应头

- `x-modelgate-model-id`：实际命中的上游模型
- `x-modelgate-attempted`：完整尝试链
- `x-modelgate-cost`：本次调用成本（USD，按价目表计算）
