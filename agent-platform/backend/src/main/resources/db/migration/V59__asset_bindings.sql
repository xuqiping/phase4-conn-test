-- ============================================================
-- V59: 项目资产库 · 资产↔画布绑定表（asset_bindings）
-- 功能：资产与画布节点双向追溯（plan §S7 / FR-008/009/011，设计方案 §八）
-- 设计要点：
--   1. 双向台账（设计方案 §八"双向追溯"）：
--      - PRODUCED：资产产自哪个画布节点（画布产出物入库时落，service 层捕获生成谱系）
--      - REFERENCE：资产被哪个画布节点引用（库→画布引用时落，记录引用版本快照）
--   2. 资产详情页"使用记录"= 查本表 by asset_id；画布节点徽标"来自资产·xx v2"= 查本表 by canvas_id+node_id。
--   3. 引用语义：asset_version 锁定引用时的版本快照，资产升级不影响已引用方（设计方案 §六/§八）。
--   4. 不加 FK 到 canvases（跨包解耦，canvas 包零回归；设计方案 §十四"避免循环依赖"）。canvas_id 仅作引用记录。
--   5. 继承 BaseEntity（审计：何时由谁绑定/解绑）。
-- 索引：asset_id（使用记录）、(canvas_id,node_id)（节点徽标）、(asset_id,bind_type)（重复入库检测）。
-- ============================================================

CREATE TABLE asset_bindings (
    id               BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by       BIGINT,
    created_at       TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_by       BIGINT,
    updated_at       TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    deleted          INTEGER                     NOT NULL DEFAULT 0,
    version          INTEGER                     NOT NULL DEFAULT 0,
    asset_id         BIGINT                      NOT NULL,                       -- 所属资产（FK assets）
    asset_version    INTEGER,                                                     -- 引用/产出的版本号（锁定版本快照，可空=未版本化时）
    canvas_id        BIGINT,                                                      -- 关联画布（canvases.id，不加 FK 跨包解耦）
    node_id          VARCHAR(64),                                                 -- 关联画布节点 id（节点内唯一标识）
    bind_type        VARCHAR(16)                 NOT NULL,                       -- 绑定类型：REFERENCE(被引用)/PRODUCED(产出自)
    CONSTRAINT fk_binding_asset FOREIGN KEY (asset_id)
        REFERENCES assets(id) ON DELETE CASCADE,
    CONSTRAINT chk_binding_type CHECK (bind_type IN ('REFERENCE','PRODUCED'))
);

-- 资产详情页「使用记录」：按资产查谁引用/产自
CREATE INDEX idx_asset_binding_asset ON asset_bindings(asset_id) WHERE deleted = 0;
-- 画布节点徽标「来自资产」：按画布+节点查
CREATE INDEX idx_asset_binding_node ON asset_bindings(canvas_id, node_id) WHERE deleted = 0;
-- 重复入库检测：节点已有 PRODUCED 绑定？（plan L5 / S7 重复入库检测）
CREATE INDEX idx_asset_binding_produced ON asset_bindings(canvas_id, node_id, bind_type) WHERE deleted = 0 AND bind_type = 'PRODUCED';

COMMENT ON TABLE  asset_bindings               IS '资产↔画布 双向追溯台账。PRODUCED(产自节点)/REFERENCE(被节点引用)。';
COMMENT ON COLUMN asset_bindings.asset_version IS '引用/产出的版本快照号；资产升级不影响已引用方（版本隔离）';
COMMENT ON COLUMN asset_bindings.canvas_id     IS '关联画布 id（不加 FK，跨包解耦保持 canvas 零回归）';
COMMENT ON COLUMN asset_bindings.bind_type     IS 'REFERENCE=资产被画布节点引用；PRODUCED=资产产自画布节点（入库时落）';

-- ============================================================
-- 回滚（rollback）：
-- DROP TABLE IF EXISTS asset_bindings;
-- ============================================================
