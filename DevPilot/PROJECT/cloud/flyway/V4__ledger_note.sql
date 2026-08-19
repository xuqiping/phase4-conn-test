-- P03 Step0：修复 P02 规格漂移——token_ledger 增加 note 字段，充值备注不再挪用 model 字段。
ALTER TABLE token_ledger ADD COLUMN note VARCHAR(200);
