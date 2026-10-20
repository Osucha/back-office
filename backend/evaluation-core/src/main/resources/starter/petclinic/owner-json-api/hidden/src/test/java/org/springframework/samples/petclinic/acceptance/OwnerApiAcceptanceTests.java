package org.springframework.samples.petclinic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hidden acceptance tests for the owner JSON API (benchmark task 1). Black box: the whole
 * application on H2, driven over MockMvc. Each test rolls back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OwnerApiAcceptanceTests {

	private static final Pattern LOCATION = Pattern.compile(".*/api/owners/(\\d+)$");

	private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

	@Autowired
	private MockMvc mockMvc;

	private static String body(String firstName, String lastName, String address, String city, String telephone) {
		return """
				{"firstName": %s, "lastName": %s, "address": %s, "city": %s, "telephone": %s}
				""".formatted(json(firstName), json(lastName), json(address), json(city), json(telephone));
	}

	private static String json(String value) {
		return value == null ? "null" : "\"" + value + "\"";
	}

	@Test
	void createReturns201WithLocationAndBodyAndCanBeReadBack() throws Exception {
		MvcResult created = this.mockMvc
			.perform(post("/api/owners").contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.content(body("Ada", "Acceptance", "1 Test Road", "Testville", "0123456789")))
			.andExpect(status().isCreated())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").isNumber())
			.andExpect(jsonPath("$.firstName").value("Ada"))
			.andExpect(jsonPath("$.lastName").value("Acceptance"))
			.andExpect(jsonPath("$.address").value("1 Test Road"))
			.andExpect(jsonPath("$.city").value("Testville"))
			.andExpect(jsonPath("$.telephone").value("0123456789"))
			.andExpect(jsonPath("$.pets").doesNotExist())
			.andReturn();

		String location = created.getResponse().getHeader("Location");
		assertThat(location).as("Location header").isNotNull();
		Matcher matcher = LOCATION.matcher(location);
		assertThat(matcher.matches()).as("Location ends with /api/owners/{id}: " + location).isTrue();
		String id = matcher.group(1);
		assertThat(created.getResponse().getContentAsString()).contains("\"id\":" + id);

		this.mockMvc.perform(get("/api/owners/{id}", id).accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(Integer.parseInt(id)))
			.andExpect(jsonPath("$.firstName").value("Ada"))
			.andExpect(jsonPath("$.lastName").value("Acceptance"))
			.andExpect(jsonPath("$.telephone").value("0123456789"))
			.andExpect(jsonPath("$.pets").doesNotExist());

		// the owner is a real owner: the existing HTML page shows it
		String page = this.mockMvc.perform(get("/owners/{id}", id))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("Ada Acceptance").contains("Testville");
	}

	@Test
	void getExistingOwnerFromSampleData() throws Exception {
		this.mockMvc.perform(get("/api/owners/1").accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.firstName").value("George"))
			.andExpect(jsonPath("$.lastName").value("Franklin"))
			.andExpect(jsonPath("$.address").value("110 W. Liberty St."))
			.andExpect(jsonPath("$.city").value("Madison"))
			.andExpect(jsonPath("$.telephone").value("6085551023"))
			.andExpect(jsonPath("$.pets").doesNotExist());
	}

	@Test
	void clientSuppliedIdIsIgnored() throws Exception {
		String request = """
				{"id": 1, "firstName": "Ida", "lastName": "Ignored", "address": "2 Test Road",
				 "city": "Testville", "telephone": "0123456789", "pets": []}
				""";
		this.mockMvc
			.perform(post("/api/owners").contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(not(1)));

		this.mockMvc.perform(get("/api/owners/1").accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.firstName").value("George"));
	}

	@Test
	void invalidOwnerAnswers400ProblemListingEachInvalidField() throws Exception {
		this.mockMvc
			.perform(post("/api/owners").contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON, PROBLEM)
				.content(body(null, "Valid", "1 Test Road", "", "12345")))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.title").isString())
			.andExpect(jsonPath("$.errors", hasSize(3)))
			.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("firstName", "city", "telephone")))
			.andExpect(jsonPath("$.errors[*].message", everyItem(not(emptyOrNullString()))));
	}

	@Test
	void tooLongValuesAreRejectedLikeTheEntity() throws Exception {
		this.mockMvc
			.perform(post("/api/owners").contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON, PROBLEM)
				.content(body("F".repeat(31), "Valid", "A".repeat(256), "C".repeat(81), "01234567890")))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("firstName", "address", "city", "telephone")));
	}

	@Test
	void unknownOwnerAnswers404Problem() throws Exception {
		this.mockMvc.perform(get("/api/owners/987654").accept(MediaType.APPLICATION_JSON, PROBLEM))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.status").value(404));
	}

}
