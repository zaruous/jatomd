package io.github.analyzer;

import io.github.analyzer.core.HierarchyAnalyzer;
import io.github.analyzer.core.JarClassLoader;
import io.github.analyzer.model.ControllerReport;
import io.github.analyzer.model.Endpoint;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring Hierarchy Analyzer
 *
 * 사용법:
 *   java -jar spring-hierarchy-analyzer.jar [target.jar]
 *
 * 출력:
 *   {jar명}-call-hierarchy.md
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
                .collect(Collectors.toList());

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

            // Markdown 저장
            String reportName = Paths.get(jarPath)
                .getFileName().toString()
                .replace(".jar", "") + "-call-hierarchy.md";

            String combined = reports.stream()
                .map(ControllerReport::toMarkdown)
                .collect(Collectors.joining("\n\n---\n\n"));

            Files.writeString(Paths.get(reportName), combined);
            System.out.println("✅ 리포트 저장 완료: " + reportName);
        }
    }
}
