package com.helper.microservice01;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"testing.property=test-value", "cache.file.path=/does/not/exist.txt"})
@AutoConfigureMockMvc
class Microservice01ApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void m2ReturnsInjectedProperty() throws Exception {
		mockMvc.perform(get("/api2/m2"))
				.andExpect(status().isOk())
				.andExpect(content().string("test-value it's mine now response"));
	}

	@Test
	void readFileReturns404WhenFileMissing() throws Exception {
		mockMvc.perform(get("/api2/readFile"))
				.andExpect(status().isNotFound());
	}

}
