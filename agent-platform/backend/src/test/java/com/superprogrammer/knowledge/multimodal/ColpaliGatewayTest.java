package com.superprogrammer.knowledge.multimodal;

import com.superprogrammer.knowledge.config.ColpaliProperties;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WP5 Step4：ColPali 实验通道接口预留——探活失败自动禁用（不重打）+ 全局开关关零出站。
 * sidecar 本版不部署，健康探测即常态失败路径；检索主链不接本网关（零影响由接线位置保证）。
 */
class ColpaliGatewayTest {

    @Test
    void probeFailure_autoDisablesAndCachesWithinCooldown() throws Exception {
        ColpaliProperties props = new ColpaliProperties();
        props.setEnabled(true);   // 全局开、sidecar 缺席 → 探活失败
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new ConnectException("refused"));
        ColpaliGateway gateway = new ColpaliGateway(props, client);

        assertFalse(gateway.healthy());
        assertFalse(gateway.healthy());   // 冷却期内用缓存——第二次零 HTTP
        verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        KnowledgeBase kb = new KnowledgeBase();
        kb.setColpaliEnabled(true);
        assertFalse(gateway.availableFor(kb));   // 探活失败自动禁用压过 KB 级开
    }

    @Test
    void globalSwitchOff_zeroOutboundCalls() {
        ColpaliProperties props = new ColpaliProperties();   // enabled=false 实验通道默认
        HttpClient client = mock(HttpClient.class);
        ColpaliGateway gateway = new ColpaliGateway(props, client);

        assertFalse(gateway.healthy());
        assertFalse(gateway.availableFor(null));
        KnowledgeBase kb = new KnowledgeBase();
        kb.setColpaliEnabled(true);
        assertFalse(gateway.availableFor(kb));   // 全局关压过 KB 级开
        verifyNoInteractions(client);            // 开关关 = 零出站调用（运维 kill switch 硬闸）
    }
}
