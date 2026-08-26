// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/WechatAuthServiceTest.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.auth.dto.TokenResponse;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import me.chanjar.weixin.common.bean.WxOAuth2UserInfo;
import me.chanjar.weixin.common.bean.oauth2.WxOAuth2AccessToken;
import me.chanjar.weixin.common.service.WxOAuth2Service;
import me.chanjar.weixin.mp.api.WxMpService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WechatAuthService 单测（Chunk D）。
 * 覆盖：未开启/授权URL/state 校验/新号建号/老号登录/禁用拒。
 */
@ExtendWith(MockitoExtension.class)
class WechatAuthServiceTest {

    @Mock private UserMapper userMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private AuthService authService;
    @Mock private CredentialService credentialService;
    @Mock private WxMpService wxMpService;
    @Mock private WxOAuth2Service oAuth2Service;
    @Mock private AuditLogService auditLogService;

    private WechatAuthService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, User.class);
        TableInfoHelper.initTableInfo(assistant, com.superprogrammer.auth.entity.Role.class);
        TableInfoHelper.initTableInfo(assistant, UserCredential.class);
        TableInfoHelper.initTableInfo(assistant, com.superprogrammer.auth.entity.UserRole.class);
    }

    @BeforeEach
    void setUp() {
        service = new WechatAuthService(userMapper, roleMapper, userRoleMapper, passwordEncoder,
                redisTemplate, authService, credentialService, auditLogService);
        ReflectionTestUtils.setField(service, "wxMpService", wxMpService);
        ReflectionTestUtils.setField(service, "wechatEnabled", true);
        ReflectionTestUtils.setField(service, "redirectUri", "https://test.com/api/auth/login/wechat/callback");
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void isEnabled_whenConfigured_returnsTrue() {
        assertTrue(service.isEnabled());
    }

    @Test
    void isEnabled_whenDisabled_returnsFalse() {
        ReflectionTestUtils.setField(service, "wechatEnabled", false);
        assertFalse(service.isEnabled());
    }

    @Test
    void buildAuthorizeUrl_whenDisabled_throws() {
        ReflectionTestUtils.setField(service, "wechatEnabled", false);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.buildAuthorizeUrl());
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void handleCallback_stateInvalid_throws() {
        when(valueOps.get(startsWith("wechat:state:"))).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handleCallback("code", "bad-state"));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
        // B1（8x-1）：state 无效 → wechat_login FAIL
        verify(auditLogService).fromMdc(eq("auth"), eq("wechat_login"), eq("user"), isNull(),
                argThat((java.util.Map<String, Object> m) -> m.containsKey("reason")), eq("FAIL"));
    }

    @Test
    void handleCallback_validStateNewUser_createsAccount() throws Exception {
        when(valueOps.get("wechat:state:valid-state")).thenReturn("1");
        when(wxMpService.getOAuth2Service()).thenReturn(oAuth2Service);
        WxOAuth2AccessToken token = new WxOAuth2AccessToken();
        when(oAuth2Service.getAccessToken("code")).thenReturn(token);
        WxOAuth2UserInfo wxUser = mock(WxOAuth2UserInfo.class);
        when(wxUser.getUnionId()).thenReturn("union-123");
        when(wxUser.getOpenid()).thenReturn("openid-abc");
        when(wxUser.getNickname()).thenReturn("测试用户");
        when(wxUser.getHeadImgUrl()).thenReturn("https://avatar.png");
        when(oAuth2Service.getUserInfo(token, "zh_CN")).thenReturn(wxUser);
        when(credentialService.findForLogin(UserCredential.TYPE_WECHAT, "union-123")).thenReturn(null);
        doAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(1L);
            return 1;
        }).when(userMapper).insert(any(User.class));
        when(authService.issueTokensForSms(any(User.class))).thenReturn(TokenResponse.builder().accessToken("jwt").build());

        TokenResponse response = service.handleCallback("code", "valid-state");

        assertNotNull(response);
        verify(credentialService).createCredential(eq(1L), eq(UserCredential.TYPE_WECHAT), eq("union-123"), isNull(), eq(true));
        verify(redisTemplate).delete("wechat:state:valid-state");
        // B1（8x-1）：扫码登录成功 → wechat_login SUCCESS + openid 全量进 detail
        verify(auditLogService).fromMdc(eq("auth"), eq("wechat_login"), eq("user"), eq("1"),
                argThat((java.util.Map<String, Object> m) -> "openid-abc".equals(m.get("openid"))), eq("SUCCESS"));
    }

    @Test
    void handleCallback_existingUser_logsIn() throws Exception {
        when(valueOps.get("wechat:state:valid-state")).thenReturn("1");
        when(wxMpService.getOAuth2Service()).thenReturn(oAuth2Service);
        WxOAuth2AccessToken token = new WxOAuth2AccessToken();
        when(oAuth2Service.getAccessToken("code")).thenReturn(token);
        WxOAuth2UserInfo wxUser = mock(WxOAuth2UserInfo.class);
        when(wxUser.getUnionId()).thenReturn("union-123");
        when(wxUser.getOpenid()).thenReturn("openid-abc");
        when(oAuth2Service.getUserInfo(token, "zh_CN")).thenReturn(wxUser);
        UserCredential credential = new UserCredential();
        credential.setUserId(1L);
        when(credentialService.findForLogin(UserCredential.TYPE_WECHAT, "union-123")).thenReturn(credential);
        User existing = new User();
        existing.setId(1L);
        existing.setStatus("ACTIVE");
        when(userMapper.selectById(1L)).thenReturn(existing);
        when(authService.issueTokensForSms(any(User.class))).thenReturn(TokenResponse.builder().accessToken("jwt").build());

        TokenResponse response = service.handleCallback("code", "valid-state");
        assertNotNull(response);
        verify(credentialService, never()).createCredential(anyLong(), anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void handleCallback_disabledUser_throws() throws Exception {
        when(valueOps.get("wechat:state:valid-state")).thenReturn("1");
        when(wxMpService.getOAuth2Service()).thenReturn(oAuth2Service);
        WxOAuth2AccessToken token = new WxOAuth2AccessToken();
        when(oAuth2Service.getAccessToken("code")).thenReturn(token);
        WxOAuth2UserInfo wxUser = mock(WxOAuth2UserInfo.class);
        when(wxUser.getUnionId()).thenReturn("union-123");
        when(oAuth2Service.getUserInfo(token, "zh_CN")).thenReturn(wxUser);
        UserCredential credential = new UserCredential();
        credential.setUserId(1L);
        when(credentialService.findForLogin(UserCredential.TYPE_WECHAT, "union-123")).thenReturn(credential);
        User disabled = new User();
        disabled.setId(1L);
        disabled.setStatus("DISABLED");
        when(userMapper.selectById(1L)).thenReturn(disabled);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handleCallback("code", "valid-state"));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }
}
