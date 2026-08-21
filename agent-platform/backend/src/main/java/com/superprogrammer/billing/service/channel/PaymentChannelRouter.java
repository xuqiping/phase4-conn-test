package com.superprogrammer.billing.service.channel;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 支付渠道路由：按 channel 码分发到渠道实现；另供「当前可用渠道列表」（前端充值对话框显隐）。
 */
@Component
public class PaymentChannelRouter {

    private final Map<String, PaymentChannelService> byChannel;

    public PaymentChannelRouter(List<PaymentChannelService> channels) {
        this.byChannel = channels.stream()
                .collect(Collectors.toMap(PaymentChannelService::channel, Function.identity()));
    }

    /** 路由（不要求可用——notify 链路对已下单渠道必须可达，即使后来被关闭）。 */
    public PaymentChannelService route(String channel) {
        PaymentChannelService svc = byChannel.get(channel);
        if (svc == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未知支付渠道: " + channel);
        }
        return svc;
    }

    /** 下单路由：渠道必须当前可用（未开通/未配置 → 400）。 */
    public PaymentChannelService routeForCreate(String channel) {
        PaymentChannelService svc = route(channel);
        if (!svc.available()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该支付渠道未开通: " + channel);
        }
        return svc;
    }

    /** 当前可用渠道码列表（前端充值对话框只渲染这些；空列表=前端隐藏充值按钮）。 */
    public List<String> availableChannels() {
        return byChannel.values().stream()
                .filter(PaymentChannelService::available)
                .map(PaymentChannelService::channel)
                .sorted()
                .toList();
    }
}
