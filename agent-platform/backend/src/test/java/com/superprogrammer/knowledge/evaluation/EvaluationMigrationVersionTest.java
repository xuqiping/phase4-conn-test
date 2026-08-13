package com.superprogrammer.knowledge.evaluation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationMigrationVersionTest {
    @Test
    void flywayVersionsAreUniqueAndEvaluationCenterUsesVersion115() throws Exception {
        Path migrationDir = Path.of("src/main/resources/db/migration");
        List<String> names;
        try (var files = Files.list(migrationDir)) {
            names = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V\\d+__.*\\.sql"))
                    .toList();
        }
        Map<String, Long> counts = names.stream().collect(Collectors.groupingBy(
                name -> name.substring(1, name.indexOf("__")), Collectors.counting()));
        assertTrue(counts.values().stream().allMatch(count -> count == 1), counts.toString());
        assertTrue(names.contains("V115__rag_evaluation_center.sql"), names.toString());
    }
}
