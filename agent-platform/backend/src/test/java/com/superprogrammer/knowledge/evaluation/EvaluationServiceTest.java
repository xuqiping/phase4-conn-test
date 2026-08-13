package com.superprogrammer.knowledge.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationServiceTest {
    @Test
    void importsJsonlPerLineAndExportsTenantScopedCases() {
        InMemoryRepository repository = new InMemoryRepository();
        EvaluationService service = new EvaluationService(repository, new ObjectMapper());
        EvaluationService.Dataset dataset = service.createDataset(1L, 9L, "回归集", "", 7L);

        EvaluationService.ImportResult result = service.importJsonl(1L, dataset.id(), """
                {"queryType":"EXACT","question":"第十条是什么","expectedChunkIds":["11"],"answerable":true}
                not-json
                {"queryType":"NO_ANSWER","question":"不存在的问题","expectedChunkIds":[],"answerable":false}
                """);

        assertEquals(2, result.imported());
        assertEquals(1, result.errors().size());
        String exported = service.exportJsonl(1L, dataset.id());
        assertTrue(exported.contains("第十条是什么"));
        assertThrows(IllegalArgumentException.class, () -> service.exportJsonl(2L, dataset.id()));
    }

    private static final class InMemoryRepository implements EvaluationService.Repository {
        private long nextDataset = 1;
        private long nextCase = 1;
        private final List<EvaluationService.Dataset> datasets = new ArrayList<>();
        private final List<EvaluationService.EvalCase> cases = new ArrayList<>();

        public EvaluationService.Dataset insertDataset(EvaluationService.Dataset dataset) {
            EvaluationService.Dataset saved = new EvaluationService.Dataset(nextDataset++, dataset.tenantId(),
                    dataset.kbId(), dataset.name(), dataset.description(), dataset.createdBy());
            datasets.add(saved); return saved;
        }
        public EvaluationService.Dataset findDataset(long tenantId, long datasetId) {
            return datasets.stream().filter(d -> d.tenantId() == tenantId && d.id() == datasetId)
                    .findFirst().orElse(null);
        }
        public EvaluationService.EvalCase insertCase(EvaluationService.EvalCase value) {
            EvaluationService.EvalCase saved = value.withId(nextCase++); cases.add(saved); return saved;
        }
        public List<EvaluationService.EvalCase> listCases(long tenantId, long datasetId) {
            if (findDataset(tenantId, datasetId) == null) return List.of();
            return cases.stream().filter(c -> c.datasetId() == datasetId).toList();
        }
    }
}
