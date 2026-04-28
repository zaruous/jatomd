package io.github.analyzer.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 메서드 시그니처 정보 (파라미터 목록 + 리턴 타입)
 */
public record MethodSignatureInfo(List<ParamInfo> params, TypeInfo returnType) {

    public String paramsDisplay() {
        return "(" + params.stream()
            .map(ParamInfo::display)
            .collect(Collectors.joining(", ")) + ")";
    }

    /** LLM 컨텍스트용 한 줄 표현: methodName(params): returnType */
    public String oneLine(String methodName) {
        return methodName + paramsDisplay() + ": " + returnType.display();
    }
}
