package com.superprogrammer.knowledge.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class PostgresEvaluationRepository implements EvaluationService.Repository, EvaluationRunService.Repository {
    private final EvaluationMapper mapper;
    private final ObjectMapper objectMapper;

    public EvaluationService.Dataset insertDataset(EvaluationService.Dataset dataset) {
        EvaluationMapper.DatasetRow row = new EvaluationMapper.DatasetRow();
        row.tenantId=dataset.tenantId(); row.kbId=dataset.kbId(); row.name=dataset.name();
        row.description=dataset.description(); row.createdBy=dataset.createdBy(); mapper.insertDataset(row);
        return new EvaluationService.Dataset(row.id, row.tenantId, row.kbId, row.name, row.description, row.createdBy);
    }
    public EvaluationService.Dataset findDataset(long tenantId, long datasetId) {
        EvaluationMapper.DatasetRow row=mapper.findDataset(tenantId,datasetId); if(row==null)return null;
        return new EvaluationService.Dataset(row.id,row.tenantId,row.kbId,row.name,row.description,row.createdBy);
    }
    public EvaluationService.EvalCase insertCase(EvaluationService.EvalCase value) {
        EvaluationMapper.CaseRow row=toRow(value); mapper.insertCase(row); return value.withId(row.id);
    }
    public List<EvaluationService.EvalCase> listCases(long tenantId,long datasetId) {
        return mapper.listCases(tenantId,datasetId).stream().map(this::fromRow).toList();
    }
    public EvaluationRunService.Run insertRun(EvaluationRunService.Run value) {
        EvaluationMapper.RunRow row=runRow(value); mapper.insertRun(row);
        return value.withId(row.id);
    }
    public void updateRun(EvaluationRunService.Run value) { mapper.updateRun(runRow(value)); }
    public void insertResult(EvaluationRunService.Result value) {
        EvaluationMapper.ResultRow row=new EvaluationMapper.ResultRow(); row.id=value.id(); row.runId=value.runId();
        row.caseId=value.caseId(); row.traceId=value.traceId(); row.metrics=json(value.metrics()); row.verdict=value.verdict();
        mapper.insertResult(row);
    }
    private EvaluationMapper.RunRow runRow(EvaluationRunService.Run value) {
        EvaluationMapper.RunRow row=new EvaluationMapper.RunRow(); row.id=value.id(); row.datasetId=value.datasetId();
        row.pipelineVersion=value.pipelineVersion(); row.status=value.status(); row.startedBy=value.startedBy();
        row.startedAt=value.startedAt(); row.finishedAt=value.finishedAt(); row.summaryMetrics=json(value.summaryMetrics());
        row.errorSummary=value.errorSummary(); return row;
    }
    private EvaluationMapper.CaseRow toRow(EvaluationService.EvalCase value){
        EvaluationMapper.CaseRow row=new EvaluationMapper.CaseRow(); row.datasetId=value.datasetId();
        row.queryType=value.queryType(); row.question=value.question(); row.answerable=value.answerable();
        row.expectedChunkIds=json(value.expectedChunkIds()); row.forbiddenChunkIds=json(value.forbiddenChunkIds());
        row.metadata=json(value.metadata()); return row;
    }
    private EvaluationService.EvalCase fromRow(EvaluationMapper.CaseRow row){
        return new EvaluationService.EvalCase(row.id,row.datasetId,row.queryType,row.question,
                strings(row.expectedChunkIds),strings(row.forbiddenChunkIds),Boolean.TRUE.equals(row.answerable),
                map(row.metadata));
    }
    private String json(Object value){try{return objectMapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("invalid evaluation json",e);}}
    private List<String> strings(String value){try{return objectMapper.readValue(value,new TypeReference<>(){});}catch(Exception e){return List.of();}}
    private Map<String,Object> map(String value){try{return objectMapper.readValue(value,new TypeReference<>(){});}catch(Exception e){return Map.of();}}
}
