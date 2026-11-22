package com.shopflow.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.shopflow.ledger.domain.FxConversion;
import com.shopflow.ledger.domain.FxRate;
import com.shopflow.ledger.domain.Money;
import com.shopflow.ledger.domain.PostingDirection;
import com.shopflow.ledger.domain.PostingLine;

/**
 * A read-only "hit it live" surface for the FX domain primitives (Walks 148-151): POST a conversion and
 * see exactly how a cross-currency movement is booked — the converted amount and the TWO balanced
 * single-currency journal entries it becomes (base SELL leg + quote BUY leg through the FX clearing
 * accounts). It computes with {@link FxConversion}; it does NOT persist anything (that is the next
 * increment). Clearing accounts are derived as {@code FX_CLEARING:<ccy>}.
 *
 *   curl -XPOST :8085/api/v1/fx/preview -H 'Content-Type: application/json' -d \
 *     '{"fromAccount":"CUSTOMER_USD","toAccount":"CASH_INR","amountMinor":10000,"base":"USD","quote":"INR","rate":83.50}'
 *
 * {@code @Profile("!aws")}: a demo/teaching surface, present in dev + tests, not registered in prod
 * (same rule as the settlement demo controller, Walk 143).
 */
@RestController
@RequestMapping("/api/v1/fx")
@Profile("!aws")
public class FxController {

    public record PreviewRequest(
            @NotBlank @Size(max = 64) String fromAccount,
            @NotBlank @Size(max = 64) String toAccount,
            @Positive long amountMinor,
            @NotBlank @Size(min = 3, max = 3) String base,
            @NotBlank @Size(min = 3, max = 3) String quote,
            @NotNull @Positive BigDecimal rate) {
    }

    public record LegLine(String accountCode, PostingDirection direction, long minorUnits, String currency) {
        static LegLine from(PostingLine l) {
            return new LegLine(l.accountCode(), l.direction(), l.amount().minorUnits(), l.amount().currency());
        }
    }

    public record PreviewResponse(long soldMinor, String soldCurrency, long boughtMinor, String boughtCurrency,
                                  List<LegLine> sellLeg, List<LegLine> buyLeg) {
    }

    @PostMapping("/preview")
    public PreviewResponse preview(@Valid @RequestBody PreviewRequest req) {
        FxRate rate = new FxRate(req.base(), req.quote(), req.rate(), Instant.now());
        Money amount = Money.of(req.amountMinor(), req.base());
        FxConversion.Result r = FxConversion.convert(req.fromAccount(), req.toAccount(),
                "FX_CLEARING:" + req.base(), "FX_CLEARING:" + req.quote(), amount, rate);
        return new PreviewResponse(
                r.sold().minorUnits(), r.sold().currency(),
                r.bought().minorUnits(), r.bought().currency(),
                r.sellLeg().stream().map(LegLine::from).toList(),
                r.buyLeg().stream().map(LegLine::from).toList());
    }

    /** A degenerate rate / same base-and-quote (FxRate's own guards) is a bad request, not a 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(IllegalArgumentException e) {
        return e.getMessage();
    }
}
