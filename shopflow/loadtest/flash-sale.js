// k6 load test: 300 virtual buyers rush the 3 PS5 units, while others browse products.
//   docker compose up -d && TOKEN=$(...) k6 run -e TOKEN=$TOKEN loadtest/flash-sale.js
// Pass/fail is encoded in thresholds, so this can gate a CI job.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const confirmed = new Counter('orders_confirmed');
const rejected = new Counter('orders_rejected');
const throttled = new Counter('orders_throttled');
const orderLatency = new Trend('order_latency', true);

export const options = {
  scenarios: {
    buyers: {                      // the flash sale itself
      executor: 'shared-iterations',
      vus: 300,
      iterations: 300,
      maxDuration: '1m',
      exec: 'buy',
    },
    browsers: {                    // background read traffic hitting the cache
      executor: 'constant-arrival-rate',
      rate: 200, timeUnit: '1s', duration: '30s', preAllocatedVUs: 50,
      exec: 'browse',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],          // 5xx are failures; 409/429 are not (see check below)
    'http_req_duration{scenario:browse}': ['p(99)<300'],
    'order_latency': ['p(95)<800'],
    'orders_confirmed': ['count==3'],        // exactly the stock, never more: the whole point
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const INVENTORY = __ENV.INVENTORY_URL || 'http://localhost:8082';
const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${__ENV.TOKEN}` };

export function buy() {
  const res = http.post(`${BASE}/api/v1/orders`, JSON.stringify({ sku: 'PS5-SLIM', quantity: 1 }),
    { headers, tags: { name: 'POST /orders' } });
  orderLatency.add(res.timings.duration);
  if (res.status === 201) {
    const status = res.json('status');
    if (status === 'CONFIRMED') confirmed.add(1);
    if (status === 'REJECTED') rejected.add(1);
  } else if (res.status === 429) {
    throttled.add(1);
  }
  check(res, { 'no server error': (r) => r.status < 500 });
}

export function browse() {
  const res = http.get(`${INVENTORY}/api/v1/products/PS5-SLIM`, { tags: { name: 'GET /products/{sku}' } });
  check(res, { 'product readable': (r) => r.status === 200 });
  sleep(0.1);
}

export function handleSummary(data) {
  const c = data.metrics.orders_confirmed ? data.metrics.orders_confirmed.values.count : 0;
  const r = data.metrics.orders_rejected ? data.metrics.orders_rejected.values.count : 0;
  const t = data.metrics.orders_throttled ? data.metrics.orders_throttled.values.count : 0;
  return { stdout: `\nconfirmed=${c} rejected=${r} throttled=${t}  (expected: confirmed==3)\n` };
}
