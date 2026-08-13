package com.superprogrammer.knowledge.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EvaluationService {
    private final Repository repository;
    private final ObjectMapper objectMapper;
    private final RagMetricsCalculator metrics = new RagMetricsCalculator();

    public RagMetricsCalculator calculator() { return metrics; }

    public Dataset createDataset(long tenantId, long kbId, String name, String description, long createdBy) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("dataset name required");
        return repository.insertDataset(new Dataset(0, tenantId, kbId, name.trim(), description, createdBy));
    }

    public ImportResult importJsonl(long tenantId, long datasetId, String jsonl) {
        requireDataset(tenantId, datasetId);
        int imported = 0;
        List<ImportError> errors = new ArrayList<>();
        String[] lines = jsonl == null ? new String[0] : jsonl.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            try {
                Map<String, Object> row = objectMapper.readValue(lines[i], new TypeReference<>() {});
                String queryType = text(row.get("queryType"));
                String question = text(row.get("question"));
                if (queryType == null || question == null) throw new IllegalArgumentException("queryType/question required");
                repository.insertCase(new EvalCase(0, datasetId, queryType, question,
                        strings(row.get("expectedChunkIds")), strings(row.get("forbiddenChunkIds")),
                        bool(row.get("answerable"), true), row));
                imported++;
            } catch (Exception e) {
                errors.add(new ImportError(i + 1, safeMessage(e)));
            }
        }
        return new ImportResult(imported, errors);
    }

    public String exportJsonl(long tenantId, long datasetId) {
        requireDataset(tenantId, datasetId);
        StringBuilder out = new StringBuilder();
        for (EvalCase value : repository.listCases(tenantId, datasetId)) {
            try {
                out.append(objectMapper.writeValueAsString(Map.of(
                        "queryType", value.queryType(), "question", value.question(),
                        "expectedChunkIds", value.expectedChunkIds(),
                        "forbiddenChunkIds", value.forbiddenChunkIds(),
                        "answerable", value.answerable()))).append('\n');
            } catch (Exception e) {
                throw new IllegalStateException("evaluation case export failed", e);
            }
        }
        return out.toString();
    }

    public List<EvalCase> listCases(long tenantId, long datasetId) {
        requireDataset(tenantId, datasetId);
        return repository.listCases(tenantId, datasetId);
    }

    private Dataset requireDataset(long tenantId, long datasetId) {
        Dataset dataset = repository.findDataset(tenantId, datasetId);
        if (dataset == null) throw new IllegalArgumentException("evaluation dataset not found");
        return dataset;
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }
    private boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message.substring(0, Math.min(200, message.length()));
    }

    public interface Repository {
        Dataset insertDataset(Dataset dataset);
        Dataset findDataset(long tenantId, long datasetId);
        EvalCase insertCase(EvalCase value);
        List<EvalCase> listCases(long tenantId, long datasetId);
    }

    public record Dataset(long id, long tenantId, long kbId, String name, String description, long createdBy) {}
    public record EvalCase(long id, long datasetId, String queryType, String question,
                           List<String> expectedChunkIds, List<String> forbiddenChunkIds,
                           boolean answerable, Map<String, Object> metadata) {
        public EvalCase withId(long id) {
            return new EvalCase(id, datasetId, queryType, question, expectedChunkIds,
                    forbiddenChunkIds, answerable, metadata);
        }
    }
    public record ImportError(int line, String message) {}
    public record ImportResult(int imported, List<ImportError> errors) {
        public ImportResult { errors = List.copyOf(errors); }
    }
}
