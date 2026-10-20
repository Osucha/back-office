package org.springframework.samples.petclinic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Hidden acceptance test for benchmark task 5: without any property the limit is 20
 * requests and one more every 3 seconds. Real time passes between the requests, so one
 * refilled token is tolerated.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RateLimitDefaultsAcceptanceTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void defaultCapacityIs20AndRetryAfterAtMost3Seconds() throws Exception {
		int firstLimited = -1;
		MockHttpServletResponse limited = null;
		for (int attempt = 1; attempt <= 30 && firstLimited < 0; attempt++) {
			MockHttpServletResponse response = this.mockMvc.perform(post("/owners/new").with(r -> {
				r.setRemoteAddr("10.2.2.2");
				return r;
			})).andReturn().getResponse();
			if (response.getStatus() == 429) {
				firstLimited = attempt;
				limited = response;
			}
		}
		assertThat(firstLimited).as("first 429 (20 allowed, one refill tolerated)").isBetween(21, 22);
		assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isBetween(1L, 3L);
	}

}
