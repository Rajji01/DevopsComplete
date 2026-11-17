package com.shopflow.ledger.settlement;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A REST surface so the broken-vs-fixed settlement behaviour can be hit LIVE with curl (not just unit
 * tests). Hand it accounts, then fire settlements at either consumer and watch the balances:
 *
 *   # set up
 *   curl -XPOST :8085/api/settlement/accounts -H 'Content-Type: application/json' -d '{"account":"ACC","balanceMinor":1000}'
 *   curl -XPOST :8085/api/settlement/accounts -H 'Content-Type: application/json' -d '{"account":"CP","balanceMinor":0}'
 *
 *   # BROKEN: settle once, then RESTART the pod and settle the SAME trade again -> ACC drops twice (S1).
 *   #         run two pods and fire the same trade at each -> twice (S2).
 *   #         fire a trade whose counterparty doesn't exist -> 409, but ACC already debited (S3 half-settle).
 *   curl -XPOST :8085/api/settlement/broken -H 'Content-Type: application/json' \
 *        -d '{"tradeId":"T1","account":"ACC","counterparty":"CP","amountMinor":100,"currency":"INR"}'
 *
 *   # FIXED: replay/restart the same trade -> applied once (DB remembers). Missing counterparty -> 409 and
 *   #        ACC UNCHANGED (atomic rollback).
 *   curl -XPOST :8085/api/settlement/fixed  -H 'Content-Type: application/json' -d '{...same body...}'
 *
 *   # REAL Kafka path: publish to the 'settlements' topic; the @KafkaListener (fixed) consumes it async.
 *   curl -XPOST :8085/api/settlement/publish -H 'Content-Type: application/json' -d '{...}'
 *
 *   curl :8085/api/settlement/accounts/ACC        # check the balance after each step
 */
@RestController
@RequestMapping("/api/settlement")
public class SettlementDemoController {

    private final BrokenSettlementConsumer broken;
    private final SettlementConsumer fixed;
    private final SettlementBalanceRepository balances;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public SettlementDemoController(BrokenSettlementConsumer broken, SettlementConsumer fixed,
                                    SettlementBalanceRepository balances, KafkaTemplate<String, String> kafka,
                                    ObjectMapper objectMapper) {
        this.broken = broken;
        this.fixed = fixed;
        this.balances = balances;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    public record AccountRequest(String account, long balanceMinor) {}

    @PostMapping("/accounts")
    public Map<String, Object> seed(@RequestBody AccountRequest request) {
        balances.save(new SettlementBalance(request.account(), request.balanceMinor()));
        return Map.of("account", request.account(), "balanceMinor", request.balanceMinor());
    }

    @GetMapping("/accounts/{account}")
    public ResponseEntity<Map<String, Object>> balance(@PathVariable String account) {
        return balances.findById(account)
                .<ResponseEntity<Map<String, Object>>>map(b -> ResponseEntity.ok(
                        Map.of("account", b.getAccount(), "balanceMinor", b.getBalanceMinor())))
                .orElse(ResponseEntity.notFound().build());
    }

    /** THE BUG, live. In-memory dedup (lost on restart, per-pod) + non-atomic debit/credit. */
    @PostMapping("/broken")
    public Map<String, Object> broken(@RequestBody SettlementEvent event) {
        broken.onMessage(event);
        return Map.of("consumer", "broken", "tradeId", event.tradeId(), "applied", true);
    }

    /** THE FIX, live. DB dedup + one transaction: idempotent across restarts/pods, atomic on failure. */
    @PostMapping("/fixed")
    public Map<String, Object> fixed(@RequestBody SettlementEvent event) {
        fixed.settle(event);
        return Map.of("consumer", "fixed", "tradeId", event.tradeId(), "applied", true);
    }

    /** The real Kafka path: publish to 'settlements'; the @KafkaListener (fixed) consumes it asynchronously. */
    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestBody SettlementEvent event) throws Exception {
        kafka.send("settlements", event.tradeId(), objectMapper.writeValueAsString(event));
        return Map.of("published", true, "topic", "settlements", "tradeId", event.tradeId(),
                "note", "async — GET /api/settlement/accounts/{account} in a moment to see it land");
    }

    // so a failed settlement is visible as a clear 409 (you then GET the balance to see broken=debited vs
    // fixed=unchanged) instead of a generic 500
    @ExceptionHandler(UnknownAccountException.class)
    public ResponseEntity<Map<String, Object>> onUnknownAccount(UnknownAccountException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", ex.getMessage(),
                        "hint", "broken leaves the debit applied (half-settle); fixed rolls it back"));
    }
}
