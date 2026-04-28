package io.github.analyzer.model;

/**
 * 메서드 파라미터 정보
 * 예: @RequestBody CreateUserRequest request
 */
public record ParamInfo(TypeInfo type, String name, String annotation) {

    public String display() {
        String ann = (annotation != null) ? annotation + " " : "";
        String n   = (name != null)       ? " " + name       : "";
        return ann + type.display() + n;
    }
}
