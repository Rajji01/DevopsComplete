package com.shopflow.ledger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.shopflow.ledger.domain.JournalEntry;
import com.shopflow.ledger.domain.Money;

@WebMvcTest(FxController.class)
class FxControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LedgerService ledger; // preview is pure and never touches it; convert delegates to it

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

    @Test
    void convertDelegatesToTheLedgerAndReturnsTheEntryIds() throws Exception {
        JournalEntry sell = mock(JournalEntry.class);
        JournalEntry buy = mock(JournalEntry.class);
        when(sell.getId()).thenReturn(11L);
        when(buy.getId()).thenReturn(22L);
        when(ledger.convertCurrency(any(), any(), any(), any(), any(), any()))
                .thenReturn(new LedgerService.FxPosting(sell, buy, Money.of(10000, "USD"), Money.of(835000, "INR")));

        mockMvc.perform(post("/api/v1/fx/convert").contentType(MediaType.APPLICATION_JSON).content("""
                {"idempotencyKey":"k1","reference":"trade-1","fromAccount":"CUSTOMER_USD","toAccount":"CASH_INR","amountMinor":10000,"base":"USD","quote":"INR","rate":83.50}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellEntryId").value(11))
                .andExpect(jsonPath("$.buyEntryId").value(22))
                .andExpect(jsonPath("$.soldMinor").value(10000))
                .andExpect(jsonPath("$.boughtMinor").value(835000))
                .andExpect(jsonPath("$.boughtCurrency").value("INR"));
    }
}
