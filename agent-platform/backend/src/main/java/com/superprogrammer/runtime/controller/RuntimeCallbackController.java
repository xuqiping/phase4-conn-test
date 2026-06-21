package com.superprogrammer.runtime.controller;

import com.superprogrammer.common.result.R;
import com.superprogrammer.runtime.dto.RuntimeNodeCallbackRequest;
import com.superprogrammer.runtime.dto.RuntimeNodeCallbackResponse;
import com.superprogrammer.runtime.service.RuntimeNodeCallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/runtime/callbacks")
@RequiredArgsConstructor
public class RuntimeCallbackController {

    private final RuntimeNodeCallbackService runtimeNodeCallbackService;

    @PostMapping("/nodes/execute")
    public ResponseEntity<R<RuntimeNodeCallbackResponse>> executeNode(
            @RequestBody RuntimeNodeCallbackRequest request) {
        return ResponseEntity.ok(R.ok(runtimeNodeCallbackService.executeNode(request)));
    }
}
