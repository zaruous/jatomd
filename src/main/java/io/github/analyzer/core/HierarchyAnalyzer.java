package io.github.analyzer.core;

import io.github.analyzer.model.*;
import io.github.analyzer.parser.SignatureParser;
import org.objectweb.asm.*;

import java.io.InputStream;
import java.util.*;

/**
 * 바이트코드 분석 엔진
 *
 * Controller → Service(Interface) → ServiceImpl 호출 계층을 재귀적으로 탐색하고
 * BeanUtils 사용 위치를 감지합니다.
 */
public class HierarchyAnalyzer {

    private static final int MAX_DEPTH = 5;

    private final JarClassLoader loader;
    private final Map<String, String> implMap;

    public HierarchyAnalyzer(JarClassLoader loader) {
        this.loader  = loader;
        this.implMap = loader.buildImplMap();
    }

    // ── 컨트롤러 전체 분석 ──────────────────────
    public ControllerReport analyzeController(String controllerClass) throws Exception {
        ControllerReport report = new ControllerReport(controllerClass);

        try (InputStream is = loader.openClass(controllerClass)) {
            if (is == null) return report;
            ClassReader cr = new ClassReader(is);

            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (name.startsWith("<")) return null;

                    boolean isStatic  = (access & Opcodes.ACC_STATIC) != 0;
                    String[] httpHolder = {""};

                    MethodSignatureInfo baseSig =
                        SignatureParser.parseMethod(descriptor, signature);
                    int paramCount = baseSig.params().size();

                    String[] paramNames       = new String[paramCount];
                    String[] paramAnnotations = new String[paramCount];

                    return new MethodVisitor(Opcodes.ASM9) {

                        @Override
                        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                            if (desc.contains("GetMapping"))    httpHolder[0] = "GET";
                            if (desc.contains("PostMapping"))   httpHolder[0] = "POST";
                            if (desc.contains("PutMapping"))    httpHolder[0] = "PUT";
                            if (desc.contains("DeleteMapping")) httpHolder[0] = "DELETE";
                            if (desc.contains("PatchMapping"))  httpHolder[0] = "PATCH";
                            return null;
                        }

                        @Override
                        public AnnotationVisitor visitParameterAnnotation(
                                int parameter, String desc, boolean visible) {
                            String ann = extractSpringAnnotation(desc);
                            if (ann != null && parameter < paramCount) {
                                paramAnnotations[parameter] = ann;
                            }
                            return null;
                        }

                        @Override
                        public void visitLocalVariable(String varName, String varDesc,
                                                       String varSig, Label start,
                                                       Label end, int index) {
                            int paramIdx = isStatic ? index : index - 1;
                            if (paramIdx >= 0 && paramIdx < paramCount) {
                                paramNames[paramIdx] = varName;
                            }
                        }

                        @Override
                        public void visitEnd() {
                            if (httpHolder[0].isEmpty()) return;

                            List<ParamInfo> enriched = new ArrayList<>();
                            for (int i = 0; i < paramCount; i++) {
                                enriched.add(new ParamInfo(
                                    baseSig.params().get(i).type(),
                                    paramNames[i],
                                    paramAnnotations[i]
                                ));
                            }
                            MethodSignatureInfo fullSig =
                                new MethodSignatureInfo(enriched, baseSig.returnType());

                            Set<String> visited = new HashSet<>();
                            CallNode tree =
                                analyzeMethod(controllerClass, name, "CONTROLLER", 1, visited);
                            if (tree != null) {
                                report.endpoints.add(
                                    new Endpoint(httpHolder[0], name, fullSig, tree));
                            }
                        }
                    };
                }
            }, 0);
        }
        return report;
    }

    // ── 메서드 재귀 분석 ─────────────────────────
    CallNode analyzeMethod(String className, String methodName,
                           String nodeType, int depth, Set<String> visited) {
        String key = className + "#" + methodName;
        if (depth > MAX_DEPTH || !visited.add(key)) return null;

        CallNode node = new CallNode(className, methodName, nodeType);

        // Interface → Impl 위임
        String impl = implMap.get(className);
        if (impl != null) {
            CallNode implNode = analyzeMethod(impl, methodName, "IMPL", depth + 1, visited);
            if (implNode != null) node.children.add(implNode);
            return node;
        }

        try (InputStream is = loader.openClass(className)) {
            if (is == null) return node;
            ClassReader cr = new ClassReader(is);

            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!name.equals(methodName)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String calledName,
                                                    String desc, boolean isInterface) {
                            CallNode child = null;
                            if (owner.contains("BeanUtils")) {
                                child = new CallNode(owner, calledName, "BEAN_UTILS");
                            } else if (isInternalPackage(owner)) {
                                String t = owner.contains("Service") ? "SERVICE" : "IMPL";
                                child = analyzeMethod(owner, calledName, t, depth + 1, visited);
                            }
                            if (child != null) node.children.add(child);
                        }
                    };
                }
            }, 0);
        } catch (Exception ignored) {}

        return node;
    }

    /**
     * 내부 패키지 여부 판단
     * BeanUtils, java.*, javax.* 등 외부 라이브러리는 재귀 제외
     */
    private boolean isInternalPackage(String owner) {
        return !owner.startsWith("java/")
            && !owner.startsWith("javax/")
            && !owner.startsWith("org/springframework/")
            && !owner.startsWith("org/apache/")
            && !owner.contains("BeanUtils");
    }

    private String extractSpringAnnotation(String descriptor) {
        if (descriptor.contains("RequestBody"))    return "@RequestBody";
        if (descriptor.contains("PathVariable"))   return "@PathVariable";
        if (descriptor.contains("RequestParam"))   return "@RequestParam";
        if (descriptor.contains("RequestHeader"))  return "@RequestHeader";
        if (descriptor.contains("ModelAttribute")) return "@ModelAttribute";
        return null;
    }
}
