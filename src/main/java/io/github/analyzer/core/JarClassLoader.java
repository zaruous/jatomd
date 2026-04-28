package io.github.analyzer.core;

import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.*;

/**
 * JAR 파일에서 클래스를 읽는 로더
 *
 * 지원:
 *  - 일반 JAR:          com/example/Foo.class
 *  - Spring Boot fat JAR: BOOT-INF/classes/com/example/Foo.class
 *  - WAR:               WEB-INF/classes/com/example/Foo.class
 */
public class JarClassLoader implements Closeable {

    private final JarFile jarFile;
    private final Map<String, JarEntry> entryMap = new HashMap<>();

    public JarClassLoader(String jarPath) throws IOException {
        this.jarFile = new JarFile(jarPath);
        indexEntries();
    }

    private void indexEntries() {
        jarFile.stream()
            .filter(e -> e.getName().endsWith(".class"))
            .forEach(e -> {
                String key = toInternalName(e.getName());
                entryMap.put(key, e);
            });
    }

    /**
     * 엔트리 경로 → 내부 클래스명 변환
     * BOOT-INF/classes/com/example/Foo.class → com/example/Foo
     */
    private String toInternalName(String entryName) {
        String name = entryName.replace(".class", "");
        if (name.startsWith("BOOT-INF/classes/")) {
            name = name.substring("BOOT-INF/classes/".length());
        } else if (name.startsWith("WEB-INF/classes/")) {
            name = name.substring("WEB-INF/classes/".length());
        }
        return name;
    }

    /** 클래스 내부명으로 InputStream 반환 */
    public InputStream openClass(String internalName) throws IOException {
        JarEntry entry = entryMap.get(internalName);
        if (entry == null) return null;
        return jarFile.getInputStream(entry);
    }

    /** 전체 클래스 스트림 순회 */
    public void scanAll(Consumer<InputStream> consumer) {
        entryMap.forEach((name, entry) -> {
            try (InputStream is = jarFile.getInputStream(entry)) {
                consumer.accept(is);
            } catch (IOException ignored) {}
        });
    }

    /**
     * @RestController / @Controller 어노테이션이 붙은 클래스 탐색
     */
    public List<String> findControllers() {
        List<String> controllers = new ArrayList<>();
        entryMap.forEach((name, entry) -> {
            try (InputStream is = jarFile.getInputStream(entry)) {
                ClassReader cr = new ClassReader(is);
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (desc.contains("RestController") || desc.contains("Controller")) {
                            controllers.add(name);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE);
            } catch (IOException ignored) {}
        });
        return controllers;
    }

    /** Interface → Impl 매핑 스캔 */
    public Map<String, String> buildImplMap() {
        Map<String, String> implMap = new HashMap<>();
        entryMap.forEach((name, entry) -> {
            try (InputStream is = jarFile.getInputStream(entry)) {
                ClassReader cr      = new ClassReader(is);
                String[]    ifaces  = cr.getInterfaces();
                for (String iface : ifaces) {
                    implMap.put(iface, cr.getClassName());
                }
            } catch (IOException ignored) {}
        });
        return implMap;
    }

    public int classCount() { return entryMap.size(); }

    @Override
    public void close() throws IOException { jarFile.close(); }
}
