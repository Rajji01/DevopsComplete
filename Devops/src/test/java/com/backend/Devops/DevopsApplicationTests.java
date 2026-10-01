package com.backend.Devops;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(properties = {"testing.property=test-value", "host.port=1234"})
@AutoConfigureMockMvc
class DevopsApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private RestTemplate restTemplate;

	@Test
	void m1ReturnsInjectedProperty() throws Exception {
		mockMvc.perform(get("/api/m1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("test-value")));
	}

	@Test
	void m3ProxiesHelperService() throws Exception {
		when(restTemplate.getForObject(eq("http://helper-service:8088/api2/m2"), eq(String.class)))
				.thenReturn("helper says hi");

		mockMvc.perform(get("/api/m3"))
				.andExpect(status().isOk())
				.andExpect(content().string("helper says hi"));
	}

	@Test
	void m3Returns503WhenHelperIsDown() throws Exception {
		when(restTemplate.getForObject(anyString(), eq(String.class)))
				.thenThrow(new ResourceAccessException("connection refused"));

		mockMvc.perform(get("/api/m3"))
				.andExpect(status().isServiceUnavailable());
	}

	@Test
	void m4ReturnsHostPortFromConfig() throws Exception {
		mockMvc.perform(get("/api/m4"))
				.andExpect(status().isOk())
				.andExpect(content().string("host port is = 1234"));
	}

}
