package io.graphspindle.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.graphspindle.common.Actor;
import io.graphspindle.common.BrainAccessGuard;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * IMPLEMENTATION_PLAN M9 {@code ServiceGuardTest}: every public application service method that takes
 * a brain id takes the {@link Actor} and reaches {@link BrainAccessGuard} (directly or through the
 * methods it calls, e.g. {@code BrainService.requireActive}), so no brain-scoped service method can
 * skip authorization, whichever adapter (REST or MCP) calls it.
 */
class ServiceGuardTest {

    static final List<String> SERVICE_PACKAGES = List.of("brain", "knowledge", "retrieval", "conflict", "ingestion",
            "admin", "security");

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.graphspindle");
    }

    @Test
    void everyBrainScopedServiceMethodCallsTheGuard() throws Exception {
        List<String> checked = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        Map<JavaCodeUnit, Boolean> memo = new HashMap<>();
        for (JavaClass c : classes) {
            if (!c.getSimpleName().endsWith("Service") || SERVICE_PACKAGES.stream()
                    .noneMatch(p -> c.getPackageName().equals("io.graphspindle." + p))) {
                continue;
            }
            for (Method m : Class.forName(c.getName()).getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers()) || m.isSynthetic()) {
                    continue;
                }
                Parameter[] params = m.getParameters();
                boolean brainId = Arrays.stream(params).anyMatch(p -> p.getName().equals("brainId")
                        && p.getType() == String.class);
                if (!brainId) {
                    continue;
                }
                String name = c.getSimpleName() + "." + m.getName();
                checked.add(name);
                if (Arrays.stream(params).noneMatch(p -> p.getType() == Actor.class)) {
                    offenders.add(name + ": no Actor parameter");
                    continue;
                }
                JavaCodeUnit unit = codeUnit(c, m).orElseThrow();
                if (!reachesGuard(unit, memo, 0)) {
                    offenders.add(name + ": never reaches BrainAccessGuard");
                }
            }
        }
        assertThat(offenders).isEmpty();
        // the parameter-name scan only works with -parameters: make sure it really found the services
        assertThat(checked).hasSizeGreaterThan(40).contains("KnowledgeService.createEntity", "IngestionService.ingest",
                "BrainService.delete", "VocabularyTypeService.createNodeType", "ApiKeyService.createForBrain",
                "SearchService.search", "ConflictService.resolve", "ProjectionCheckService.check");
    }

    private static Optional<JavaCodeUnit> codeUnit(JavaClass c, Method m) {
        return c.getCodeUnits().stream().filter(u -> u.getName().equals(m.getName())
                && u.getRawParameterTypes().stream().map(JavaClass::getName).toList()
                        .equals(Arrays.stream(m.getParameterTypes()).map(Class::getName).toList()))
                .findFirst();
    }

    private static boolean reachesGuard(JavaCodeUnit unit, Map<JavaCodeUnit, Boolean> memo, int depth) {
        Boolean known = memo.get(unit);
        if (known != null) {
            return known;
        }
        memo.put(unit, false); // cycle guard
        boolean result = false;
        for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
            if (call.getTargetOwner().isEquivalentTo(BrainAccessGuard.class)
                    && call.getName().startsWith("require")) {
                result = true;
                break;
            }
        }
        if (!result && depth < 6) {
            for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
                if (!call.getTargetOwner().getPackageName().startsWith("io.graphspindle")) {
                    continue;
                }
                for (JavaCodeUnit target : call.getTarget().resolveMember().stream().toList()) {
                    if (reachesGuard(target, memo, depth + 1)) {
                        result = true;
                        break;
                    }
                }
                if (result) {
                    break;
                }
            }
        }
        memo.put(unit, result);
        return result;
    }
}
