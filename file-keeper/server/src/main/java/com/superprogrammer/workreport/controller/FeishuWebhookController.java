package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.JsonUtils;
import com.superprogrammer.common.R;
import com.superprogrammer.workreport.service.InboundMessageService;
import com.superprogrammer.workreport.service.webhook.FeishuWebhookAdapter;
import com.superprogrammer.workreport.service.webhook.WebhookParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/client/work-report/webhook/feishu")
@RequiredArgsConstructor
public class FeishuWebhookController {

    private final FeishuWebhookAdapter feishuWebhookAdapter;
    private final InboundMessageService inboundMessageService;

    @PostMapping
    public ResponseEntity<?> receive(
            @RequestBody String body,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce) {

        log.info("[FeishuWebhook] 收到回调 body={}", body);
        Map<String, Object> payload = JsonUtils.parseMap(body);

        if (feishuWebhookAdapter.isChallenge(payload)) {
            String challenge = feishuWebhookAdapter.extractChallenge(payload);
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }

        // MVP 阶段可选：配置 feishu.webhook-secret 后启用验签
        // String secret = ...;
        // if (!feishuWebhookAdapter.verifySignature(body, signature, timestamp, nonce, secret)) {
        //     return ResponseEntity.status(401).body(R.fail(401, "签名验证失败"));
        // }

        WebhookParseResult parseResult = feishuWebhookAdapter.parseMessage(payload);
        if (parseResult == null) {
            log.warn("[FeishuWebhook] 无法解析消息 payload={}", payload);
            return ResponseEntity.ok(R.ok());
        }

        inboundMessageService.receive("FEISHU", parseResult);
        return ResponseEntity.ok(R.ok());
    }
}
