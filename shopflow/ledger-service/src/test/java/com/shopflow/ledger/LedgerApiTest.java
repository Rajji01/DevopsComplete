package com.shopflow.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LedgerApiTest {

    @Autowired
    private MockMvc mockMvc;

    private String createAsset() throws Exception {
        return createAccount("CASH-" + UUID.randomUUID(), "ASSET");
    }

    private String createLiability() throws Exception {
        return createAccount("WALLET-" + UUID.randomUUID(), "LIABILITY");
    }

    private String createAccount(String code, String type) throws Exception {
        String body = """
                {"code":"%s","type":"%s","currency":"INR"}""".formatted(code, type);
        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return code;
    }

    private String entryBody(String key, String ref, String debitAcct, String creditAcct, long debit, long credit) {
        return """
                {"idempotencyKey":"%s","reference":"%s","currency":"INR","lines":[
                  {"accountCode":"%s","direction":"DEBIT","amountMinor":%d},
                  {"accountCode":"%s","direction":"CREDIT","amountMinor":%d}]}"""
                .formatted(key, ref, debitAcct, debit, creditAcct, credit);
    }

    @Test
    void postABalancedEntryAndReadTheDerivedBalances() throws Exception {
        String cash = createAsset();
        String wallet = createLiability();
        String ref = "order-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/journal-entries").contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody(UUID.randomUUID().toString(), ref, cash, wallet, 1000, 1000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INITIATED"));

        mockMvc.perform(get("/api/v1/accounts/{code}/balance", cash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minorUnits").value(1000));
        mockMvc.perform(get("/api/v1/accounts/{code}/balance", wallet))
                .andExpect(jsonPath("$.minorUnits").value(-1000));
    }

    @Test
    void replayingTheSameKeyPostsOnce() throws Exception {
        String cash = createAsset();
        String wallet = createLiability();
        String ref = "order-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String body = entryBody(key, ref, cash, wallet, 500, 500);

        mockMvc.perform(post("/api/v1/journal-entries").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/journal-entries").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());   // idempotent: safe to retry

        mockMvc.perform(get("/api/v1/journal-entries").param("reference", ref))
                .andExpect(jsonPath("$.length()").value(1));            // posted once, not twice
        mockMvc.perform(get("/api/v1/accounts/{code}/balance", cash))
                .andExpect(jsonPath("$.minorUnits").value(500));
    }

    @Test
    void anUnbalancedEntryIs422() throws Exception {
        String cash = createAsset();
        String wallet = createLiability();
        mockMvc.perform(post("/api/v1/journal-entries").contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody(UUID.randomUUID().toString(), "r", cash, wallet, 1000, 999)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Unbalanced entry"));
    }

    @Test
    void postingToAnUnknownAccountIs422() throws Exception {
        String cash = createAsset();
        mockMvc.perform(post("/api/v1/journal-entries").contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody(UUID.randomUUID().toString(), "r", cash, "NO-SUCH-ACCOUNT", 1000, 1000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Unknown account"));
    }

    @Test
    void aMalformedRequestIs400() throws Exception {
        // empty lines + blank currency -> bean validation -> 400
        String bad = """
                {"idempotencyKey":"k","reference":"r","currency":"","lines":[]}""";
        mockMvc.perform(post("/api/v1/journal-entries").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }
}
