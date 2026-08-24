// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/CaptchaService.java
package com.superprogrammer.auth.service;

import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.model.vo.CaptchaVO;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 滑块验证码服务（封装 AJ-Captcha）。
 *
 * <p>职责：获取滑块图片 / 校验滑块轨迹。
 * <p>安全语义：captchaToken 单次有效（AJ-Captcha 二次校验后 Redis 删 key，防重放）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaService {

    private final com.anji.captcha.service.CaptchaService ajCaptchaService;

    /**
     * 获取滑块验证码（前端渲染用）。
     *
     * @return CaptchaVO 含 base64 图片 + token
     */
    /** 滑块拼图类型（AJ-Captcha get/check/verification 均强制要求 captchaType 非空，否则 repCode=0011）。 */
    private static final String CAPTCHA_TYPE_BLOCK_PUZZLE = "blockPuzzle";

    public CaptchaVO get() {
        CaptchaVO input = new CaptchaVO();
        input.setCaptchaType(CAPTCHA_TYPE_BLOCK_PUZZLE);
        ResponseModel response = ajCaptchaService.get(input);
        if (!response.isSuccess()) {
            log.error("获取滑块验证码失败 : {}", response.getRepMsg());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取验证码失败");
        }
        return (CaptchaVO) response.getRepData();
    }

    /**
     * 一次校验（坐标核验）：前端拖动结束提交 token + 加密轨迹，AJ-Captcha 校验 x±slipOffset / y 精确相等，
     * 通过后写二次缓存 key（RUNNING:CAPTCHA:second-*）——{@link #verify} 只认该 key，
     * 所以本步是滑块链路不可绕过的前置闸。
     *
     * @param token     验证码 token（get 时下发）
     * @param pointJson AES({x,y}, secretKey) 加密轨迹
     * @throws BusinessException 坐标不符 / token 过期
     */
    public void check(String token, String pointJson) {
        if (token == null || token.isBlank() || pointJson == null || pointJson.isBlank()) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID);
        }
        CaptchaVO vo = new CaptchaVO();
        vo.setCaptchaType(CAPTCHA_TYPE_BLOCK_PUZZLE);
        vo.setToken(token);
        vo.setPointJson(pointJson);
        ResponseModel response = ajCaptchaService.check(vo);
        if (!response.isSuccess()) {
            log.warn("滑块坐标校验失败 : {}", response.getRepMsg());
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID);
        }
    }

    /**
     * 校验滑块轨迹（前端提交滑块结果后调）。
     *
     * @param captchaVerification AJ-Captcha 返回的验证码 token（前端滑块后拿到）
     * @throws BusinessException 校验失败（统一话术，不泄露具体原因）
     */
    public void verify(String captchaVerification) {
        if (captchaVerification == null || captchaVerification.isBlank()) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID);
        }
        // AJ-Captcha verification 形参为 CaptchaVO（captchaVerification 字段承载 token），非裸 String
        CaptchaVO vo = new CaptchaVO();
        vo.setCaptchaType(CAPTCHA_TYPE_BLOCK_PUZZLE);
        vo.setCaptchaVerification(captchaVerification);
        ResponseModel response = ajCaptchaService.verification(vo);
        if (!response.isSuccess()) {
            log.warn("滑块验证码校验失败 : {}", response.getRepMsg());
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID);
        }
    }
}
