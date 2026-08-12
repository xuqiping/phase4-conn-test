// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/CredentialServiceTest.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.auth.dto.CredentialVO;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.auth.mapper.UserCredentialMapper;
import com.superprogrammer.auth.mapper.UserMapper;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * CredentialService 单测（Chunk A 基础 + Chunk G 绑定/解绑/改密）。
 * 覆盖：登录查询 / 建凭证（并发冲突转 CONFLICT）/ 标记验证（幂等）/ 列表脱敏 /
 * 绑定邮箱（格式/已绑/被他人占）/ 解绑（PASSWORD 拒/最后一种拒/正常软删）/ 改密（旧密码错/成功踢会话）。
 */
@ExtendWith(MockitoExtension.class)
class CredentialServiceTest {

    @Mock
    private UserCredentialMapper credentialMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private SessionService sessionService;

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
        service = new CredentialService(credentialMapper, userMapper, passwordEncoder, sessionService);
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

    // ==================== Chunk G：绑定/解绑/改密 ====================

    @Test
    void bindEmail_invalidFormat_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.bindEmail(1L, "not-an-email"));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verifyNoInteractions(credentialMapper);
    }

    @Test
    void bindEmail_alreadyBoundByThisUser_throwsConflict() {
        when(credentialMapper.countByUserIdAndType(1L, UserCredential.TYPE_EMAIL)).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.bindEmail(1L, "a@b.com"));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void bindEmail_boundByOther_throwsAlreadyBound() {
        when(credentialMapper.countByUserIdAndType(1L, UserCredential.TYPE_EMAIL)).thenReturn(0L);
        UserCredential existing = new UserCredential();
        existing.setUserId(999L); // 他人的
        when(credentialMapper.findByTypeAndIdentifier(UserCredential.TYPE_EMAIL, "a@b.com")).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.bindEmail(1L, "a@b.com"));
        assertEquals(ErrorCode.CREDENTIAL_ALREADY_BOUND.getCode(), ex.getCode());
    }

    @Test
    void bindEmail_success_createsUnverifiedCredential() {
        when(credentialMapper.countByUserIdAndType(1L, UserCredential.TYPE_EMAIL)).thenReturn(0L);
        when(credentialMapper.findByTypeAndIdentifier(UserCredential.TYPE_EMAIL, "a@b.com")).thenReturn(null);

        service.bindEmail(1L, "A@B.com"); // 大写 → 归一化小写

        ArgumentCaptor<UserCredential> captor = ArgumentCaptor.forClass(UserCredential.class);
        verify(credentialMapper).insert(captor.capture());
        UserCredential saved = captor.getValue();
        assertEquals(UserCredential.TYPE_EMAIL, saved.getCredentialType());
        assertEquals("a@b.com", saved.getIdentifier()); // 归一化
        assertFalse(saved.getVerified()); // 绑定时未验证
        assertNull(saved.getSecret());
    }

    @Test
    void unbind_passwordType_rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.unbind(1L, UserCredential.TYPE_PASSWORD));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verifyNoInteractions(credentialMapper);
    }

    @Test
    void unbind_lastOne_rejected() {
        when(credentialMapper.findByUserId(1L)).thenReturn(List.of(new UserCredential()));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.unbind(1L, UserCredential.TYPE_EMAIL));
        assertEquals(ErrorCode.CREDENTIAL_LAST_ONE.getCode(), ex.getCode());
    }

    @Test
    void unbind_success_softDeletes() {
        UserCredential email = new UserCredential();
        email.setId(10L);
        email.setCredentialType(UserCredential.TYPE_EMAIL);
        UserCredential phone = new UserCredential();
        phone.setId(11L);
        phone.setCredentialType(UserCredential.TYPE_PHONE);
        when(credentialMapper.findByUserId(1L)).thenReturn(List.of(email, phone)); // 2 条 → 可解绑

        service.unbind(1L, UserCredential.TYPE_EMAIL);

        verify(credentialMapper).deleteById(10L); // 逻辑删（@TableLogic）
    }

    @Test
    void unbind_typeNotFound_throws() {
        UserCredential phone = new UserCredential();
        phone.setCredentialType(UserCredential.TYPE_PHONE);
        when(credentialMapper.findByUserId(1L)).thenReturn(List.of(phone, new UserCredential()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.unbind(1L, UserCredential.TYPE_EMAIL));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void changePassword_wrongOldPassword_throws() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword("$2b$oldhash");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("WrongOld", "$2b$oldhash")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changePassword(1L, "WrongOld", "NewPass123!"));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(userMapper, never()).updateById(any());
    }

    @Test
    void changePassword_success_updatesAndKicksSession() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword("$2b$oldhash");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("OldPass123!", "$2b$oldhash")).thenReturn(true);
        when(passwordEncoder.matches("NewPass123!", "$2b$oldhash")).thenReturn(false); // 新旧不同
        when(passwordEncoder.encode("NewPass123!")).thenReturn("$2b$newhash");

        // 该用户有 PASSWORD 凭证 → secret 应同步
        UserCredential pwdCred = new UserCredential();
        pwdCred.setId(5L);
        pwdCred.setCredentialType(UserCredential.TYPE_PASSWORD);
        when(credentialMapper.findByUserId(1L)).thenReturn(List.of(pwdCred));

        service.changePassword(1L, "OldPass123!", "NewPass123!");

        verify(userMapper).updateById(any(User.class));
        verify(credentialMapper).updateById(any(UserCredential.class)); // 凭证 secret 同步
        verify(sessionService).kickAllSessions(1L); // 踢所有会话
    }
}
