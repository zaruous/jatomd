# Spring Hierarchy Analyzer

Spring JAR 파일을 주입하면 **Controller → Service → Impl** 호출 계층을 자동으로 분석합니다.  
외부 라이브러리 없이 ASM 하나만으로 바이트코드를 정적 분석합니다.

## 기능

- `@RestController` / `@Controller` 자동 감지
- Controller 메서드 파라미터 타입 추출 (제네릭 포함: `List<UserDto>`, `Map<String, List<T>>`)
- `@RequestBody`, `@PathVariable`, `@RequestParam` 등 Spring 어노테이션 추출
- 리턴 타입 추출 (`ResponseEntity<List<UserDto>>` 등)
- Interface → Impl 자동 매핑
- `BeanUtils.copyProperties()` 사용 위치 감지 및 경로 추적
- `BeanUtils.get(...)[Spring Controller]` 전용 요약 파일 기본 생성
- Markdown 리포트 + LLM 코딩 가이드 컨텍스트 블록 생성
- Spring Boot fat JAR / 일반 JAR / WAR 모두 지원

## 빌드

```bash
mvn package -q
```

## 실행

```bash
java -jar target/spring-hierarchy-analyzer-1.0.0.jar myapp.jar
```

### 출력 예시

```
📦 분석 대상: myapp.jar
   클래스 수: 342
   컨트롤러 수: 5개

============================================================
📌 BeanUtils.get(...)[Spring Controller] 요약
  - createUser() -> BeanUtils.get(UserController.class) [Spring Controller]

============================================================
📁 UserController

  [@POST] createUser(@RequestBody CreateUserRequest request, @PathVariable Long id): ResponseEntity<UserDto>
  └─ UserController.createUser() [Controller]
       └─ UserService.createUser() [Service]
            └─ UserServiceImpl.createUser() [Impl]
                 ├─ AddressService.validate() [Service]
                 │    └─ BeanUtils.copyProperties() ⚠️
                 └─ UserMapper.toEntity() [Impl]

✅ 저장: myapp/00-beanutils-spring-controller-summary.md
✅ 저장: myapp/README.md
✅ 저장: myapp/com/example/web/UserController.md
```

## 생성 파일

| 파일 | 내용 |
|---|---|
| `{jar명}/README.md` | 산출물 파일 종류와 요약/상세 리포트의 포함 기준 설명 |
| `{jar명}/00-beanutils-spring-controller-summary.md` | `BeanUtils.get(...)[Spring Controller]` 항목만 모은 기본 요약 파일 |
| `{jar명}/{패키지경로}/{Controller}.md` | 엔드포인트별 호출 트리, 파라미터 테이블, BeanUtils 요약, LLM 컨텍스트 블록 |

## Java 버전별 대응

| Java 버전 | 분석 엔진 |
|---|---|
| 8 ~ 23 | ASM (현재, `asm.jar` 내장) |
| 24+ | `java.lang.classfile` (JDK 내장, 추가 JAR 불필요) |

## 요구사항

- Java 17+
- Maven 3.6+
- 분석 대상 JAR에 참조 라이브러리 없어도 동작 (정적 바이트코드 분석)
