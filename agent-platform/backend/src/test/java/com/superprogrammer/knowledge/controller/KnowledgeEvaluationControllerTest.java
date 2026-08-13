package com.superprogrammer.knowledge.controller;

import com.superprogrammer.knowledge.evaluation.EvaluationService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class KnowledgeEvaluationControllerTest {
    @Test
    void createsDatasetAndImportsJsonlForCurrentOperator() {
        EvaluationService service = mock(EvaluationService.class);
        KnowledgeEvaluationController controller = new KnowledgeEvaluationController(service);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, List.of()));
        when(service.createDataset(1L, 9L, "回归集", "说明", 7L)).thenReturn(
                new EvaluationService.Dataset(3L, 1L, 9L, "回归集", "说明", 7L));
        when(service.importJsonl(1L, 3L, "{}"))
                .thenReturn(new EvaluationService.ImportResult(1, List.of()));

        assertEquals(3L, controller.createDataset(
                new KnowledgeEvaluationController.DatasetRequest(9L, "回归集", "说明"))
                .getBody().getData().id());
        assertEquals(1, controller.importJsonl(3L, "{}").getBody().getData().imported());
    }
}
