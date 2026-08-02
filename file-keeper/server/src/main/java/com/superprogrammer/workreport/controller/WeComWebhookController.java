package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.workreport.service.InboundMessageService;
import com.superprogrammer.workreport.service.webhook.WebhookParseResult;
import com.superprogrammer.workreport.service.webhook.WeComWebhookAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 企业微信自建应用回调入口。
 *
 * <p>注意：企业微信普通群机器人（webhook）只能发消息，不能接收回调；
 * 接收用户消息必须使用自建应用的「接收消息」回调模式。
 */
@Slf4j
@RestController
@RequestMapping("/api/client/work-report/webhook/wecom")
@RequiredArgsConstructor
public class WeComWebhookController {

    private final WeComWebhookAdapter weComWebhookAdapter;
    private final InboundMessageService inboundMessageService;

    /**
     * 企业微信配置回调 URL 时的 GET 验证。
     */
    @GetMapping
    public ResponseEntity<String> verifyUrl(
            @RequestParam("msg_signature") String signature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestParam("echostr") String echostr) {

        log.info("[WeComWebhook] 收到 URL 验证请求");
        // MVP 阶段：直接返回 echostr；生产环境应使用 encodingAESKey 解密后返回
        // String decrypted = weComWebhookAdapter.decrypt(echostr, encodingAesKey);
        // return ResponseEntity.ok(decrypted);
        return ResponseEntity.ok(echostr);
    }

    /**
     * 接收企业微信自建应用推送的消息。
     */
    @PostMapping
    public ResponseEntity<?> receive(
            @RequestParam("msg_signature") String signature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestBody String body) {

        log.info("[WeComWebhook] 收到消息回调 body={}", body);

        // MVP 阶段：从 XML 中直接提取 Content（未启用加密时企业微信可配置明文模式）
        // 生产环境应先用 msg_signature 验签，再解密 Encrypt 字段
        Map<String, Object> payload = parseXml(body);

        if (payload.containsKey("Encrypt")) {
            log.info("[WeComWebhook] 收到加密消息，请在生产环境配置 encodingAESKey 后解密");
            return ResponseEntity.ok(R.ok());
        }

        WebhookParseResult parseResult = weComWebhookAdapter.parseMessage(payload);
        if (parseResult == null) {
            log.warn("[WeComWebhook] 无法解析消息 payload={}", payload);
            return ResponseEntity.ok(R.ok());
        }

        inboundMessageService.receive("WECHAT_WORK", parseResult);
        return ResponseEntity.ok(R.ok());
    }

    private Map<String, Object> parseXml(String xml) {
        Map<String, Object> map = new HashMap<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    map.put(node.getNodeName(), node.getTextContent());
                }
            }
        } catch (Exception e) {
            log.error("[WeComWebhook] XML 解析失败", e);
        }
        return map;
    }
}
