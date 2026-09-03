package com.superprogrammer.knowledge.query;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QueryPlanner {
    /** 版本号边界用显式环视而非 \b：JDK 的 \b 按 Unicode 判词——「V2.1第十条」的 1 与 第 均为词字符，
     *  无边界致版本锚点漏提（WP4 Step3 混合跟进依赖锚点 filter）；环视口径=不以字母/数字/点衔接。 */
    private static final Pattern VERSION = Pattern.compile("(?i)(?<![0-9A-Za-z.])V?\\d+(?:\\.\\d+)+(?![0-9.])");
    private static final Pattern DATE = Pattern.compile("\\b\\d{4}[-年/]\\d{1,2}[-月/]\\d{1,2}日?\\b");
    private static final Pattern ARTICLE = Pattern.compile("第[一二三四五六七八九十百千万0-9]+条");
    /** C7 GLOBAL（WP4 Step2）：库级范围信号——须与聚合/概览意图词同时出现，
     *  防止「总结一下报销流程」这类局部问题误入全局 map-reduce 分支（又慢又泛）。 */
    private static final Pattern GLOBAL_SCOPE = Pattern.compile(
            ".*(全库|整个库|整个知识库|所有文档|全部文档|库内.{0,6}文档|这个库|该库|本库).*");
    private static final Pattern GLOBAL_INTENT = Pattern.compile(
            ".*(总结|综述|概览|趋势|整体|主题|话题|讲什么|关于什么|有哪些类|多少类|涵盖).*");

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
        // GLOBAL 置于 EXACT 之前：范围词+意图词双命中即库级聚合主意图——混合问题
        // （「总结全库，并给出V2.1第十条原文」）以 GLOBAL 为主分支，锚点 filters 保留在
        // plan 里作为细节跟进轮的「必达子意图」（WP4 Step3）；纯锚点问题（无意图词）仍走 EXACT。
        if (GLOBAL_SCOPE.matcher(q).matches() && GLOBAL_INTENT.matcher(q).matches()) {
            return new QueryPlan("GLOBAL", "OVERVIEW", filters,
                    List.of("SPARSE", "DENSE"), true, false, true);
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
