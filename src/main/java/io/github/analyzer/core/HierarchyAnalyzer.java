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
            MappingRegistry inheritedMappings = loadInheritedMappings(cr.getInterfaces());
            String[] classPathHolder = {inheritedMappings.classPath()};

            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (!desc.contains("RequestMapping")) return null;
                    return new MappingAnnotationVisitor(classPathHolder, null);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (name.startsWith("<")) return null;

                    boolean isStatic  = (access & Opcodes.ACC_STATIC) != 0;
                    String[] httpHolder = {"REQUEST"};
                    String[] methodPathHolder = {""};
                    boolean[] mappingDetected = {false};

                    MethodSignatureInfo baseSig =
                        SignatureParser.parseMethod(descriptor, signature);
                    int paramCount = baseSig.params().size();

                    String[] paramNames       = new String[paramCount];
                    String[] paramAnnotations = new String[paramCount];
                    MethodMapping inheritedMapping =
                        inheritedMappings.methods().get(methodKey(name, descriptor));
                    if (inheritedMapping != null) {
                        mappingDetected[0] = true;
                        httpHolder[0] = inheritedMapping.httpMethod();
                        methodPathHolder[0] = inheritedMapping.path();
                        copyParameterAnnotations(inheritedMapping.parameterAnnotations(),
                            paramAnnotations);
                    }

                    return new MethodVisitor(Opcodes.ASM9) {
                        private int parameterVisitIndex = 0;

                        @Override
                        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                            String httpMethod = httpMethodFromAnnotation(desc);
                            if (httpMethod == null) return null;

                            mappingDetected[0] = true;
                            httpHolder[0] = httpMethod;
                            return new MappingAnnotationVisitor(methodPathHolder, httpHolder);
                        }

                        @Override
                        public void visitParameter(String name, int access) {
                            if (parameterVisitIndex < paramCount) {
                                paramNames[parameterVisitIndex] = name;
                            }
                            parameterVisitIndex++;
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
                            if (!mappingDetected[0]) return;

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
                                String fullPath =
                                    combinePaths(classPathHolder[0], methodPathHolder[0]);
                                report.endpoints.add(
                                    new Endpoint(httpHolder[0], fullPath, name, fullSig, tree));
                            }
                        }
                    };
                }
            }, 0);

            if (report.endpoints.isEmpty()) {
                for (String iface : cr.getInterfaces()) {
                    ControllerReport inherited = analyzeController(iface);
                    if (!inherited.endpoints.isEmpty()) {
                        report.endpoints.addAll(inherited.endpoints);
                        break;
                    }
                }
            }
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
                        private String lastClassLiteral;
                        private CallNode pendingBeanNode;

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof Type type) {
                                lastClassLiteral = type.getInternalName();
                            } else {
                                lastClassLiteral = null;
                            }
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            if (opcode == Opcodes.CHECKCAST && pendingBeanNode != null) {
                                pendingBeanNode.detail = buildBeanUtilsDetail(type);
                                pendingBeanNode = null;
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String calledName,
                                                    String desc, boolean isInterface) {
                            CallNode child = null;
                            if (owner.contains("BeanUtils") && "get".equals(calledName)) {
                                child = new CallNode(owner, calledName, "BEAN_UTILS",
                                    buildBeanUtilsDetail(lastClassLiteral));
                                pendingBeanNode = child;
                            } else if (isInternalPackage(owner)) {
                                String t = owner.contains("Service") ? "SERVICE" : "IMPL";
                                child = analyzeMethod(owner, calledName, t, depth + 1, visited);
                            }
                            if (child != null) node.children.add(child);
                            if (!(owner.contains("BeanUtils") && "get".equals(calledName))) {
                                lastClassLiteral = null;
                            }
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

    private String httpMethodFromAnnotation(String descriptor) {
        if (descriptor.contains("GetMapping")) return "GET";
        if (descriptor.contains("PostMapping")) return "POST";
        if (descriptor.contains("PutMapping")) return "PUT";
        if (descriptor.contains("DeleteMapping")) return "DELETE";
        if (descriptor.contains("PatchMapping")) return "PATCH";
        if (descriptor.contains("RequestMapping")) return "REQUEST";
        return null;
    }

    private String combinePaths(String classPath, String methodPath) {
        String combined = normalizePath(classPath) + normalizePath(methodPath);
        return combined.isEmpty() ? "/" : combined;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) return "";
        String normalized = path.trim().replace('\\', '/');
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String buildBeanUtilsDetail(String internalName) {
        if (internalName == null || internalName.isBlank()) {
            return "BeanUtils.get(?) [Unknown]";
        }

        String qualified = internalName.replace('/', '.');
        String role = classifyLoadedBean(internalName);
        return "BeanUtils.get(" + qualified + ".class) [" + role + "]";
    }

    private String classifyLoadedBean(String internalName) {
        if (loader.isSpringController(internalName)) {
            return "Spring Controller";
        }
        if (internalName.contains("/service/") || internalName.endsWith("Service")) {
            return "Service";
        }
        if (internalName.contains("/controller/") || internalName.endsWith("Controller")) {
            return "Controller-like";
        }
        return "Other Bean";
    }

    private MappingRegistry loadInheritedMappings(String[] interfaces) {
        String classPath = "";
        Map<String, MethodMapping> methods = new HashMap<>();

        for (String iface : interfaces) {
            try (InputStream is = loader.openClass(iface)) {
                if (is == null) continue;
                ClassReader cr = new ClassReader(is);
                String[] ifacePathHolder = {""};

                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (!desc.contains("RequestMapping")) return null;
                        return new MappingAnnotationVisitor(ifacePathHolder, null);
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodSignatureInfo sig =
                            SignatureParser.parseMethod(descriptor, signature);
                        int paramCount = sig.params().size();
                        String[] paramAnnotations = new String[paramCount];
                        String[] httpHolder = {"REQUEST"};
                        String[] pathHolder = {""};
                        boolean[] mappingDetected = {false};

                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                String httpMethod = httpMethodFromAnnotation(desc);
                                if (httpMethod == null) return null;
                                mappingDetected[0] = true;
                                httpHolder[0] = httpMethod;
                                return new MappingAnnotationVisitor(pathHolder, httpHolder);
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
                            public void visitEnd() {
                                if (!mappingDetected[0]) return;
                                methods.put(methodKey(name, descriptor), new MethodMapping(
                                    httpHolder[0], pathHolder[0], paramAnnotations.clone()));
                            }
                        };
                    }
                }, 0);

                if (classPath.isBlank() && !ifacePathHolder[0].isBlank()) {
                    classPath = ifacePathHolder[0];
                }
            } catch (Exception ignored) {}
        }

        return new MappingRegistry(classPath, methods);
    }

    private String methodKey(String name, String descriptor) {
        return name + descriptor;
    }

    private void copyParameterAnnotations(String[] source, String[] target) {
        if (source == null) return;
        for (int i = 0; i < source.length && i < target.length; i++) {
            if (target[i] == null) {
                target[i] = source[i];
            }
        }
    }

    private static final class MappingAnnotationVisitor extends AnnotationVisitor {
        private final String[] pathHolder;
        private final String[] httpHolder;

        private MappingAnnotationVisitor(String[] pathHolder, String[] httpHolder) {
            super(Opcodes.ASM9);
            this.pathHolder = pathHolder;
            this.httpHolder = httpHolder;
        }

        @Override
        public void visit(String name, Object value) {
            if (("value".equals(name) || "path".equals(name)) && value instanceof String path) {
                setPath(path);
            }
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if ("value".equals(name) || "path".equals(name)) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String ignored, Object value) {
                        if (value instanceof String path && isBlank(pathHolder[0])) {
                            setPath(path);
                        }
                    }
                };
            }

            if ("method".equals(name) && httpHolder != null) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitEnum(String ignored, String descriptor, String value) {
                        if ("REQUEST".equals(httpHolder[0])) {
                            httpHolder[0] = value;
                        }
                    }
                };
            }

            return super.visitArray(name);
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            if ("method".equals(name) && httpHolder != null && "REQUEST".equals(httpHolder[0])) {
                httpHolder[0] = value;
            }
        }

        private void setPath(String path) {
            if (isBlank(pathHolder[0])) {
                pathHolder[0] = path;
            }
        }

        private boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    private record MethodMapping(
        String httpMethod,
        String path,
        String[] parameterAnnotations
    ) {}

    private record MappingRegistry(
        String classPath,
        Map<String, MethodMapping> methods
    ) {}
}
