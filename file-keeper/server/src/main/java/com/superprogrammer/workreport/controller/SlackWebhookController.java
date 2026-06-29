package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.JsonUtils;
import com.superprogrammer.common.R;
import com.superprogrammer.workreport.service.InboundMessageService;
import com.superprogrammer.workreport.service.webhook.SlackWebhookAdapter;
import com.superprogrammer.workreport.service.webhook.WebhookParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/client/work-report/webhook/slack")
@RequiredArgsConstructor
public class SlackWebhookController {

    private final SlackWebhookAdapter slackWebhookAdapter;
    private final InboundMessageService inboundMessageService;

    @PostMapping
    public ResponseEntity<?> receive(
            @RequestBody String body,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signature,
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp) {
        log.info("[SlackWebhook] 收到回调 body={}", body);
        Map<String, Object> payload = JsonUtils.parseMap(body);

        if (slackWebhookAdapter.isChallenge(payload)) {
            String challenge = slackWebhookAdapter.extractChallenge(payload);
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }

        // MVP 阶段可选：配置 slack.signing-secret 后启用验签
        // String secret = ...;
        // if (!slackWebhookAdapter.verifySignature(body, signature, timestamp, null, secret)) {
        //     return ResponseEntity.status(401).body(R.fail(401, "签名验证失败"));
        // }

        WebhookParseResult parseResult = slackWebhookAdapter.parseMessage(payload);
        if (parseResult == null) {
            log.warn("[SlackWebhook] 无法解析消息 payload={}", payload);
            return ResponseEntity.ok(R.ok());
        }

        inboundMessageService.receive("SLACK", parseResult);
        return ResponseEntity.ok(R.ok());
    }
}
