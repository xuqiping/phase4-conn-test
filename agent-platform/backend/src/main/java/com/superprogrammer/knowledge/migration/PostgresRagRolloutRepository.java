package com.superprogrammer.knowledge.migration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresRagRolloutRepository implements RagRolloutService.Repository {
    private final RagRolloutMapper mapper;
    public void save(RagRolloutService.RolloutState current, RagRolloutService.RolloutState previous) {
        mapper.upsert(RagRolloutMapper.RolloutRow.of(current, previous));
    }
    public RagRolloutService.RolloutHistory find(long kbId) {
        RagRolloutMapper.RolloutRow row=mapper.find(kbId); if(row==null)return null;
        var current=new RagRolloutService.RolloutState(row.kbId,row.currentPercentage,row.currentConfigVersion,row.currentOperatorId);
        var previous=row.previousPercentage==null?null:new RagRolloutService.RolloutState(row.kbId,row.previousPercentage,row.previousConfigVersion,row.previousOperatorId);
        return new RagRolloutService.RolloutHistory(current,previous);
    }
}
