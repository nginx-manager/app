package net.nginxmanager;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** constitution "데이터 접근 규칙"을 코드로 강제한다. */
class ArchitectureRulesTest {

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("net.nginxmanager");
    }

    @Test
    void toOneAssociationsAreLazy() {
        fields().that().areAnnotatedWith(ManyToOne.class).or().areAnnotatedWith(OneToOne.class)
                .should(declareLazyFetch())
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void noStringJpqlOrNativeQueries() {
        noClasses().should().callMethod(EntityManager.class, "createQuery", String.class)
                .orShould().callMethod(EntityManager.class, "createQuery", String.class, Class.class)
                .orShould().callMethod(EntityManager.class, "createNativeQuery", String.class)
                .orShould().callMethod(EntityManager.class, "createNativeQuery", String.class, Class.class)
                .because("dynamic queries are written with Querydsl")
                .check(classes);
        methods().should().notBeAnnotatedWith(Query.class)
                .because("dynamic queries are written with Querydsl")
                .check(classes);
    }

    private static ArchCondition<JavaField> declareLazyFetch() {
        return new ArchCondition<>("declare fetch = FetchType.LAZY") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                FetchType fetch = field.tryGetAnnotationOfType(ManyToOne.class).map(ManyToOne::fetch)
                        .or(() -> field.tryGetAnnotationOfType(OneToOne.class).map(OneToOne::fetch))
                        .orElseThrow();
                boolean lazy = fetch == FetchType.LAZY;
                events.add(new SimpleConditionEvent(field, lazy,
                        field.getFullName() + (lazy ? " is LAZY" : " is not LAZY")));
            }
        };
    }
}
