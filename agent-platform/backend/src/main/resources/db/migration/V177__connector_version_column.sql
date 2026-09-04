-- V177：连接器两表补 version 乐观锁列
-- 根因：KnowledgeConnector/KnowledgeConnectorDoc 实体继承 BaseEntity（含 @Version Integer version），
-- MyBatis-Plus 生成的 SELECT/UPDATE 会带上 version 列，但 V175/V176 建表漏了该列，
-- 真库运行时连接器轮询（ConnectorSyncTxService.listEnabled）每轮 BadSqlGrammar：
-- 「字段 "version" 不存在」。单测因 mock 数据层未暴露（WP6 22/22 绿仍漏）。
-- 惯例对齐：version INTEGER NOT NULL DEFAULT 0（同 V102/V103 乐观锁列写法）。
ALTER TABLE knowledge_connectors
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE knowledge_connector_docs
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
