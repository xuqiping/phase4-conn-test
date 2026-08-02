package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.JsonUtils;
import com.superprogrammer.common.R;
import com.superprogrammer.workreport.service.InboundMessageService;
import com.superprogrammer.workreport.service.webhook.DingTalkWebhookAdapter;
import com.superprogrammer.workreport.service.webhook.WebhookParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/client/work-report/webhook/dingtalk")
@RequiredArgsConstructor
public class DingTalkWebhookController {

    private final DingTalkWebhookAdapter dingTalkWebhookAdapter;
    private final InboundMessageService inboundMessageService;

    @PostMapping
    public ResponseEntity<?> receive(
            @RequestBody String body,
            @RequestParam(value = "timestamp", required = false) String timestamp,
            @RequestParam(value = "sign", required = false) String sign) {
        log.info("[DingTalkWebhook] 收到回调 body={}", body);
        Map<String, Object> payload = JsonUtils.parseMap(body);

        // MVP 阶段可选：配置钉钉 appSecret 后启用验签
        // String secret = ...;
        // if (!dingTalkWebhookAdapter.verifySignature(body, sign, timestamp, null, secret)) {
        //     return ResponseEntity.status(401).body(R.fail(401, "签名验证失败"));
        // }

        WebhookParseResult parseResult = dingTalkWebhookAdapter.parseMessage(payload);
        if (parseResult == null) {
            log.warn("[DingTalkWebhook] 无法解析消息 payload={}", payload);
            return ResponseEntity.ok(R.ok());
        }

        inboundMessageService.receive("DINGTALK", parseResult);
        return ResponseEntity.ok(R.ok());
    }
}
