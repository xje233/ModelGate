-- H2 版的用量表：只为了让 `modelgate.usage.backend=jdbc` 在本地可跑。
-- 生产 DDL 见 ops/sql/schema.sql（MySQL），两边列名与语义完全一致，SQL 语句是同一套。
--
--   java -jar modelgate-proxy-*.jar --modelgate.usage.backend=jdbc \
--     --spring.sql.init.mode=always \
--     --spring.sql.init.schema-locations=classpath:db/schema-h2.sql

CREATE TABLE IF NOT EXISTS t_usage_log (
    request_id             VARCHAR(64)   NOT NULL,
    key_id                 VARCHAR(64),
    tenant_id              VARCHAR(64),
    model_group            VARCHAR(64),
    model_id               VARCHAR(128),
    provider               VARCHAR(32),
    prompt_tokens          INT           NOT NULL DEFAULT 0,
    completion_tokens      INT           NOT NULL DEFAULT 0,
    cached_prompt_tokens   INT           NOT NULL DEFAULT 0,
    cost_usd               DECIMAL(12, 6) NOT NULL DEFAULT 0,
    cache_hit              INT           NOT NULL DEFAULT 0,
    stream                 INT           NOT NULL DEFAULT 0,
    arm                    VARCHAR(16),
    attempted              VARCHAR(512),
    status                 VARCHAR(32),
    usage_date             DATE          NOT NULL,
    start_time             TIMESTAMP(3),
    completion_start_time  TIMESTAMP(3),
    end_time               TIMESTAMP(3),
    PRIMARY KEY (request_id)
);
CREATE INDEX IF NOT EXISTS idx_usage_tenant_date ON t_usage_log (tenant_id, usage_date);
CREATE INDEX IF NOT EXISTS idx_usage_model_date ON t_usage_log (model_group, usage_date);
CREATE INDEX IF NOT EXISTS idx_usage_date ON t_usage_log (usage_date);
