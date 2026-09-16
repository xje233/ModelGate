-- ModelGate 控制面 + 用量表（MySQL 8）
--
-- 已接入代码：
--   t_usage_log  —— 用量与成本明细，由 JdbcUsageStore 异步批量写入
--
-- 已定义、尚未接线（当前由 application.yml 承担，切换时只需替换对应的 Registry/Properties）：
--   t_tenant / t_api_key / t_model —— 租户、密钥（token 只存哈希）、模型路由配置
--
-- 本地用 H2 跑演示时用 modelgate-proxy/src/main/resources/db/schema-h2.sql（同一套列）。

CREATE TABLE IF NOT EXISTS t_tenant (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_code    VARCHAR(64)  NOT NULL UNIQUE,
    rpm_limit      INT          NULL COMMENT '租户级 RPM，NULL 表示不限',
    tpm_limit      BIGINT       NULL,
    max_budget_usd DECIMAL(12, 4) NULL COMMENT '周期预算，与 key 级取最严',
    status         TINYINT      NOT NULL DEFAULT 1,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '租户';

CREATE TABLE IF NOT EXISTS t_api_key (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    token_hash     CHAR(64)     NOT NULL UNIQUE COMMENT 'SHA-256，绝不存明文',
    key_alias      VARCHAR(128) NULL,
    tenant_id      BIGINT       NOT NULL,
    user_id        VARCHAR(64)  NULL COMMENT '用户维度配额锚点，同一 key 也可区分个人',
    models         JSON         NULL COMMENT '可用 model group 白名单，["*"] 表示不限',
    rpm_limit      INT          NULL,
    tpm_limit      BIGINT       NULL,
    max_budget_usd DECIMAL(12, 4) NULL,
    expires_at     DATETIME     NULL,
    status         TINYINT      NOT NULL DEFAULT 1,
    KEY idx_key_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'API 密钥';

-- 一个 model group 对应多行 deployment；group_name 是对外别名，model_id 才是上游真实模型
CREATE TABLE IF NOT EXISTS t_model (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_name VARCHAR(64)  NOT NULL,
    provider   VARCHAR(32)  NOT NULL,
    model_id   VARCHAR(128) NOT NULL,
    base_url   VARCHAR(256) NULL,
    weight     INT          NOT NULL DEFAULT 10,
    canary     TINYINT      NOT NULL DEFAULT 0 COMMENT '1 = 该 deployment 是灰度臂',
    enabled    TINYINT      NOT NULL DEFAULT 1,
    KEY idx_model_group (group_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '模型路由配置';

-- 用量明细：热路径只做入队，由后台线程批量 INSERT（见 JdbcUsageStore）
-- usage_date 由应用写入，避免在 SQL 里用各数据库不一致的日期函数
CREATE TABLE IF NOT EXISTS t_usage_log (
    request_id            CHAR(36)      NOT NULL,
    key_id                VARCHAR(64)   NULL,
    tenant_id             VARCHAR(64)   NULL,
    model_group           VARCHAR(64)   NULL,
    model_id              VARCHAR(128)  NULL,
    provider              VARCHAR(32)   NULL,
    prompt_tokens         INT           NOT NULL DEFAULT 0,
    completion_tokens     INT           NOT NULL DEFAULT 0,
    cached_prompt_tokens  INT           NOT NULL DEFAULT 0 COMMENT 'prompt cache 命中部分，计价倍率不同',
    cost_usd              DECIMAL(12, 6) NOT NULL DEFAULT 0,
    cache_hit             TINYINT       NOT NULL DEFAULT 0 COMMENT '语义缓存命中（本次未打上游）',
    stream                TINYINT       NOT NULL DEFAULT 0,
    arm                   VARCHAR(16)   NULL COMMENT 'canary / stable / single，灰度归因',
    attempted             VARCHAR(512)  NULL COMMENT '实际尝试过的 deployment 链',
    status                VARCHAR(32)   NULL,
    usage_date            DATE          NOT NULL,
    start_time            DATETIME(3)   NULL,
    completion_start_time DATETIME(3)   NULL COMMENT '首 token 时间，用于 TTFT',
    end_time              DATETIME(3)   NULL,
    PRIMARY KEY (request_id),
    KEY idx_usage_tenant_date (tenant_id, usage_date),
    KEY idx_usage_model_date (model_group, usage_date),
    KEY idx_usage_date (usage_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用量与成本明细';
