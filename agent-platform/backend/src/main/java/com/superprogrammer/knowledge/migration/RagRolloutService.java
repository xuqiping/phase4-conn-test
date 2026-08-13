package com.superprogrammer.knowledge.migration;

import com.superprogrammer.knowledge.mapper.RagAnswerCacheMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.superprogrammer.knowledge.opensearch.KnowledgeIndexOperationsService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RagRolloutService {
    private static final Set<Integer> ALLOWED_PERCENTAGES = Set.of(5, 20, 50, 100);
    private final CacheInvalidator cacheInvalidator;
    private final Repository repository;
    private final IndexRoute indexRoute;
    private final Map<Long, Integer> invalidations = new ConcurrentHashMap<>();

    @Autowired
    public RagRolloutService(RagAnswerCacheMapper cacheMapper, PostgresRagRolloutRepository repository,
                             KnowledgeIndexOperationsService indexOperationsService) {
        this(cacheMapper::invalidateByKb, repository, new IndexRoute() {
            public String activeSnapshot(long kbId) { return indexOperationsService.status(kbId).activeSnapshotId(); }
            public void switchSnapshot(long kbId, String snapshotId) {
                try { indexOperationsService.switchSnapshot(kbId, snapshotId, true); }
                catch (java.io.IOException error) { throw new IllegalStateException("索引路由切换失败", error); }
            }
        });
    }

    public RagRolloutService(CacheInvalidator cacheInvalidator) {
        this(cacheInvalidator, new InMemoryRepository(), IndexRoute.NOOP);
    }
    public RagRolloutService(CacheInvalidator cacheInvalidator, Repository repository) {
        this(cacheInvalidator, repository, IndexRoute.NOOP);
    }
    public RagRolloutService(CacheInvalidator cacheInvalidator, Repository repository, IndexRoute indexRoute) {
        this.cacheInvalidator = cacheInvalidator;
        this.repository = repository;
        this.indexRoute = indexRoute;
    }

    public RolloutState configure(long kbId, int percentage, String configVersion, long operatorId,
                                  boolean confirmed, Readiness readiness) {
        requireConfirmed(confirmed);
        if (!ALLOWED_PERCENTAGES.contains(percentage)) {
            throw new IllegalArgumentException("灰度比例只允许 5、20、50、100");
        }
        if (configVersion == null || !configVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("配置版本不合法");
        }
        if (readiness == null || !readiness.releaseGatePassed() || !readiness.indexHealthy()
                || !readiness.reconciliationPassed()) {
            throw new IllegalStateException("发布门禁、索引健康和对账必须全部通过");
        }
        RolloutState next = new RolloutState(kbId, percentage, configVersion, operatorId,
                indexRoute.activeSnapshot(kbId));
        RolloutHistory current=repository.find(kbId);
        repository.save(next,current==null?null:current.current());
        return next;
    }

    public RolloutState rollback(long kbId, long operatorId, boolean confirmed) {
        requireConfirmed(confirmed);
        RolloutHistory history = repository.find(kbId);
        if (history == null || history.previous() == null) {
            throw new IllegalStateException("没有可回滚的灰度配置");
        }
        RolloutState previous = history.previous();
        if (previous.snapshotId() != null && !previous.snapshotId().equals(indexRoute.activeSnapshot(kbId))) {
            indexRoute.switchSnapshot(kbId, previous.snapshotId());
        }
        RolloutState restored = new RolloutState(kbId, previous.percentage(), previous.configVersion(), operatorId,
                previous.snapshotId());
        repository.save(restored, history.current());
        cacheInvalidator.invalidate(kbId);
        invalidations.merge(kbId, 1, Integer::sum);
        return restored;
    }

    public RolloutState status(long kbId) {
        RolloutHistory history = repository.find(kbId);
        return history == null ? new RolloutState(kbId, 0, "champion", 0, indexRoute.activeSnapshot(kbId)) : history.current();
    }

    public boolean useChallenger(long kbId, long userId) {
        return status(kbId).challengerSelected(userId);
    }

    public int cacheInvalidations(long kbId) {
        return invalidations.getOrDefault(kbId, 0);
    }

    private static void requireConfirmed(boolean confirmed) {
        if (!confirmed) throw new IllegalArgumentException("需要二次确认");
    }

    private static int bucket(long kbId, long userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((kbId + ":" + userId).getBytes(StandardCharsets.UTF_8));
            return Math.floorMod(((digest[0] & 0xff) << 8) | (digest[1] & 0xff), 100);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @FunctionalInterface
    public interface CacheInvalidator { int invalidate(long kbId); }

    public record Readiness(boolean releaseGatePassed, boolean indexHealthy, boolean reconciliationPassed) {}

    public record RolloutState(long knowledgeBaseId, int percentage, String configVersion, long operatorId,
                               String snapshotId) {
        public RolloutState(long knowledgeBaseId, int percentage, String configVersion, long operatorId) {
            this(knowledgeBaseId, percentage, configVersion, operatorId, null);
        }
        public boolean challengerSelected(long userId) {
            return percentage > 0 && bucket(knowledgeBaseId, userId) < percentage;
        }
    }

    public record RolloutHistory(RolloutState current, RolloutState previous) {}
    public interface Repository { void save(RolloutState current, RolloutState previous); RolloutHistory find(long kbId); }
    public interface IndexRoute {
        IndexRoute NOOP = new IndexRoute() {
            public String activeSnapshot(long kbId) { return null; }
            public void switchSnapshot(long kbId, String snapshotId) { }
        };
        String activeSnapshot(long kbId);
        void switchSnapshot(long kbId, String snapshotId);
    }
    static final class InMemoryRepository implements Repository {
        private final Map<Long,RolloutHistory> states=new ConcurrentHashMap<>();
        public void save(RolloutState current,RolloutState previous){states.put(current.knowledgeBaseId(),new RolloutHistory(current,previous));}
        public RolloutHistory find(long kbId){return states.get(kbId);}
    }
}
