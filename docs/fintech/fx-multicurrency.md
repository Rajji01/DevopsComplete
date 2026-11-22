# FX / multi-currency in the ledger — how it works + hit it live

> Ye note `ledger-service` ki FX capability (fintech inc-4c) explain karta hai — kaunse 4 primitives, har ek **kyun** hai (interview answer), aur **curl se live kaise chalao**. Code: `shopflow/ledger-service/src/main/java/com/shopflow/ledger/domain/{Money,FxRate,FxConversion}.java` + `LedgerService.convertCurrency` + `FxController`. Walks 148–154.

## The problem (ek line mein)
Double-entry ledger ka core invariant: **ek journal entry ke debits == credits, aur sirf ek currency ke andar meaningful hai** (`DoubleEntry.validate` mixed-currency legs reject karta). To ek cross-currency trade (USD aaya, INR mein settle hua) **ek entry nahi ban sakti**. FX is problem ko solve karta hai — invariant ko toda nahi, uske around model banaya.

## 4 primitives, har ek ka "kyun"

**1. `Money` — currency ka asli minor-unit exponent (Walk 148).**
`Money(minorUnits, currency)` — paisa/cent integer mein, kabhi float nahi. `fromMajor("749.99", "INR")` → 74999 paise. Trick: scale **currency se** aata hai, hardcoded 2 nahi — `minorUnitDigits`: INR/USD=2, **JPY/KRW=0** (yen ka sub-unit hi nahi), **BHD/KWD=3** (fils).
- *Interview Q: "500 JPY ko minor units mein kaise store karoge?"* → 500, 50000 nahi. 2 hardcode karte to JPY **100x** galat hota — aur kyunki double-entry ke dono legs same factor se inflate hote, `sum==0` invariant **pakad bhi nahi paata** → pehle foreign-currency trade tak invisible. Wrong-but-consistent scale sabse khatarnak kism ka bug hai.

**2. `FxRate` — captured rate (Walk 149).**
`FxRate(base, quote, rate, capturedAt)`. `apply(Money)` base→quote convert karta, rounding **ek baar**, quote currency ke minor unit mein (HALF_EVEN).
- *Interview Q: "FX rate kab lete ho — trade time ya settlement time?"* → **Trade ke saath capture karo + timestamp store karo.** Rate har second hilta hai; settlement pe dobara fetch kiya to alag number aayega aur jo customer ko quote kiya tha usse **tie-out nahi hoga**. Captured rate = reproducible + auditable = recon aur reg-reporting ki zaroorat. `FxRate` degenerate rate (same base/quote, non-positive, no timestamp) ko **construction pe hi** reject karta.

**3. `FxConversion` — cross-currency = do balanced single-currency entries (Walk 150–151).**
Ek conversion = **do** entries, ek per-currency `FX_CLEARING` account se joined:
- SELL leg (base): `CR fromAccount`, `DR FX_CLEARING:<base>` — base amount source se nikalke clearing mein.
- BUY leg (quote): `DR toAccount`, `CR FX_CLEARING:<quote>` — converted amount clearing se target mein.
Har leg single-currency + sum-zero → har ek `DoubleEntry.validate` alag se pass karti. Clearing **per-currency** (Walk 151 fix: `LedgerService` account.currency==entry.currency enforce karta, to ek shared clearing dono currencies hold nahi kar sakta). Clearing +base/-quote hold karta = **book ka FX position**.
- *Interview Q: "Mixed-currency transaction double-entry mein kaise?"* → Ek entry mein nahi — do single-currency entries + clearing account. Invariant weaken mat karo (warna "sum of all balances == 0" property, jis pe pura recon tika hai, tutt jaati).

**4. `convertCurrency` — atomic persist (Walk 153).**
`LedgerService.convertCurrency` dono entries **ek hi transaction** mein likhta → all-or-nothing, koi "half-conversion window" nahi (base nikla par quote aaya hi nahi — wo window sirf do alag `record()` calls mein hota). Idempotent `:fx-sell`/`:fx-buy` key pe (race-recovery transaction ke bahar, Walk-99 structure). `FX_CLEARING:<ccy>` accounts first-use pe auto-ensure (currency se derived, user-input nahi → typo-risk nahi).

## Hit it live (ledger `:8085`, dev/compose — `@Profile("!aws")`)

```bash
# 1. do business accounts banao (FX_CLEARING:* khud ban jaate)
curl -XPOST :8085/api/v1/accounts -H 'Content-Type: application/json' -d '{"code":"CUSTOMER_USD","type":"ASSET","currency":"USD"}'
curl -XPOST :8085/api/v1/accounts -H 'Content-Type: application/json' -d '{"code":"CASH_INR","type":"ASSET","currency":"INR"}'

# 2. DRY RUN — preview (kuch persist nahi): 100.00 USD @ 83.50 -> dono balanced legs dikhata
curl -XPOST :8085/api/v1/fx/preview -H 'Content-Type: application/json' -d \
  '{"fromAccount":"CUSTOMER_USD","toAccount":"CASH_INR","amountMinor":10000,"base":"USD","quote":"INR","rate":83.50}'
#   -> soldMinor 10000 USD, boughtMinor 835000 INR, sellLeg[CR CUSTOMER_USD / DR FX_CLEARING:USD], buyLeg[DR CASH_INR / CR FX_CLEARING:INR]

# 3. REAL booking — convert (persist, idempotent on idempotencyKey)
curl -XPOST :8085/api/v1/fx/convert -H 'Content-Type: application/json' -d \
  '{"idempotencyKey":"k1","reference":"trade-1","fromAccount":"CUSTOMER_USD","toAccount":"CASH_INR","amountMinor":10000,"base":"USD","quote":"INR","rate":83.50}'

# 4. balances check — dono currencies hile, clearing position carry karta
curl :8085/api/v1/accounts/CUSTOMER_USD/balance      # -10000 USD  (CR: USD nikla)
curl :8085/api/v1/accounts/FX_CLEARING:USD/balance   # +10000 USD  (DR: base clearing)
curl :8085/api/v1/accounts/CASH_INR/balance          # +835000 INR (DR: INR aaya)
curl :8085/api/v1/accounts/FX_CLEARING:INR/balance   # -835000 INR (CR: quote clearing)

# 5. replay same idempotencyKey -> double-post NAHI (balances same rehte)
curl -XPOST :8085/api/v1/fx/convert -H 'Content-Type: application/json' -d \
  '{"idempotencyKey":"k1","reference":"trade-1","fromAccount":"CUSTOMER_USD","toAccount":"CASH_INR","amountMinor":10000,"base":"USD","quote":"INR","rate":83.50}'
```

Degenerate input saaf fail karta: same base/quote ya non-positive rate → **400**; aisa business account jo bana hi nahi → **404** (500 nahi).

## Ab kya baaki (alag concepts, loose ends nahi)
- **Realised FX gain/loss**: jab `FX_CLEARING` position ek naye rate pe square-off hoti, booked-vs-squareoff ka difference = realized P&L (ek `FX_GAIN_LOSS` account mein). Ye alag operation hai (position close karna), is conversion ka hissa nahi.
- **Delayed capture**: authorize ab, capture dispatch pe (payment-service state machine — auth≠capture already guarded, Walk 114).
