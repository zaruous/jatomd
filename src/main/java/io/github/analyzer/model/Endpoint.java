package io.github.analyzer.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

/** 컨트롤러의 단일 엔드포인트 */
public record Endpoint(
    String httpMethod,
    String methodName,
    MethodSignatureInfo signature,
    CallNode tree
) {}
