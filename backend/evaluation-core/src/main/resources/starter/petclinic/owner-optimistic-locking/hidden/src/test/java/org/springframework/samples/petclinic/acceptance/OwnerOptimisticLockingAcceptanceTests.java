package org.springframework.samples.petclinic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Hidden acceptance tests for benchmark task 3 (optimistic locking of owner edits). Black
 * box over MockMvc against the whole application on H2: every request runs in its own
 * transaction, like two receptionists with two browsers. Each test creates its own owner.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OwnerOptimisticLockingAcceptanceTests {

	private static final String STALE_MESSAGE = "This owner was changed by someone else. Reload the page and apply your changes again.";

	private static final Pattern VERSION_INPUT = Pattern.compile("<input[^>]*name=\"version\"[^>]*>");

	private static final Pattern VALUE = Pattern.compile("value=\"(\\d*)\"");

	@Autowired
	private MockMvc mockMvc;

	@Test
	void editFormCarriesTheVersionOfTheSampleOwnersStartingAtZero() throws Exception {
		assertThat(versionInEditForm(8)).isEqualTo(0);
		assertThat(versionInEditForm(10)).isEqualTo(0);
	}

	@Test
	void staleSaveIsRejectedOnTheFormAndChangesNothing() throws Exception {
		int id = createOwner("Stale");
		int loaded = versionInEditForm(id);

		// receptionist A and B both opened the form with version "loaded"; A saves first
		this.mockMvc.perform(edit(id, loaded, "Alice City"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/owners/" + id));
		assertThat(versionInEditForm(id)).isGreaterThan(loaded);

		// B saves on top of the stale version: the form is shown again with the error
		String page = this.mockMvc.perform(edit(id, loaded, "Bob City"))
			.andExpect(status().isOk())
			.andExpect(view().name("owners/createOrUpdateOwnerForm"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains(STALE_MESSAGE);

		String details = html("/owners/" + id);
		assertThat(details).contains("Alice City").doesNotContain("Bob City");
	}

	@Test
	void afterReloadingTheFormTheSaveSucceeds() throws Exception {
		int id = createOwner("Reload");
		int loaded = versionInEditForm(id);
		this.mockMvc.perform(edit(id, loaded, "First City")).andExpect(status().is3xxRedirection());
		this.mockMvc.perform(edit(id, loaded, "Lost City")).andExpect(status().isOk());

		int reloaded = versionInEditForm(id);
		this.mockMvc.perform(edit(id, reloaded, "Second City"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/owners/" + id));
		assertThat(html("/owners/" + id)).contains("Second City");
		assertThat(versionInEditForm(id)).isGreaterThan(reloaded);
	}

	@Test
	void everyStaleVersionIsRejectedNotOnlyTheFirst() throws Exception {
		int id = createOwner("Older");
		int v0 = versionInEditForm(id);
		this.mockMvc.perform(edit(id, v0, "One")).andExpect(status().is3xxRedirection());
		int v1 = versionInEditForm(id);
		this.mockMvc.perform(edit(id, v1, "Two")).andExpect(status().is3xxRedirection());

		String page = this.mockMvc.perform(edit(id, v1, "Three"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains(STALE_MESSAGE);
		this.mockMvc.perform(edit(id, v0, "Zero")).andExpect(status().isOk());
		assertThat(html("/owners/" + id)).contains("Two").doesNotContain("Three").doesNotContain("Zero");
	}

	@Test
	void creatingAnOwnerStillWorksAndStartsAVersion() throws Exception {
		int id = createOwner("Created");
		assertThat(versionInEditForm(id)).isGreaterThanOrEqualTo(0);
	}

	@Test
	void mysqlAndPostgresOwnersHaveAVersionColumnDefaultingToZero() throws Exception {
		for (String db : List.of("mysql", "postgres")) {
			String owners = ownersStatements(Files.readString(Path.of("src/main/resources/db", db, "schema.sql")));
			assertThat(owners).as(db + " owners.version").containsPattern("\\bversion\\b[^,]*default\\s+0");
		}
	}

	@Test
	void positionalOwnerInsertsMatchTheNewColumnCount() throws Exception {
		for (String db : List.of("h2", "mysql", "postgres")) {
			String data = Files.readString(Path.of("src/main/resources/db", db, "data.sql"));
			Matcher insert = Pattern
				.compile("(?is)insert\\s+(?:ignore\\s+)?into\\s+owners\\s+values\\s*\\((.*?)\\)\\s*;")
				.matcher(data);
			while (insert.find()) {
				assertThat(topLevelValues(insert.group(1))).as(db + ": " + insert.group()).isEqualTo(7);
			}
		}
	}

	private int createOwner(String lastName) throws Exception {
		String location = this.mockMvc
			.perform(post("/owners/new").param("firstName", "Lock")
				.param("lastName", lastName)
				.param("address", "1 Race Street")
				.param("city", "Start City")
				.param("telephone", "0123456789"))
			.andExpect(status().is3xxRedirection())
			.andReturn()
			.getResponse()
			.getRedirectedUrl();
		assertThat(location).isNotNull();
		return Integer.parseInt(location.substring(location.lastIndexOf('/') + 1));
	}

	private int versionInEditForm(int ownerId) throws Exception {
		String form = html("/owners/" + ownerId + "/edit");
		Matcher input = VERSION_INPUT.matcher(form);
		assertThat(input.find()).as("hidden input named version on the edit form").isTrue();
		assertThat(input.group()).contains("type=\"hidden\"");
		Matcher value = VALUE.matcher(input.group());
		assertThat(value.find()).as("version value in " + input.group()).isTrue();
		assertThat(value.group(1)).as("version value in " + input.group()).isNotEmpty();
		return Integer.parseInt(value.group(1));
	}

	private MockHttpServletRequestBuilder edit(int ownerId, int version, String city) {
		return post("/owners/{ownerId}/edit", ownerId).param("firstName", "Lock")
			.param("lastName", "Owner")
			.param("address", "1 Race Street")
			.param("city", city)
			.param("telephone", "0123456789")
			.param("version", Integer.toString(version));
	}

	private String html(String url) throws Exception {
		return this.mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private static String ownersStatements(String script) {
		StringBuilder result = new StringBuilder();
		Pattern ownersTable = Pattern
			.compile("(?is)^\\s*(create\\s+table(\\s+if\\s+not\\s+exists)?|alter\\s+table)\\s+owners\\b.*");
		for (String statement : script.split(";")) {
			if (ownersTable.matcher(statement).matches()) {
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
