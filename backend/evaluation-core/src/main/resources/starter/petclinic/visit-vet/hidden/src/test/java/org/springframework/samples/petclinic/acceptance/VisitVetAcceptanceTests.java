package org.springframework.samples.petclinic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Hidden acceptance tests for benchmark task 2 (vet on a visit). Black box: the whole
 * application on H2 over MockMvc, every request in its own transaction, so the visit
 * really goes through the database. Each test creates its own owner and pet. The MySQL
 * and PostgreSQL scripts cannot run without Docker, so they are checked as text.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VisitVetAcceptanceTests {

	private static final Pattern VISIT_LINK = Pattern.compile("(\\d+)/pets/(\\d+)/visits/new");

	private static final String[] VET_NAMES = { "James Carter", "Helen Leary", "Linda Douglas", "Rafael Ortega",
			"Henry Stevens", "Sharon Jenkins" };

	@Autowired
	private MockMvc mockMvc;

	@Test
	void visitFormOffersEveryVetAndAnEmptyChoice() throws Exception {
		int[] ids = newOwnerWithPet("Formcheck");
		String form = html("/owners/" + ids[0] + "/pets/" + ids[1] + "/visits/new");
		assertThat(form).containsPattern("<select[^>]*name=\"vet\"");
		assertThat(form).containsPattern("<option[^>]*value=\"\"");
		for (int i = 0; i < VET_NAMES.length; i++) {
			assertThat(form).as("option for vet " + (i + 1))
				.containsPattern("<option[^>]*value=\"" + (i + 1) + "\"[^>]*>\\s*" + VET_NAMES[i] + "\\s*</option>");
		}
	}

	@Test
	void visitWithVetIsStoredAndShownOnTheOwnerPage() throws Exception {
		int[] ids = newOwnerWithPet("Withvet");
		this.mockMvc
			.perform(post("/owners/{ownerId}/pets/{petId}/visits/new", ids[0], ids[1])
				.param("date", LocalDate.now().plusDays(2).toString())
				.param("description", "acceptance-with-vet")
				.param("vet", "5"))
			.andExpect(status().is3xxRedirection());

		String row = rowContaining(html("/owners/" + ids[0]), "acceptance-with-vet");
		assertThat(row).contains("Henry Stevens");
	}

	@Test
	void visitWithoutVetStillWorks() throws Exception {
		int[] ids = newOwnerWithPet("Novet");
		this.mockMvc
			.perform(post("/owners/{ownerId}/pets/{petId}/visits/new", ids[0], ids[1])
				.param("date", LocalDate.now().plusDays(2).toString())
				.param("description", "acceptance-empty-vet")
				.param("vet", ""))
			.andExpect(status().is3xxRedirection());
		this.mockMvc
			.perform(post("/owners/{ownerId}/pets/{petId}/visits/new", ids[0], ids[1])
				.param("date", LocalDate.now().plusDays(3).toString())
				.param("description", "acceptance-no-vet-param"))
			.andExpect(status().is3xxRedirection());

		String page = html("/owners/" + ids[0]);
		assertNoVetName(rowContaining(page, "acceptance-empty-vet"));
		assertNoVetName(rowContaining(page, "acceptance-no-vet-param"));
	}

	@Test
	void unknownVetIsAFieldErrorAndNothingIsSaved() throws Exception {
		int[] ids = newOwnerWithPet("Unknownvet");
		this.mockMvc
			.perform(post("/owners/{ownerId}/pets/{petId}/visits/new", ids[0], ids[1])
				.param("date", LocalDate.now().plusDays(2).toString())
				.param("description", "acceptance-unknown-vet")
				.param("vet", "987654"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrors("visit", "vet"))
			.andExpect(view().name("pets/createOrUpdateVisitForm"));

		assertThat(html("/owners/" + ids[0])).doesNotContain("acceptance-unknown-vet");
	}

	@Test
	void sampleDataAssignsVetsToTheFirstAndThirdVisit() throws Exception {
		// owner 6 (Jean Coleman): visits 1 (2013-01-01) and 3 (2013-01-03) have vets
		String page = html("/owners/6");
		assertThat(rowContaining(page, "2013-01-01")).contains("Helen Leary");
		assertThat(rowContaining(page, "2013-01-03")).contains("Linda Douglas");
		assertNoVetName(rowContaining(page, "2013-01-02"));
		assertNoVetName(rowContaining(page, "2013-01-04"));
	}

	@Test
	void mysqlAndPostgresSchemasHaveANullableVetForeignKey() throws Exception {
		for (String db : List.of("mysql", "postgres")) {
			String visits = visitsStatements(Files.readString(Path.of("src/main/resources/db", db, "schema.sql")));
			assertThat(visits).as(db + " visits table has vet_id").contains("vet_id");
			assertThat(visits).as(db + " visits.vet_id references vets").containsPattern("references\\s+vets\\b");
			assertThat(visits).as(db + " visits.vet_id is nullable").doesNotContainPattern("vet_id[^,()]*not\\s+null");
		}
	}

	@Test
	void positionalVisitInsertsMatchTheNewColumnCount() throws Exception {
		for (String db : List.of("h2", "mysql", "postgres")) {
			String data = Files.readString(Path.of("src/main/resources/db", db, "data.sql"));
			Matcher insert = Pattern
				.compile("(?is)insert\\s+(?:ignore\\s+)?into\\s+visits\\s+values\\s*\\((.*?)\\)\\s*;")
				.matcher(data);
			while (insert.find()) {
				assertThat(topLevelValues(insert.group(1))).as(db + ": " + insert.group()).isEqualTo(5);
			}
		}
	}

	private int[] newOwnerWithPet(String lastName) throws Exception {
		String location = this.mockMvc
			.perform(post("/owners/new").param("firstName", "Accept")
				.param("lastName", lastName)
				.param("address", "1 Test Road")
				.param("city", "Testville")
				.param("telephone", "0123456789"))
			.andExpect(status().is3xxRedirection())
			.andReturn()
			.getResponse()
			.getRedirectedUrl();
		assertThat(location).isNotNull();
		int ownerId = Integer.parseInt(location.substring(location.lastIndexOf('/') + 1));
		this.mockMvc
			.perform(post("/owners/{ownerId}/pets/new", ownerId).param("name", "Pet" + lastName)
				.param("birthDate", "2020-01-01")
				.param("type", "cat"))
			.andExpect(status().is3xxRedirection());
		Matcher link = VISIT_LINK.matcher(html("/owners/" + ownerId));
		assertThat(link.find()).as("visit link on the owner page").isTrue();
		return new int[] { ownerId, Integer.parseInt(link.group(2)) };
	}

	private String html(String url) throws Exception {
		return this.mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private static String rowContaining(String html, String text) {
		int at = html.indexOf(text);
		assertThat(at).as("page contains " + text).isGreaterThanOrEqualTo(0);
		int start = html.lastIndexOf("<tr", at);
		int end = html.indexOf("</tr>", at);
		assertThat(start).isGreaterThanOrEqualTo(0);
		assertThat(end).isGreaterThan(at);
		return html.substring(start, end);
	}

	private static void assertNoVetName(String row) {
		for (String name : VET_NAMES) {
			assertThat(row).doesNotContain(name);
		}
	}

	private static String visitsStatements(String script) {
		StringBuilder result = new StringBuilder();
		Pattern visitsTable = Pattern
			.compile("(?is)^\\s*(create\\s+table(\\s+if\\s+not\\s+exists)?|alter\\s+table)\\s+visits\\b.*");
		for (String statement : script.split(";")) {
			if (visitsTable.matcher(statement).matches()) {
				result.append(statement.toLowerCase(Locale.ROOT)).append(";\n");
			}
		}
		return result.toString();
	}

	private static int topLevelValues(String values) {
		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (char c : values.toCharArray()) {
			if (c == '\'') {
				quoted = !quoted;
			}
			if (c == ',' && !quoted) {
				parts.add(current.toString());
				current.setLength(0);
			}
			else {
				current.append(c);
			}
		}
		parts.add(current.toString());
		return parts.size();
	}

}
