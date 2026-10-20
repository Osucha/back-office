package org.springframework.samples.petclinic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Hidden acceptance tests for benchmark task 5 over HTTP (MockMvc against the whole
 * application) with a small bucket: 2 requests, one more per hour. Every test uses its
 * own client IP. Form submissions are invalid on purpose so nothing is written.
 */
@SpringBootTest(properties = { "petclinic.rate-limit.capacity=2", "petclinic.rate-limit.refill-period=1h" })
@AutoConfigureMockMvc
class RateLimitHttpAcceptanceTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void thirdPostFromTheSameIpIsAnswered429WithRetryAfterInSeconds() throws Exception {
		assertThat(send(post("/owners/new"), "10.1.1.1").getStatus()).isEqualTo(200);
		assertThat(send(post("/owners/new"), "10.1.1.1").getStatus()).isEqualTo(200);

		MockHttpServletResponse limited = send(post("/owners/new"), "10.1.1.1");
		assertThat(limited.getStatus()).isEqualTo(429);
		String retryAfter = limited.getHeader("Retry-After");
		assertThat(retryAfter).as("Retry-After header").isNotNull().matches("\\d+");
		assertThat(Long.parseLong(retryAfter)).isBetween(3500L, 3600L);
	}

	@Test
	void getRequestsAreNeverLimited() throws Exception {
		send(post("/owners/new"), "10.1.1.2");
		send(post("/owners/new"), "10.1.1.2");
		assertThat(send(post("/owners/new"), "10.1.1.2").getStatus()).isEqualTo(429);

		for (int i = 0; i < 5; i++) {
			assertThat(send(get("/owners/new"), "10.1.1.2").getStatus()).isEqualTo(200);
			assertThat(send(get("/owners/find"), "10.1.1.2").getStatus()).isEqualTo(200);
			assertThat(send(get("/owners/1"), "10.1.1.2").getStatus()).isEqualTo(200);
		}
	}

	@Test
	void otherClientsKeepTheirOwnBudget() throws Exception {
		send(post("/owners/new"), "10.1.1.3");
		send(post("/owners/new"), "10.1.1.3");
		assertThat(send(post("/owners/new"), "10.1.1.3").getStatus()).isEqualTo(429);

		assertThat(send(post("/owners/new"), "10.1.1.4").getStatus()).isEqualTo(200);
	}

	@Test
	void allPostsUnderOwnersShareTheClientBucket() throws Exception {
		assertThat(send(post("/owners/new"), "10.1.1.5").getStatus()).isEqualTo(200);
		assertThat(send(post("/owners/1/edit").param("address", ""), "10.1.1.5").getStatus()).isEqualTo(200);
		assertThat(send(post("/owners/1/pets/new"), "10.1.1.5").getStatus()).isEqualTo(429);
		assertThat(send(post("/owners/1/pets/1/visits/new"), "10.1.1.5").getStatus()).isEqualTo(429);
	}

	@Test
	void postsOutsideOwnersAreNotLimited() throws Exception {
		for (int i = 0; i < 4; i++) {
			assertThat(send(post("/ownersearch"), "10.1.1.6").getStatus()).isNotEqualTo(429);
			assertThat(send(post("/vets.html"), "10.1.1.6").getStatus()).isNotEqualTo(429);
		}
		assertThat(send(post("/owners/new"), "10.1.1.6").getStatus()).isEqualTo(200);
	}

	private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String ip) throws Exception {
		return this.mockMvc.perform(request.with(r -> {
			r.setRemoteAddr(ip);
			return r;
		})).andReturn().getResponse();
	}

}
