package com.superprogrammer.workreport.service.push;

import com.superprogrammer.workreport.entity.PushTarget;

public interface PushService {

    boolean supports(Platform platform);

    PushResult push(PushPayload payload, PushTarget target, String decryptedCredential);
}
