package io.github.analyzer.parser;

import io.github.analyzer.model.MethodSignatureInfo;
import io.github.analyzer.model.ParamInfo;
import io.github.analyzer.model.TypeInfo;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ASM descriptor / signature 문자열을 TypeInfo 트리로 변환
 *
 * 지원 케이스:
 *   List&lt;UserDto&gt;, Map&lt;String, List&lt;UserDto&gt;&gt;
 *   ? extends BaseDto, ? super Object
 *   int[], UserDto[], ResponseEntity&lt;T&gt;
 */
public class SignatureParser {

    // ── 진입점 ──────────────────────────────────
    public static MethodSignatureInfo parseMethod(String descriptor, String signature) {
        return (signature != null)
            ? parseFromSignature(signature)
            : parseFromDescriptor(descriptor);
    }

    // ── descriptor 파싱 (제네릭 없음) ───────────
    private static MethodSignatureInfo parseFromDescriptor(String descriptor) {
        List<ParamInfo> params = Arrays.stream(Type.getArgumentTypes(descriptor))
            .map(t -> new ParamInfo(fromAsmType(t), null, null))
            .collect(Collectors.toList());
        TypeInfo ret = fromAsmType(Type.getReturnType(descriptor));
        return new MethodSignatureInfo(params, ret);
    }

    // ── signature 파싱 (제네릭 포함) ─────────────
    private static MethodSignatureInfo parseFromSignature(String sig) {
        int[] pos = {0};

        // 클래스 레벨 타입 파라미터 스킵: <T:Ljava/lang/Object;>
        if (sig.charAt(0) == '<') {
            int depth = 0;
            while (pos[0] < sig.length()) {
                char c = sig.charAt(pos[0]++);
                if      (c == '<') depth++;
                else if (c == '>') { if (--depth == 0) break; }
            }
        }

        pos[0]++; // '(' 스킵
        List<ParamInfo> params = new ArrayList<>();
        while (pos[0] < sig.length() && sig.charAt(pos[0]) != ')') {
            params.add(new ParamInfo(parseType(sig, pos), null, null));
        }
        pos[0]++; // ')' 스킵

        TypeInfo ret = parseType(sig, pos);
        return new MethodSignatureInfo(params, ret);
    }

    // ── 재귀 타입 파서 ───────────────────────────
    public static TypeInfo parseType(String sig, int[] pos) {
        if (pos[0] >= sig.length()) return TypeInfo.simple("?");
        return switch (sig.charAt(pos[0])) {
            case 'L' -> parseClassType(sig, pos);
            case '[' -> parseArrayType(sig, pos);
            case 'T' -> parseTypeVar(sig, pos);
            case 'I' -> { pos[0]++; yield TypeInfo.simple("int");     }
            case 'J' -> { pos[0]++; yield TypeInfo.simple("long");    }
            case 'D' -> { pos[0]++; yield TypeInfo.simple("double");  }
            case 'F' -> { pos[0]++; yield TypeInfo.simple("float");   }
            case 'Z' -> { pos[0]++; yield TypeInfo.simple("boolean"); }
            case 'B' -> { pos[0]++; yield TypeInfo.simple("byte");    }
            case 'C' -> { pos[0]++; yield TypeInfo.simple("char");    }
            case 'S' -> { pos[0]++; yield TypeInfo.simple("short");   }
            case 'V' -> { pos[0]++; yield TypeInfo.simple("void");    }
            default  -> { pos[0]++; yield TypeInfo.simple("?");       }
        };
    }

    // Ljava/util/List<Lcom/example/dto/UserDto;>;
    private static TypeInfo parseClassType(String sig, int[] pos) {
        pos[0]++; // 'L' 스킵
        int start = pos[0];
        while (pos[0] < sig.length()
               && sig.charAt(pos[0]) != '<'
               && sig.charAt(pos[0]) != ';') pos[0]++;

        String raw    = sig.substring(start, pos[0]);
        String simple = raw.substring(raw.lastIndexOf('/') + 1);

        List<TypeInfo> args = new ArrayList<>();
        if (pos[0] < sig.length() && sig.charAt(pos[0]) == '<') {
            pos[0]++; // '<' 스킵
            while (pos[0] < sig.length() && sig.charAt(pos[0]) != '>') {
                args.add(parseTypeArg(sig, pos));
            }
            pos[0]++; // '>' 스킵
        }

        while (pos[0] < sig.length() && sig.charAt(pos[0]) != ';') pos[0]++;
        if (pos[0] < sig.length()) pos[0]++; // ';' 스킵

        return new TypeInfo(simple, args);
    }

    // 와일드카드: *, +Bound (? extends), -Bound (? super)
    private static TypeInfo parseTypeArg(String sig, int[] pos) {
        return switch (sig.charAt(pos[0])) {
            case '*' -> { pos[0]++; yield TypeInfo.simple("?"); }
            case '+' -> { pos[0]++; TypeInfo b = parseType(sig, pos);
                          yield TypeInfo.simple("? extends " + b.display()); }
            case '-' -> { pos[0]++; TypeInfo b = parseType(sig, pos);
                          yield TypeInfo.simple("? super " + b.display()); }
            default  -> parseType(sig, pos);
        };
    }

    private static TypeInfo parseArrayType(String sig, int[] pos) {
        pos[0]++; // '[' 스킵
        return TypeInfo.simple(parseType(sig, pos).display() + "[]");
    }

    private static TypeInfo parseTypeVar(String sig, int[] pos) {
        pos[0]++; // 'T' 스킵
        int start = pos[0];
        while (pos[0] < sig.length() && sig.charAt(pos[0]) != ';') pos[0]++;
        String name = sig.substring(start, pos[0]);
        if (pos[0] < sig.length()) pos[0]++;
        return TypeInfo.simple(name);
    }

    // ASM Type → TypeInfo 변환
    public static TypeInfo fromAsmType(Type t) {
        return switch (t.getSort()) {
            case Type.ARRAY  -> TypeInfo.simple(
                fromAsmType(t.getElementType()).display() + "[]".repeat(t.getDimensions()));
            case Type.OBJECT -> TypeInfo.simple(
                t.getInternalName().substring(t.getInternalName().lastIndexOf('/') + 1));
            case Type.VOID   -> TypeInfo.simple("void");
            default          -> TypeInfo.simple(t.getClassName());
        };
    }
}
