package com.superprogrammer.runtime.service;

import com.superprogrammer.runtime.dto.ExecutionEvent;
import com.superprogrammer.runtime.dto.ExecutionRequest;
import reactor.core.publisher.Flux;

public interface RuntimeGateway {

    Flux<ExecutionEvent> run(ExecutionRequest request);
}
