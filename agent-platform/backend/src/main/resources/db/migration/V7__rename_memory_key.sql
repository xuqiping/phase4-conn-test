-- Rename reserved word columns for H2 compatibility
ALTER TABLE user_memories RENAME COLUMN key TO memory_key;
ALTER TABLE user_memories RENAME COLUMN value TO memory_value;
