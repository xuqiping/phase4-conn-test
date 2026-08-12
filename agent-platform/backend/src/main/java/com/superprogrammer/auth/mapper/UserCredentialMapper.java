// agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/UserCredentialMapper.java
package com.superprogrammer.auth.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.auth.entity.UserCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * user_credential Mapper。
 *
 * <p>所有查询默认带 {@code deleted=0}（MyBatis-Plus {@code @TableLogic} 自动追加，
 * 配合 V102 的部分唯一索引，软删的解绑凭证不参与唯一性约束）。
 */
@Mapper
public interface UserCredentialMapper extends BaseMapper<UserCredential> {

    /** 登录路径：按 (类型 + 标识) 定位唯一凭证。命中 V102 的 uk_credential_type_identifier 索引。 */
    default UserCredential findByTypeAndIdentifier(String credentialType, String identifier) {
        LambdaQueryWrapper<UserCredential> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserCredential::getCredentialType, credentialType)
                .eq(UserCredential::getIdentifier, identifier);
        return selectOne(wrapper);
    }

    /** 设置页：列出该用户所有可用凭证。命中 idx_credential_user 索引。 */
    default List<UserCredential> findByUserId(Long userId) {
        LambdaQueryWrapper<UserCredential> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserCredential::getUserId, userId);
        return selectList(wrapper);
    }

    /** 绑定判重：该用户是否已有同类凭证（一人一类型一凭证）。命中 uk_user_credential_type 索引。 */
    default Long countByUserIdAndType(@Param("userId") Long userId, @Param("credentialType") String credentialType) {
        LambdaQueryWrapper<UserCredential> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserCredential::getUserId, userId)
                .eq(UserCredential::getCredentialType, credentialType);
        return selectCount(wrapper);
    }
}
