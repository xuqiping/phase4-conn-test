package com.superprogrammer.user.controller;

import com.superprogrammer.common.R;
import com.superprogrammer.user.dto.CheckVerificationRequest;
import com.superprogrammer.user.dto.SendVerificationRequest;
import com.superprogrammer.user.dto.VerificationCheckResponse;
import com.superprogrammer.user.service.VerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/client/verification")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationService verificationService;

    @PostMapping("/send")
    public R<Void> send(@Valid @RequestBody SendVerificationRequest request) {
        verificationService.send(request.contactType(), request.contact());
        return R.ok();
    }

    @PostMapping("/check")
    public R<VerificationCheckResponse> check(@Valid @RequestBody CheckVerificationRequest request) {
        return R.ok(new VerificationCheckResponse(
                verificationService.check(request.contactType(), request.contact(), request.code())
        ));
    }
}
