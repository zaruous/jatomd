package io.github.analyzer.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 클래스 타입 정보 (제네릭 포함)
 * 예: List&lt;UserDto&gt;, Map&lt;String, List&lt;UserDto&gt;&gt;
 */
public record TypeInfo(String name, List<TypeInfo> typeArgs) {

    public static TypeInfo simple(String name) {
        return new TypeInfo(name, List.of());
    }

    /** 표시용 문자열: List&lt;UserDto&gt; */
    public String display() {
        if (typeArgs.isEmpty()) return name;
        return name + "<" + typeArgs.stream()
            .map(TypeInfo::display)
            .collect(Collectors.joining(", ")) + ">";
    }
}
