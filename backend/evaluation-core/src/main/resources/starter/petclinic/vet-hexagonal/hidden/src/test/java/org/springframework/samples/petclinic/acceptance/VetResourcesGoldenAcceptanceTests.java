package org.springframework.samples.petclinic.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Hidden acceptance tests for benchmark task 4 (vet module to ports and adapters): the
 * public behaviour must not change. The golden files were captured from the unchanged
 * application at the pinned commit; responses must match byte for byte. The "vets" cache
 * must still be filled by the vet pages.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VetResourcesGoldenAcceptanceTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CacheManager cacheManager;

	@Test
	void vetsJsonIsUnchanged() throws Exception {
		assertGolden(get("/vets").accept(MediaType.APPLICATION_JSON), "vets.json", "application/json");
	}

	@Test
	void vetsXmlIsUnchanged() throws Exception {
		assertGolden(get("/vets").accept(MediaType.APPLICATION_XML), "vets.xml", "application/xml");
	}

	@Test
	void vetsHtmlFirstPageIsUnchanged() throws Exception {
		assertGolden(get("/vets.html").param("page", "1"), "vets-page-1.html", "text/html;charset=UTF-8");
	}

	@Test
	void vetsHtmlSecondPageIsUnchanged() throws Exception {
		assertGolden(get("/vets.html").param("page", "2"), "vets-page-2.html", "text/html;charset=UTF-8");
	}

	@Test
	void vetsHtmlOutOfRangePageStillRedirects() throws Exception {
		MockHttpServletResponse response = this.mockMvc.perform(get("/vets.html").param("page", "3"))
			.andReturn()
			.getResponse();
		assertThat(response.getStatus()).isEqualTo(302);
		assertThat(response.getRedirectedUrl()).isEqualTo("/vets.html?page=1");
	}

	@Test
	void vetListsAreStillCachedInTheVetsCache() throws Exception {
		Cache cache = this.cacheManager.getCache("vets");
		assertThat(cache).as("cache named vets").isNotNull();

		cache.clear();
		this.mockMvc.perform(get("/vets").accept(MediaType.APPLICATION_JSON));
		assertThat(nativeEntries(cache)).as("entries in the vets cache after GET /vets").isPositive();

		cache.clear();
		this.mockMvc.perform(get("/vets.html").param("page", "1"));
		assertThat(nativeEntries(cache)).as("entries in the vets cache after GET /vets.html").isPositive();

		// served again (from the cache) with the same body
		assertGolden(get("/vets").accept(MediaType.APPLICATION_JSON), "vets.json", "application/json");
	}

	private void assertGolden(MockHttpServletRequestBuilder request, String golden, String contentType)
			throws Exception {
		MockHttpServletResponse response = this.mockMvc.perform(request).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentType()).isEqualTo(contentType);
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(read(golden));
	}

	private static String read(String golden) throws Exception {
		try (InputStream in = VetResourcesGoldenAcceptanceTests.class.getResourceAsStream("/acceptance/" + golden)) {
			assertThat(in).as("golden file " + golden).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Counts the entries of the native cache without depending on the cache provider
	 * (Caffeine in this application; JCache or a map with other configurations).
	 */
	private static int nativeEntries(Cache cache) throws Exception {
		Object nativeCache = cache.getNativeCache();
		if (nativeCache instanceof Map<?, ?> map) {
			return map.size();
		}
		if (nativeCache instanceof Iterable<?> iterable) {
			int entries = 0;
			for (Object ignored : iterable) {
				entries++;
			}
			return entries;
		}
		Method asMap = nativeCache.getClass().getMethod("asMap");
		asMap.setAccessible(true);
		return ((Map<?, ?>) asMap.invoke(nativeCache)).size();
	}

}
