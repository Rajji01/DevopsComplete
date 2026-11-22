package com.shopflow.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FxController.class)
class FxControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void previewConvertsAtTheRateAndReturnsBothBalancedLegs() throws Exception {
        mockMvc.perform(post("/api/v1/fx/preview").contentType(MediaType.APPLICATION_JSON).content("""
                {"fromAccount":"CUSTOMER_USD","toAccount":"CASH_INR","amountMinor":10000,"base":"USD","quote":"INR","rate":83.50}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soldMinor").value(10000))       // 100.00 USD
                .andExpect(jsonPath("$.soldCurrency").value("USD"))
                .andExpect(jsonPath("$.boughtMinor").value(835000))    // 8350.00 INR
                .andExpect(jsonPath("$.boughtCurrency").value("INR"))
                .andExpect(jsonPath("$.sellLeg.length()").value(2))
                .andExpect(jsonPath("$.buyLeg.length()").value(2))
                .andExpect(jsonPath("$.sellLeg[0].currency").value("USD"))
                .andExpect(jsonPath("$.buyLeg[0].currency").value("INR"));
    }

    @Test
    void sameBaseAndQuoteIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/fx/preview").contentType(MediaType.APPLICATION_JSON).content("""
                {"fromAccount":"A","toAccount":"B","amountMinor":100,"base":"USD","quote":"USD","rate":1.0}"""))
                .andExpect(status().isBadRequest());
    }
}
