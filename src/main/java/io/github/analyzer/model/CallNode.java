package io.github.analyzer.model;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 호출 트리 노드
 * type: CONTROLLER | SERVICE | IMPL | BEAN_UTILS
 */
public class CallNode {

    public final String className;
    public final String methodName;
    public final String type;
    public final List<CallNode> children = new ArrayList<>();

    public CallNode(String className, String methodName, String type) {
        this.className  = className;
        this.methodName = methodName;
        this.type       = type;
    }

    public String label() {
        String simple = className.substring(className.lastIndexOf('/') + 1);
        return simple + "." + methodName + "()";
    }

    // ── 콘솔 출력 ──
    public void printTree(String prefix, boolean isLast) {
        String connector = isLast ? "└─ " : "├─ ";
        String tag = switch (type) {
            case "BEAN_UTILS"  -> " ⚠️  BeanUtils";
            case "CONTROLLER"  -> " [Controller]";
            case "SERVICE"     -> " [Service]";
            case "IMPL"        -> " [Impl]";
            default            -> "";
        };
        System.out.println(prefix + connector + label() + tag);
        String childPrefix = prefix + (isLast ? "     " : "│    ");
        for (int i = 0; i < children.size(); i++) {
            children.get(i).printTree(childPrefix, i == children.size() - 1);
        }
    }

    // ── Markdown 트리 직렬화 ──
    public void toMarkdown(StringBuilder sb, int depth) {
        String indent = "  ".repeat(depth);
        String bullet = depth == 0 ? "###" : "-";
        String badge  = switch (type) {
            case "BEAN_UTILS"  -> " `⚠️ BeanUtils`";
            case "CONTROLLER"  -> " `[Controller]`";
            case "SERVICE"     -> " `[Service]`";
            case "IMPL"        -> " `[Impl]`";
            default            -> "";
        };
        if (depth == 0) {
            sb.append(bullet).append(" ").append(label()).append(badge).append("\n\n");
        } else {
            sb.append(indent).append(bullet)
              .append(" `").append(label()).append("`").append(badge).append("\n");
        }
        for (CallNode child : children) child.toMarkdown(sb, depth + 1);
    }

    // ── BeanUtils 사용 경로 수집 ──
    public void collectBeanUtilsPaths(List<String> paths, Deque<String> stack) {
        stack.push(label());
        if ("BEAN_UTILS".equals(type)) {
            List<String> path = new ArrayList<>(stack);
            java.util.Collections.reverse(path);
            paths.add(String.join(" → ", path));
        }
        for (CallNode child : children) child.collectBeanUtilsPaths(paths, stack);
        stack.pop();
    }
}
