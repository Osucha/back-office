package org.springframework.samples.petclinic.acceptance;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Hidden acceptance tests for benchmark task 4: the vet module follows ports and adapters
 * in the packages named by the task. Every rule refuses to pass on an empty selection, so
 * the original structure (everything in {@code vet}) fails.
 */
class VetHexagonalArchitectureAcceptanceTests {

	private static final String VET = "org.springframework.samples.petclinic.vet";

	private static final String DOMAIN = VET + ".domain..";

	private static final String APPLICATION = VET + ".application..";

	private static final String ADAPTER = VET + ".adapter..";

	private static final String ADAPTER_IN = VET + ".adapter.in..";

	private static final String WEB = VET + ".adapter.in.web..";

	private static final String ADAPTER_OUT = VET + ".adapter.out..";

	private static final String PERSISTENCE = VET + ".adapter.out.persistence..";

	private static JavaClasses classes;

	@BeforeAll
	static void importMainClasses() {
		classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("org.springframework.samples.petclinic");
	}

	@Test
	void domainIsFreeOfFrameworksAndOfTheOuterLayers() {
		DescribedPredicate<JavaClass> spring = resideInAPackage("org.springframework..")
			.and(not(resideInAPackage("org.springframework.samples.petclinic..")));
		DescribedPredicate<JavaClass> forbidden = resideInAnyPackage("jakarta..", "javax.persistence..",
				"com.fasterxml..", "tools.jackson..", "org.hibernate..",
				"org.springframework.samples.petclinic.model..", APPLICATION, ADAPTER)
			.or(spring);
		noClasses().that()
			.resideInAPackage(DOMAIN)
			.should()
			.dependOnClassesThat(forbidden.as("Spring, Jakarta (JPA, JAXB, validation), Jackson, Hibernate, "
					+ "petclinic.model, vet.application or vet.adapter"))
			.allowEmptyShould(false)
			.check(classes);
	}

	@Test
	void applicationDoesNotDependOnAdaptersOrPersistenceOrWeb() {
		noClasses().that()
			.resideInAPackage(APPLICATION)
			.should()
			.dependOnClassesThat()
			.resideInAnyPackage(ADAPTER, "jakarta.persistence..", "org.springframework.data..", "org.hibernate..",
					"org.springframework.web..", "org.springframework.ui..",
					"org.springframework.samples.petclinic.model..")
			.allowEmptyShould(false)
			.check(classes);
	}

	@Test
	void inboundAdaptersDoNotTalkToOutboundAdaptersOrTheOutboundPort() {
		noClasses().that()
			.resideInAPackage(ADAPTER_IN)
			.should()
			.dependOnClassesThat()
			.resideInAPackage(ADAPTER_OUT)
			.orShould()
			.dependOnClassesThat()
			.haveFullyQualifiedName(VET + ".application.VetCatalog")
			.allowEmptyShould(false)
			.check(classes);
	}

	@Test
	void portsAreInterfacesOfTheApplicationLayer() {
		assertThat(classes.contain(VET + ".application.ListVets")).as("inbound port vet.application.ListVets").isTrue();
		assertThat(classes.contain(VET + ".application.VetCatalog")).as("outbound port vet.application.VetCatalog")
			.isTrue();
		assertThat(classes.get(VET + ".application.ListVets").isInterface()).isTrue();
		assertThat(classes.get(VET + ".application.VetCatalog").isInterface()).isTrue();

		classes().that()
			.implement(VET + ".application.ListVets")
			.should()
			.resideInAPackage(APPLICATION)
			.allowEmptyShould(false)
			.check(classes);
		classes().that()
			.implement(VET + ".application.VetCatalog")
			.should()
			.resideInAPackage(PERSISTENCE)
			.allowEmptyShould(false)
			.check(classes);
	}

	@Test
	void domainModelExists() {
		assertThat(classes.contain(VET + ".domain.Vet")).as("vet.domain.Vet").isTrue();
		assertThat(classes.contain(VET + ".domain.Specialty")).as("vet.domain.Specialty").isTrue();
	}

	@Test
	void jpaEntitiesLiveInThePersistenceAdapter() {
		classes().that()
			.resideInAPackage(VET + "..")
			.and()
			.areAnnotatedWith("jakarta.persistence.Entity")
			.should()
			.resideInAPackage(PERSISTENCE)
			.allowEmptyShould(false)
			.check(classes);
		noClasses().that()
			.resideOutsideOfPackage(PERSISTENCE)
			.and()
			.resideInAPackage(VET + "..")
			.should()
			.dependOnClassesThat()
			.areAnnotatedWith("jakarta.persistence.Entity")
			.check(classes);
	}

	@Test
	void controllersLiveInTheWebAdapter() {
		classes().that()
			.resideInAPackage(VET + "..")
			.and()
			.areMetaAnnotatedWith("org.springframework.stereotype.Controller")
			.should()
			.resideInAPackage(WEB)
			.allowEmptyShould(false)
			.check(classes);
	}

	@Test
	void nothingIsLeftInTheOldFlatPackage() {
		List<String> leftovers = classes.stream()
			.filter(c -> c.getPackageName().equals(VET))
			.map(JavaClass::getName)
			.filter(name -> !name.endsWith("package-info"))
			.toList();
		assertThat(leftovers).as("classes directly in " + VET).isEmpty();
	}

}
