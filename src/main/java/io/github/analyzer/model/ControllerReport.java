package io.github.analyzer.model;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 컨트롤러 분석 리포트
 * Markdown 직렬화 및 LLM 컨텍스트 블록 생성 포함
 */
public class ControllerReport {

    private static final Pattern SPRING_CONTROLLER_PATTERN =
        Pattern.compile("BeanUtils\\.get\\(([^)]+)\\.class\\) \\[Spring Controller\\]");

    public final String controllerClass;
    public final List<Endpoint> endpoints = new ArrayList<>();

    public ControllerReport(String controllerClass) {
        this.controllerClass = controllerClass;
    }

    public String simpleClassName() {
        return controllerClass.substring(controllerClass.lastIndexOf('/') + 1);
    }

    public String qualifiedClassName() {
        return controllerClass.replace('/', '.');
    }

    public List<String> beanUtilsSpringControllerLoads() {
        Map<String, LinkedHashSet<String>> grouped = collectBeanLoadsByEndpoint("Spring Controller");
        List<String> lines = new ArrayList<>();
        grouped.forEach((endpointMethod, labels) ->
            labels.forEach(label -> lines.add(endpointMethod + "() -> " + label)));
        return lines;
    }

    public List<EndpointRelatedControllers> relatedControllerEndpoints() {
        List<EndpointRelatedControllers> endpointsWithRelations = new ArrayList<>();
        for (Endpoint ep : endpoints) {
            LinkedHashSet<String> labels = new LinkedHashSet<>();
            collectBeanLoads(ep.tree(), ep.methodName(), labels, "Spring Controller");
            if (labels.isEmpty()) {
                continue;
            }

            List<RelatedControllerRef> relatedControllers = labels.stream()
                .map(this::parseRelatedController)
                .filter(Objects::nonNull)
                .toList();
            if (relatedControllers.isEmpty()) {
                continue;
            }

            endpointsWithRelations.add(new EndpointRelatedControllers(
                ep.methodName(), ep.httpMethod(), ep.path(), relatedControllers));
        }
        return endpointsWithRelations;
    }

    public void appendBeanUtilsSpringControllerSummary(StringBuilder sb) {
        Map<String, LinkedHashSet<String>> grouped = collectBeanLoadsByEndpoint("Spring Controller");
        sb.append("## ").append(qualifiedClassName()).append("\n\n");
        if (grouped.isEmpty()) {
            sb.append("> N/A\n\n");
            return;
        }

        sb.append("| Endpoint Method | BeanUtils.get(...)[Spring Controller] |\n");
        sb.append("|---|---|\n");
        grouped.forEach((endpointMethod, labels) -> sb.append("| `")
            .append(endpointMethod).append("()` | ")
            .append(String.join("<br>", labels))
            .append(" |\n"));
        sb.append("\n");
    }

    // ── 전체 Markdown 리포트 ──────────────────────
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();

        sb.append("# 호출 구조 분석 리포트\n\n");
        sb.append("> **분석 대상:** `").append(simpleClassName()).append("`  \n");
        sb.append("> **생성 일시:** ").append(LocalDateTime.now()).append("\n\n");
        sb.append("---\n\n");

        sb.append("## 엔드포인트별 호출 구조\n\n");
        if (endpoints.isEmpty()) {
            sb.append("> 감지된 엔드포인트가 없습니다. ")
              .append("현재 컨트롤러가 `@RequestMapping` 계열이 아니거나, ")
              .append("바이트코드에서 Spring 매핑 정보를 추출하지 못했을 수 있습니다.\n\n");
        } else {
            for (Endpoint ep : endpoints) {
                appendEndpoint(sb, ep);
            }
        }

        appendBeanUtilsLoadSummary(sb);
        appendBeanUtilsSummary(sb);
        appendLlmContext(sb);

        return sb.toString();
    }

    private void appendEndpoint(StringBuilder sb, Endpoint ep) {
        // 헤더
        sb.append("### `@").append(ep.httpMethod()).append("` ")
          .append(ep.path()).append(" -> ")
          .append(ep.methodName()).append("()\n\n");

        // 파라미터 테이블
        List<ParamInfo> params = ep.signature().params();
        if (!params.isEmpty()) {
            sb.append("| # | 어노테이션 | 타입 | 파라미터명 |\n");
            sb.append("|---|---|---|---|\n");
            for (int i = 0; i < params.size(); i++) {
                ParamInfo p = params.get(i);
                sb.append("| ").append(i + 1)
                  .append(" | ").append(p.annotation() != null ? p.annotation() : "-")
                  .append(" | `").append(p.type().display()).append("`")
                  .append(" | ").append(p.name() != null ? p.name() : "-")
                  .append(" |\n");
            }
            sb.append("\n");
        }

        // 리턴타입
        sb.append("**리턴타입:** `")
          .append(ep.signature().returnType().display())
          .append("`\n\n");

        // ASCII 호출 트리
        sb.append("**호출 구조:**\n```\n");
        sb.append(renderAsciiTree(ep.tree(), "", true));
        sb.append("```\n\n");

        // Markdown 트리
        ep.tree().toMarkdown(sb, 0);
        sb.append("\n");
    }

    private void appendBeanUtilsLoadSummary(StringBuilder sb) {
        sb.append("---\n\n## BeanUtils 로드 객체 요약\n\n");
        List<String> lines = new ArrayList<>();
        for (Endpoint ep : endpoints) {
            collectBeanLoads(ep.tree(), ep.methodName(), lines, null);
        }

        if (lines.isEmpty()) {
            sb.append("> N/A\n\n");
            return;
        }

        lines.forEach(line -> sb.append("- ").append(line).append("\n"));
        sb.append("\n");
    }

    private void appendBeanUtilsSummary(StringBuilder sb) {
        sb.append("---\n\n## ⚠️ BeanUtils 사용 위치 요약\n\n");
        List<String> allPaths = new ArrayList<>();
        for (Endpoint ep : endpoints) {
            ep.tree().collectBeanUtilsPaths(allPaths, new ArrayDeque<>());
        }
        if (allPaths.isEmpty()) {
            sb.append("> N/A\n\n");
        } else {
            allPaths.forEach(p -> sb.append("- ").append(p).append("\n"));
            sb.append("\n");
        }
    }

    private Map<String, LinkedHashSet<String>> collectBeanLoadsByEndpoint(String roleFilter) {
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (Endpoint ep : endpoints) {
            collectBeanLoads(ep.tree(), ep.methodName(), grouped, roleFilter);
        }
        return grouped;
    }

    private void collectBeanLoads(CallNode node, String endpointMethod,
                                  Map<String, LinkedHashSet<String>> grouped, String roleFilter) {
        if ("BEAN_UTILS".equals(node.type)) {
            String role = node.beanRole();
            if (roleFilter == null || roleFilter.equals(role)) {
                grouped.computeIfAbsent(endpointMethod, ignored -> new LinkedHashSet<>())
                    .add(node.label());
            }
        }
        for (CallNode child : node.children) {
            collectBeanLoads(child, endpointMethod, grouped, roleFilter);
        }
    }

    private void collectBeanLoads(CallNode node, String endpointMethod,
                                  List<String> lines, String roleFilter) {
        if ("BEAN_UTILS".equals(node.type)) {
            String role = node.beanRole();
            String line = endpointMethod + "() -> " + node.label();
            if ((roleFilter == null || roleFilter.equals(role)) && !lines.contains(line)) {
                lines.add(line);
            }
        }
        for (CallNode child : node.children) {
            collectBeanLoads(child, endpointMethod, lines, roleFilter);
        }
    }

    private void collectBeanLoads(CallNode node, String endpointMethod,
                                  Set<String> labels, String roleFilter) {
        if ("BEAN_UTILS".equals(node.type)) {
            String role = node.beanRole();
            if (roleFilter == null || roleFilter.equals(role)) {
                labels.add(node.label());
            }
        }
        for (CallNode child : node.children) {
            collectBeanLoads(child, endpointMethod, labels, roleFilter);
        }
    }

    private RelatedControllerRef parseRelatedController(String label) {
        Matcher matcher = SPRING_CONTROLLER_PATTERN.matcher(label);
        if (!matcher.matches()) {
            return null;
        }
        String qualifiedClassName = matcher.group(1);
        String simpleClassName = qualifiedClassName.substring(qualifiedClassName.lastIndexOf('.') + 1);
        return new RelatedControllerRef(qualifiedClassName, simpleClassName, label);
    }

    private void appendLlmContext(StringBuilder sb) {
        sb.append("---\n\n## LLM 코딩 가이드 컨텍스트\n\n```\n");
        sb.append("[프로젝트 호출 구조 규칙]\n");
        sb.append("- Controller → Service(Interface) → ServiceImpl 계층 준수\n");
        sb.append("- 호출 깊이 최대 5depth\n");
        sb.append("- BeanUtils.copyProperties()는 ServiceImpl 레이어에서만 허용\n");
        sb.append("- 순환 호출 금지\n\n");
        sb.append("[현재 코드베이스 호출 구조]\n");
        for (Endpoint ep : endpoints) {
            sb.append("@").append(ep.httpMethod()).append(" ")
              .append(ep.path()).append(" ")
              .append(ep.signature().oneLine(ep.methodName())).append("\n");
            appendLlmTree(sb, ep.tree(), 1);
            sb.append("\n");
        }
        sb.append("```\n");
    }

    private void appendLlmTree(StringBuilder sb, CallNode node, int depth) {
        sb.append("  ".repeat(depth)).append("- ")
          .append(node.label()).append(" (").append(node.type).append(")\n");
        for (CallNode child : node.children) appendLlmTree(sb, child, depth + 1);
    }

    // ── ASCII 트리 렌더러 ──────────────────────────
    private String renderAsciiTree(CallNode node, String prefix, boolean isLast) {
        StringBuilder sb = new StringBuilder();
        String connector = isLast ? "└─ " : "├─ ";
        String tag = "BEAN_UTILS".equals(node.type) ? " ⚠️" : "";
        sb.append(prefix).append(connector).append(node.label()).append(tag).append("\n");
        String childPrefix = prefix + (isLast ? "     " : "│    ");
        for (int i = 0; i < node.children.size(); i++) {
            sb.append(renderAsciiTree(
                node.children.get(i), childPrefix, i == node.children.size() - 1));
        }
        return sb.toString();
    }

    public record RelatedControllerRef(
        String qualifiedClassName,
        String simpleClassName,
        String beanUtilsExpression
    ) {}

    public record EndpointRelatedControllers(
        String endpointMethod,
        String httpMethod,
        String path,
        List<RelatedControllerRef> relatedControllers
    ) {}
}
