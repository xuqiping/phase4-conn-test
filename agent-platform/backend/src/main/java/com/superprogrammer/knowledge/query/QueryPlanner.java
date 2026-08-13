package com.superprogrammer.knowledge.query;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QueryPlanner {
    private static final Pattern VERSION = Pattern.compile("(?i)\\bV?\\d+(?:\\.\\d+)+\\b");
    private static final Pattern DATE = Pattern.compile("\\b\\d{4}[-年/]\\d{1,2}[-月/]\\d{1,2}日?\\b");
    private static final Pattern ARTICLE = Pattern.compile("第[一二三四五六七八九十百千万0-9]+条");

    public QueryPlan plan(String query) {
        String q = query == null ? "" : query.trim();
        Map<String, String> filters = new LinkedHashMap<>();
        Matcher version = VERSION.matcher(q);
        if (version.find()) filters.put("version", normalizeVersion(version.group()));
        Matcher date = DATE.matcher(q);
        if (date.find()) filters.put("date", date.group());
        Matcher article = ARTICLE.matcher(q);
        if (article.find()) filters.put("article", article.group());

        if (q.matches(".*(比较|对比|差异|区别|优劣).*")) {
            return new QueryPlan("COMPARISON", "MULTI_EVIDENCE", filters,
                    List.of("EXACT", "SPARSE", "DENSE"), true, false, false);
        }
        if (!filters.isEmpty() || q.matches(".*[“\"].+[”\"].*")) {
            return new QueryPlan("EXACT", "DIRECT", filters,
                    List.of("EXACT", "SPARSE"), false, false, false);
        }
        if (q.matches(".*(步骤|流程|如何|怎么办|先.*再).*")) {
            return new QueryPlan("PROCEDURE", "ORDERED_STEPS", filters,
                    List.of("SPARSE", "DENSE", "NEIGHBOR"), true, true, false);
        }
        if (q.matches(".*(列出|全部|有哪些|清单).*")) {
            return new QueryPlan("LIST", "LIST", filters,
                    List.of("SPARSE", "DENSE"), true, false, false);
        }
        return new QueryPlan("SEMANTIC", "DIRECT", filters,
                List.of("DENSE", "SPARSE"), false, false, true);
    }

    private static String normalizeVersion(String value) {
        return value.regionMatches(true, 0, "V", 0, 1) ? "V" + value.substring(1) : "V" + value;
    }
}
