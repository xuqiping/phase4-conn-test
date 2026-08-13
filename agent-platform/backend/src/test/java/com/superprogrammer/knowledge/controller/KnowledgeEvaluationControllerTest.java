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
        KnowledgeEvaluationController controller = new KnowledgeEvaluationController(service, mock(com.superprogrammer.knowledge.evaluation.EvaluationRunService.class));
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

    @Test
    void startsAndQueriesEvaluationRunForCurrentOperator() {
        EvaluationService service = mock(EvaluationService.class);
        com.superprogrammer.knowledge.evaluation.EvaluationRunService runs =
                mock(com.superprogrammer.knowledge.evaluation.EvaluationRunService.class);
        KnowledgeEvaluationController controller = new KnowledgeEvaluationController(service, runs);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, List.of()));
        var run = new com.superprogrammer.knowledge.evaluation.EvaluationRunService.Run(
                5L, 1L, 3L, "pipeline-v2", "QUEUED", 7L,
                java.time.OffsetDateTime.now(), null, java.util.Map.of(), null);
        when(runs.start(1L, 3L, "pipeline-v2", 7L)).thenReturn(run);
        when(runs.get(1L, 5L)).thenReturn(run);

        assertEquals(5L, controller.startRun(3L,
                new KnowledgeEvaluationController.RunRequest("pipeline-v2")).getBody().getData().id());
        assertEquals("QUEUED", controller.getRun(5L).getBody().getData().status());
    }
}
