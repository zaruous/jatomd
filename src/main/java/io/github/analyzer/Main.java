package io.github.analyzer;

import io.github.analyzer.core.HierarchyAnalyzer;
import io.github.analyzer.core.JarClassLoader;
import io.github.analyzer.model.ControllerReport;
import io.github.analyzer.model.Endpoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Hierarchy Analyzer
 *
 * 사용법:
 *   java -jar spring-hierarchy-analyzer.jar [target.jar]
 *
 * 출력:
 *   {jar명}/{패키지경로}/{Controller}.md
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("사용법: java -jar spring-hierarchy-analyzer.jar <target.jar>");
            System.exit(1);
        }

        String jarPath = args[0];
        System.out.println("📦 분석 대상: " + jarPath);

        try (JarClassLoader loader = new JarClassLoader(jarPath)) {
            System.out.println("   클래스 수: " + loader.classCount());

            HierarchyAnalyzer analyzer = new HierarchyAnalyzer(loader);

            List<String> controllers = loader.findControllers();
            System.out.println("   컨트롤러 수: " + controllers.size() + "개\n");

            if (controllers.isEmpty()) {
                System.out.println("⚠️  @Controller / @RestController 클래스를 찾을 수 없습니다.");
                return;
            }

            List<ControllerReport> reports = controllers.stream()
                .map(ctrl -> {
                    try { return analyzer.analyzeController(ctrl); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .toList();
            long endpointCount = reports.stream().mapToLong(r -> r.endpoints.size()).sum();
            System.out.println("   감지된 엔드포인트 수: " + endpointCount + "개\n");

            if (endpointCount == 0) {
                System.out.println("⚠️  Spring 매핑 메서드를 감지하지 못했습니다.");
                System.out.println("   @RequestMapping 계열 사용 방식 또는 바이트코드 메타데이터를 확인해 주세요.\n");
            }

            List<String> beanUtilsControllerLoads = reports.stream()
                .flatMap(report -> report.beanUtilsSpringControllerLoads().stream())
                .toList();

            System.out.println("=".repeat(60));
            System.out.println("📌 BeanUtils.get(...)[Spring Controller] 요약");
            if (beanUtilsControllerLoads.isEmpty()) {
                System.out.println("  - N/A\n");
            } else {
                for (String line : beanUtilsControllerLoads) {
                    System.out.println("  - " + line);
                }
                System.out.println();
            }

            // 콘솔 출력
            for (ControllerReport report : reports) {
                System.out.println("=".repeat(60));
                System.out.println("📁 " + report.simpleClassName());
                for (Endpoint ep : report.endpoints) {
                    System.out.println("\n  [@" + ep.httpMethod() + "] "
                        + ep.signature().oneLine(ep.methodName()));
                    ep.tree().printTree("  ", true);
                }
                System.out.println();
            }

            // Markdown 저장 — 컨트롤러 패키지 구조에 따른 디렉토리 배치
            String baseName = Paths.get(jarPath)
                .getFileName().toString()
                .replace(".jar", "");
            Path outDir = Paths.get(baseName);
            Files.createDirectories(outDir);

            Path readmePath = outDir.resolve("README.md");
            Files.writeString(readmePath, buildOutputReadmeMarkdown(baseName));
            System.out.println("✅ 저장: " + readmePath);

            Path summaryPath = outDir.resolve("00-beanutils-spring-controller-summary.md");
            Files.writeString(summaryPath, buildBeanUtilsSummaryMarkdown(reports));
            System.out.println("✅ 저장: " + summaryPath);

            Path relatedControllersJsonPath = outDir.resolve("00-related-controllers.json");
            Files.writeString(relatedControllersJsonPath, buildRelatedControllersJson(baseName, reports));
            System.out.println("✅ 저장: " + relatedControllersJsonPath);

            for (ControllerReport report : reports) {
                // controllerClass: "com/example/web/UserController" 형태
                Path mdPath = outDir.resolve(report.controllerClass + ".md");
                Files.createDirectories(mdPath.getParent());
                Files.writeString(mdPath, report.toMarkdown());
                System.out.println("✅ 저장: " + mdPath);
            }
        }
    }

    private static String buildBeanUtilsSummaryMarkdown(List<ControllerReport> reports) {
        StringBuilder sb = new StringBuilder();
        sb.append("# BeanUtils Spring Controller 요약\n\n");
        sb.append("> `BeanUtils.get(...)[Spring Controller]` 항목만 추출한 기본 요약 파일  \n");
        sb.append("> 생성 일시: ").append(LocalDateTime.now()).append("\n\n");

        boolean hasAny = reports.stream().anyMatch(report -> !report.beanUtilsSpringControllerLoads().isEmpty());
        if (!hasAny) {
            sb.append("> N/A\n");
            return sb.toString();
        }

        for (ControllerReport report : reports) {
            report.appendBeanUtilsSpringControllerSummary(sb);
        }
        return sb.toString();
    }

    private static String buildOutputReadmeMarkdown(String baseName) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 산출물 기준 설명\n\n");
        sb.append("> 이 디렉토리는 `").append(baseName).append(".jar` 분석 결과물입니다.  \n");
        sb.append("> 생성 일시: ").append(LocalDateTime.now()).append("\n\n");
        sb.append("## 파일 구성\n\n");
        sb.append("- `00-beanutils-spring-controller-summary.md`: `BeanUtils.get(...)[Spring Controller]` 항목만 모은 기본 요약 파일\n");
        sb.append("- `00-related-controllers.json`: 연관 컨트롤러를 구조화한 JSON 산출물\n");
        sb.append("- `{패키지경로}/{Controller}.md`: 컨트롤러별 상세 호출 구조 리포트\n\n");
        sb.append("## 산출 기준\n\n");
        sb.append("- 요약 파일은 `BEAN_UTILS` 노드 중 라벨이 `[Spring Controller]`인 항목만 포함합니다.\n");
        sb.append("- 요약 파일은 컨트롤러별로 `Endpoint Method`와 `BeanUtils.get(...)[Spring Controller]` 컬럼을 가진 테이블 형식입니다.\n");
        sb.append("- JSON 산출물은 컨트롤러 → 엔드포인트 → 연관 컨트롤러 배열 구조이며, FQCN과 simple class name을 함께 제공합니다.\n");
        sb.append("- 같은 엔드포인트 메서드에서 중복되는 대상 컨트롤러는 병합되고, 여러 대상은 한 셀에서 줄바꿈(`<br>`)으로 구분합니다.\n");
        sb.append("- 상세 리포트는 컨트롤러별 엔드포인트, 호출 트리, BeanUtils 사용 위치, LLM 컨텍스트를 모두 유지합니다.\n");
        sb.append("- 상세 리포트 경로는 컨트롤러의 패키지 구조를 그대로 따릅니다.\n");
        return sb.toString();
    }

    private static String buildRelatedControllersJson(String baseName, List<ControllerReport> reports) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        appendJsonField(sb, 1, "artifact", "relatedControllers", true);
        appendJsonField(sb, 1, "sourceJar", baseName + ".jar", true);
        appendJsonField(sb, 1, "generatedAt", LocalDateTime.now().toString(), true);
        sb.append("  \"controllers\": [\n");

        List<ControllerReport> filteredReports = reports.stream()
            .filter(report -> !report.relatedControllerEndpoints().isEmpty())
            .toList();

        for (int i = 0; i < filteredReports.size(); i++) {
            ControllerReport report = filteredReports.get(i);
            sb.append("    {\n");
            appendJsonField(sb, 3, "controllerClass", report.qualifiedClassName(), true);
            appendJsonField(sb, 3, "simpleClassName", report.simpleClassName(), true);
            sb.append("      \"endpoints\": [\n");

            List<ControllerReport.EndpointRelatedControllers> endpoints = report.relatedControllerEndpoints();
            for (int j = 0; j < endpoints.size(); j++) {
                ControllerReport.EndpointRelatedControllers endpoint = endpoints.get(j);
                sb.append("        {\n");
                appendJsonField(sb, 5, "endpointMethod", endpoint.endpointMethod(), true);
                appendJsonField(sb, 5, "httpMethod", endpoint.httpMethod(), true);
                appendJsonField(sb, 5, "path", endpoint.path(), true);
                sb.append("          \"relatedControllers\": [\n");

                List<ControllerReport.RelatedControllerRef> relatedControllers = endpoint.relatedControllers();
                for (int k = 0; k < relatedControllers.size(); k++) {
                    ControllerReport.RelatedControllerRef ref = relatedControllers.get(k);
                    sb.append("            {\n");
                    appendJsonField(sb, 7, "qualifiedClassName", ref.qualifiedClassName(), true);
                    appendJsonField(sb, 7, "simpleClassName", ref.simpleClassName(), true);
                    appendJsonField(sb, 7, "beanUtilsExpression", ref.beanUtilsExpression(), false);
                    sb.append("            }");
                    if (k < relatedControllers.size() - 1) {
                        sb.append(",");
                    }
                    sb.append("\n");
                }

                sb.append("          ]\n");
                sb.append("        }");
                if (j < endpoints.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }

            sb.append("      ]\n");
            sb.append("    }");
            if (i < filteredReports.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendJsonField(StringBuilder sb, int indentLevel, String key, String value, boolean trailingComma) {
        sb.append("  ".repeat(indentLevel))
            .append("\"").append(escapeJson(key)).append("\": ")
            .append("\"").append(escapeJson(value)).append("\"");
        if (trailingComma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(ch);
            }
        }
        return escaped.toString();
    }
}
