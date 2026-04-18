package com.filetransfer.shared.archrules;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

/**
 * R121: pins the "optional-injection for @ConditionalOnProperty consumers" rule.
 *
 * <p>When a bean is annotated {@code @ConditionalOnProperty(matchIfMissing=false)},
 * it is permanently absent from the AOT-frozen bean graph in any service whose
 * build-time environment didn't set the property. Any consumer that declares a
 * {@code private final FooBean} field (Lombok's {@code @RequiredArgsConstructor}
 * promotes this to a constructor parameter) will hit an
 * {@code UnsatisfiedDependencyException} at context refresh, causing a permanent
 * restart loop. R117→R118→R119 cost two No-Medal release cycles this way.
 *
 * <p>This rule fails the build if any class declares a final field typed as one
 * of our {@code @ConditionalOnProperty(matchIfMissing=false)} beans. Consumers
 * must instead use one of:
 *
 * <ul>
 *   <li>{@code @Autowired(required = false) private FooBean foo;}   (non-final field injection)</li>
 *   <li>{@code private final Optional<FooBean> foo;}               (constructor-safe)</li>
 *   <li>{@code private final ObjectProvider<FooBean> fooProvider;} (constructor-safe, lazy)</li>
 * </ul>
 *
 * <p>See {@link docs.AOT-SAFETY.md} for the full decision tree and retrofit log.
 */
class ConditionalOnPropertyConsumerTest {

    /**
     * Scan scope: everything the platform owns, excluding test classes and
     * Spring-generated AOT output. We explicitly include {@code shared-core} +
     * {@code shared-platform} + the service modules via their canonical
     * top-level package.
     */
    private static final JavaClasses PLATFORM_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.filetransfer");

    private static final String CONDITIONAL_ANNOTATION =
            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty";

    @Test
    void final_fields_of_conditional_beans_must_be_optional_or_provider() {
        Set<String> conditionalBeanTypes = collectConditionalBeanTypes(PLATFORM_CLASSES);

        // If we can't find any conditional beans, the rule is vacuous — fail loudly so we
        // don't silently pass when the codebase changes shape.
        if (conditionalBeanTypes.isEmpty()) {
            throw new IllegalStateException(
                    "No @ConditionalOnProperty(matchIfMissing=false) beans found — "
                    + "either this rule's scanner is broken or every bean is unconditional. "
                    + "Check the annotation import: " + CONDITIONAL_ANNOTATION);
        }

        fields()
                .that(new DescribedPredicate<JavaField>(
                        "are final, typed as a @ConditionalOnProperty(matchIfMissing=false) bean "
                        + conditionalBeanTypes)
                {
                    @Override
                    public boolean test(JavaField field) {
                        if (!field.getModifiers().contains(
                                com.tngtech.archunit.core.domain.JavaModifier.FINAL)) {
                            return false;
                        }
                        return conditionalBeanTypes.contains(field.getRawType().getFullName());
                    }
                })
                .should(allowOnlyOptionalAccessPatterns())
                .because("Consumers of @ConditionalOnProperty(matchIfMissing=false) beans must "
                        + "tolerate the bean's absence: use @Autowired(required=false) field "
                        + "injection, Optional<T>, or ObjectProvider<T>. A `private final T` field "
                        + "(via @RequiredArgsConstructor) makes the consumer crash in every "
                        + "service that didn't set the property at AOT-build time. See "
                        + "docs/AOT-SAFETY.md and the R117→R120 retrofit log.")
                .allowEmptyShould(true)  // rule is trivially satisfied if there are no such fields
                .check(PLATFORM_CLASSES);
    }

    /**
     * Walk every imported class; return the full names of classes that carry
     * {@code @ConditionalOnProperty(matchIfMissing=false)} (on the class itself
     * or via a meta-annotation — ArchUnit handles class-level annotations here).
     */
    private static Set<String> collectConditionalBeanTypes(JavaClasses classes) {
        Set<String> beans = new HashSet<>();
        for (JavaClass cls : classes) {
            if (isGatedByMatchIfMissingFalse(cls.getAnnotations())) {
                beans.add(cls.getFullName());
            }
        }
        return beans;
    }

    /**
     * Treats an annotation set as "gated by matchIfMissing=false" iff it contains
     * a {@code @ConditionalOnProperty} whose {@code matchIfMissing} attribute is
     * explicitly false OR unset (unset defaults to false per Spring Boot).
     */
    private static boolean isGatedByMatchIfMissingFalse(Set<? extends JavaAnnotation<?>> annotations) {
        for (JavaAnnotation<?> a : annotations) {
            if (!CONDITIONAL_ANNOTATION.equals(a.getRawType().getFullName())) continue;
            Object mim = a.getProperties().get("matchIfMissing");
            if (mim == null) return true;           // unset → defaults to false per Spring
            if (Boolean.FALSE.equals(mim)) return true;
        }
        return false;
    }

    /**
     * R121: the only acceptable constructor-friendly wrappers for a conditional bean are
     * {@code Optional<T>} and {@code ObjectProvider<T>}. A bare final field fails.
     * (Non-final fields with {@code @Autowired(required=false)} pass automatically
     * because the predicate above rejects non-final fields before reaching this
     * condition.)
     */
    private static ArchCondition<JavaField> allowOnlyOptionalAccessPatterns() {
        return new ArchCondition<>("be wrapped in Optional<T> or ObjectProvider<T>") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                events.add(SimpleConditionEvent.violated(field,
                        field.getFullName() + " is a final field typed as a conditional bean. "
                        + "Refactor to @Autowired(required=false) (non-final) or "
                        + "Optional<T> / ObjectProvider<T>. "
                        + "Declared in " + field.getOwner().getFullName() + "."));
            }
        };
    }
}
