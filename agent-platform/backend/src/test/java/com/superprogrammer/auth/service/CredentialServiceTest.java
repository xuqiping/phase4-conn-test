// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/CredentialServiceTest.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.auth.dto.CredentialVO;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.auth.mapper.UserCredentialMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CredentialService 单测（Chunk A）。
 * 覆盖：登录查询 / 建凭证（并发冲突转 CONFLICT）/ 标记验证（幂等）/ 列表脱敏 / 解绑前计数。
 */
@ExtendWith(MockitoExtension.class)
class CredentialServiceTest {

    @Mock
    private UserCredentialMapper credentialMapper;

    private CredentialService service;

    /** 填充 MP lambda 缓存，使 LambdaQueryWrapper 能解析 SFunction 列名（承 AssetProjectServiceTest 范式）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, UserCredential.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new CredentialService(credentialMapper);
    }

    @Test
    void findForLogin_blankIdentifier_returnsNull() {
        assertNull(service.findForLogin(UserCredential.TYPE_PHONE, ""));
        assertNull(service.findForLogin(UserCredential.TYPE_PHONE, null));
        verifyNoInteractions(credentialMapper);
    }

    @Test
    void findForLogin_found_returnsCredential() {
        UserCredential c = new UserCredential();
        c.setUserId(1L);
        c.setCredentialType(UserCredential.TYPE_PHONE);
        c.setIdentifier("13800138000");
        when(credentialMapper.findByTypeAndIdentifier(UserCredential.TYPE_PHONE, "13800138000")).thenReturn(c);

        UserCredential found = service.findForLogin(UserCredential.TYPE_PHONE, "13800138000");
        assertSame(c, found);
    }

    @Test
    void createCredential_duplicateKey_convertsToConflict() {
        when(credentialMapper.insert(any(UserCredential.class))).thenThrow(new DuplicateKeyException("dup"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createCredential(1L, UserCredential.TYPE_EMAIL, "a@b.com", null, false));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void createCredential_verifiedTrue_setsVerifiedAt() {
        ArgumentCaptor<UserCredential> captor = ArgumentCaptor.forClass(UserCredential.class);
        service.createCredential(1L, UserCredential.TYPE_PASSWORD, "user1", "$2b$hash", true);

        verify(credentialMapper).insert(captor.capture());
        UserCredential saved = captor.getValue();
        assertTrue(saved.getVerified());
        assertNotNull(saved.getVerifiedAt());
        assertEquals("$2b$hash", saved.getSecret());
    }

    @Test
    void createCredential_verifiedFalse_verifiedAtNull() {
        ArgumentCaptor<UserCredential> captor = ArgumentCaptor.forClass(UserCredential.class);
        service.createCredential(1L, UserCredential.TYPE_EMAIL, "a@b.com", null, false);

        verify(credentialMapper).insert(captor.capture());
        assertFalse(captor.getValue().getVerified());
        assertNull(captor.getValue().getVerifiedAt());
    }

    @Test
    void markVerified_alreadyTrue_noOp() {
        UserCredential c = new UserCredential();
        c.setId(1L);
        c.setVerified(true);
        when(credentialMapper.selectById(1L)).thenReturn(c);

        service.markVerified(1L);
        verify(credentialMapper, never()).updateById(any());
    }

    @Test
    void markVerified_null_noOp() {
        when(credentialMapper.selectById(99L)).thenReturn(null);
        service.markVerified(99L);
        verify(credentialMapper, never()).updateById(any());
    }

    @Test
    void markVerifiedByIdentifier_notFound_returnsFalse() {
        when(credentialMapper.findByTypeAndIdentifier(UserCredential.TYPE_EMAIL, "x@y.com")).thenReturn(null);
        assertFalse(service.markVerifiedByIdentifier(UserCredential.TYPE_EMAIL, "x@y.com"));
    }

    @Test
    void listByUserId_masksPhoneAndEmail() {
        UserCredential phone = new UserCredential();
        phone.setCredentialType(UserCredential.TYPE_PHONE);
        phone.setIdentifier("13800138000");
        phone.setVerified(true);

        UserCredential email = new UserCredential();
        email.setCredentialType(UserCredential.TYPE_EMAIL);
        email.setIdentifier("alice@example.com");
        email.setVerified(false);

        when(credentialMapper.findByUserId(1L)).thenReturn(List.of(phone, email));

        List<CredentialVO> list = service.listByUserId(1L);
        assertEquals(2, list.size());
        // 手机号脱敏：前3后4
        assertEquals("138****8000", list.get(0).getIdentifier());
        // 邮箱脱敏：首字符 + *** + @域名
        assertEquals("a***@example.com", list.get(1).getIdentifier());
    }

    @Test
    void existsByUserIdAndType_delegatesToMapper() {
        when(credentialMapper.countByUserIdAndType(1L, UserCredential.TYPE_EMAIL)).thenReturn(1L);
        assertTrue(service.existsByUserIdAndType(1L, UserCredential.TYPE_EMAIL));
        when(credentialMapper.countByUserIdAndType(1L, UserCredential.TYPE_WECHAT)).thenReturn(0L);
        assertFalse(service.existsByUserIdAndType(1L, UserCredential.TYPE_WECHAT));
    }
}
