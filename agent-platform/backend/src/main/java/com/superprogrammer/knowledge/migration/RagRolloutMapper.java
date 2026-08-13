package com.superprogrammer.knowledge.migration;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RagRolloutMapper {
    @Insert("""
            INSERT INTO rag_rollout_states(kb_id,current_percentage,current_config_version,current_operator_id,
              current_snapshot_id,previous_percentage,previous_config_version,previous_operator_id,previous_snapshot_id,updated_at)
            VALUES(#{kbId},#{currentPercentage},#{currentConfigVersion},#{currentOperatorId},
              #{currentSnapshotId},#{previousPercentage},#{previousConfigVersion},#{previousOperatorId},#{previousSnapshotId},NOW())
            ON CONFLICT (kb_id) DO UPDATE SET current_percentage=EXCLUDED.current_percentage,
              current_config_version=EXCLUDED.current_config_version,current_operator_id=EXCLUDED.current_operator_id,
              previous_percentage=EXCLUDED.previous_percentage,previous_config_version=EXCLUDED.previous_config_version,
              previous_operator_id=EXCLUDED.previous_operator_id,current_snapshot_id=EXCLUDED.current_snapshot_id,
              previous_snapshot_id=EXCLUDED.previous_snapshot_id,updated_at=NOW()
            """)
    void upsert(RolloutRow row);

    @Select("SELECT * FROM rag_rollout_states WHERE kb_id=#{kbId}")
    RolloutRow find(@Param("kbId") long kbId);

    class RolloutRow {
        public Long kbId; public Integer currentPercentage; public String currentConfigVersion;
        public Long currentOperatorId; public String currentSnapshotId; public Integer previousPercentage;
        public String previousConfigVersion; public Long previousOperatorId; public String previousSnapshotId;
        public static RolloutRow of(RagRolloutService.RolloutState current, RagRolloutService.RolloutState previous) {
            RolloutRow row=new RolloutRow(); row.kbId=current.knowledgeBaseId(); row.currentPercentage=current.percentage();
            row.currentConfigVersion=current.configVersion(); row.currentOperatorId=current.operatorId();
            row.currentSnapshotId=current.snapshotId();
            if(previous!=null){row.previousPercentage=previous.percentage();row.previousConfigVersion=previous.configVersion();row.previousOperatorId=previous.operatorId();row.previousSnapshotId=previous.snapshotId();}
            return row;
        }
    }
}
