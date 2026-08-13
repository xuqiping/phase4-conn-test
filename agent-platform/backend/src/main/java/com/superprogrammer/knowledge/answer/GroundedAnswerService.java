package com.superprogrammer.knowledge.answer;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Builds citation-bound facts from bounded evidence batches. */
@Service
public class GroundedAnswerService {
    public Result synthesize(List<Evidence> evidence,
                             int batchSize,
                             Function<List<Evidence>, List<Fact>> extractor) {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        Set<Integer> allowed = evidence.stream().map(Evidence::citationId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<List<Fact>> batches = new ArrayList<>();
        for (int from = 0; from < evidence.size(); from += batchSize) {
            List<Evidence> batch = evidence.subList(from, Math.min(evidence.size(), from + batchSize));
            List<Fact> extracted = extractor.apply(List.copyOf(batch));
            if (extracted == null) continue;
            batches.add(extracted.stream().map(fact -> new Fact(fact.subject(), fact.value(),
                            fact.citationIds().stream().filter(allowed::contains).distinct().toList()))
                    .filter(fact -> !fact.citationIds().isEmpty())
                    .toList());
        }
        List<Fact> facts = mergeBatches(batches);
        return new Result(facts, hasConflict(facts));
    }

    public List<Fact> mergeBatches(List<List<Fact>> batches) {
        LinkedHashMap<String, Fact> out = new LinkedHashMap<>();
        for (List<Fact> batch : batches) {
            for (Fact fact : batch) {
                String key = fact.subject() + "\u0000" + fact.value();
                out.merge(key, fact, (a, b) -> new Fact(a.subject(), a.value(),
                        java.util.stream.Stream.concat(a.citationIds().stream(), b.citationIds().stream())
                                .distinct().toList()));
            }
        }
        return new ArrayList<>(out.values());
    }

    private boolean hasConflict(List<Fact> facts) {
        Map<String, Set<String>> values = new LinkedHashMap<>();
        facts.forEach(fact -> values.computeIfAbsent(fact.subject(), ignored -> new LinkedHashSet<>())
                .add(fact.value()));
        return values.values().stream().anyMatch(subjectValues -> subjectValues.size() > 1);
    }

    public String renderFacts(List<Fact> facts) {
        StringBuilder result = new StringBuilder();
        for (Fact fact : facts) {
            result.append("- ").append(fact.subject()).append("：").append(fact.value()).append(' ');
            fact.citationIds().forEach(id -> result.append('[').append(id).append(']'));
            result.append('\n');
        }
        return result.toString();
    }

    public record Evidence(int citationId, String content) {
    }

    public record Fact(String subject, String value, List<Integer> citationIds) {
        public Fact {
            citationIds = List.copyOf(citationIds);
        }
    }

    public record Result(List<Fact> facts, boolean conflict) {
        public Result {
            facts = List.copyOf(facts);
        }
    }
}
