package com.superprogrammer.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.file.entity.StoredFileEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * stored_files（V40）归属登记 mapper。
 * load 咽喉点按 fileId 取 owner 行做归属校验。
 */
@Mapper
public interface StoredFileMapper extends BaseMapper<StoredFileEntity> {
}
