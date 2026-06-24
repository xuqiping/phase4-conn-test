package com.superprogrammer.workreport.service.push;

import com.superprogrammer.workreport.entity.ReportPushTarget;

public interface PushService {

    boolean supports(Platform platform);

    PushResult push(PushPayload payload, ReportPushTarget target);
}
