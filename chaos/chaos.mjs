#!/usr/bin/env node
/**
 * Chaos test: bắn đơn hàng liên tục trong lúc kill -9 ngẫu nhiên các service, rồi đối soát chéo 3 database.
 * Chỉ cần Node 18+ và JDK (đã build jar: cd backend && ./mvnw -DskipTests package).
 *
 *   node chaos.mjs --mode OUTBOX                 # outbox + inbox
 *   node chaos.mjs --mode NAIVE                  # dual-write, không inbox
 *   node chaos.mjs --mode OUTBOX --no-chaos      # không kill gì (đối chứng)
 *   node chaos.mjs --mode OUTBOX --orders 600 --assert   # CI: lỗi => exit 1
 *   node chaos.mjs --mode OUTBOX --retry-budget 30 --redrive
 *        # relay bỏ cuộc sau 30s => message DEAD => đơn treo; rồi redrive DLQ và đối soát lại
 *
 * Kill bằng SIGKILL (= kill -9 / TerminateProcess): process chết ngay, không kịp dọn dẹp, không chạy shutdown hook.
 */
import { spawn, spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdirSync, openSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const SERVICES = {
  order: { port: 8091, jar: 'order-service' },
  inventory: { port: 8092, jar: 'inventory-service' },
  payment: { port: 8093, jar: 'payment-service' },
};
const SKUS = ['SKU-01', 'SKU-02', 'SKU-03', 'SKU-04', 'SKU-05', 'SKU-06', 'SKU-07', 'SKU-08', 'SKU-09', 'SKU-10'];
const TERMINAL = new Set(['CONFIRMED', 'REJECTED', 'CANCELLED']);

function parseArgs(argv) {
  const opts = {
    mode: 'OUTBOX',
    orders: 3000,
    rate: 40, // đơn / giây
    killEvery: 10, // giây giữa 2 lần kill (service khởi động mất ~10-20s khi máy đang tải nặng)
    down: 3, // giây nằm chết trước khi khởi động lại
    dupRate: 0.05, // 5% message bị mạng giao trùng
    drainTimeout: 120,
    chaos: true,
    assert: false,
    jvmOpts: '',
    retryBudget: 0, // >0: relay bỏ cuộc (DEAD) sau chừng ấy giây; 0 = mặc định của service (30 phút)
    redrive: false,
  };
  const map = {
    'kill-every': 'killEvery', 'dup-rate': 'dupRate', 'drain-timeout': 'drainTimeout', 'jvm-opts': 'jvmOpts',
    'retry-budget': 'retryBudget',
  };
  for (let i = 0; i < argv.length; i++) {
    const raw = argv[i].replace(/^--/, '');
    if (raw === 'no-chaos') { opts.chaos = false; continue; }
    if (raw === 'assert') { opts.assert = true; continue; }
    if (raw === 'redrive') { opts.redrive = true; continue; }
    const key = map[raw] ?? raw;
    const value = argv[++i];
    opts[key] = ['mode', 'jvmOpts'].includes(key) ? value : Number(value);
  }
  return opts;
}

const opts = parseArgs(process.argv.slice(2));
const runId = new Date().toISOString().replace(/[:.]/g, '-');
const logDir = join(here, 'logs', runId);
mkdirSync(logDir, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 19)}]`, ...a);

// ---------------------------------------------------------------- quản lý process

/**
 * Đường dẫn JVM THẬT. Trên Windows, `java` trong PATH thường là stub của Oracle (javapath\java.exe):
 * nó khởi chạy JVM thật thành TIẾN TRÌNH CON. Kill stub thì JVM vẫn sống => chaos test "giết" mà không ai chết
 * và báo 0 lỗi giả. Lấy java.home rồi chạy thẳng bin/java.
 */
function realJavaBinary() {
  // -XshowSettings in ra stderr
  const { stderr } = spawnSync('java', ['-XshowSettings:properties', '-version'], { encoding: 'utf8' });
  const match = /java\.home = (.+)/.exec(stderr ?? '');
  return match ? join(match[1].trim(), 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
}
let JAVA = 'java';

const procs = {}; // name -> ChildProcess
const kills = { order: 0, inventory: 0, payment: 0 };
let restartSeq = 0;

function start(name) {
  const svc = SERVICES[name];
  const jar = join(here, '..', 'backend', svc.jar, 'target', `${svc.jar}-0.0.1-SNAPSHOT.jar`);
  const out = openSync(join(logDir, `${name}-${restartSeq++}.log`), 'w');
  const args = [...opts.jvmOpts.split(' ').filter(Boolean), '-Xmx384m', '-jar', jar];
  procs[name] = spawn(JAVA, args, {
    env: {
      ...process.env,
      SAGA_MODE: opts.mode,
      SAGA_DUPLICATE_RATE: String(opts.dupRate),
      ...(opts.retryBudget > 0 ? { SAGA_RELAY_MAX_RETRY_SECONDS: String(opts.retryBudget) } : {}),
    },
    stdio: ['ignore', out, out],
  });
}

function kill9(name) {
  procs[name]?.kill('SIGKILL');
  procs[name] = null;
}

/** Xác nhận cú kill có tác dụng thật: cổng phải đóng. Không đóng => kill trượt => dừng test, không báo kết quả giả. */
async function assertDead(name) {
  for (let i = 0; i < 20; i++) {
    if (!(await healthy(name))) return;
    await sleep(100);
  }
  throw new Error(`${name} vẫn trả lời sau kill -9 — đang kill nhầm tiến trình (stub?)`);
}

async function healthy(name) {
  try {
    const res = await fetch(`http://localhost:${SERVICES[name].port}/actuator/health`, { signal: AbortSignal.timeout(1000) });
    return res.ok;
  } catch {
    return false;
  }
}

async function waitHealthy(name, timeoutMs = 90000) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    if (await healthy(name)) return true;
    await sleep(300);
  }
  throw new Error(`${name} did not become healthy`);
}

async function api(name, path, method = 'GET', body, headers = {}) {
  const res = await fetch(`http://localhost:${SERVICES[name].port}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...headers },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(10000),
  });
  if (!res.ok) throw new Error(`${name} ${path} -> HTTP ${res.status}`);
  return res.json();
}

// ---------------------------------------------------------------- tải + chaos

function randomOrder() {
  const n = 1 + Math.floor(Math.random() * 3);
  const picked = new Set();
  // 1/3 số đơn có hàng hiếm (SKU-01..03, mỗi loại chỉ 300 cái) => kho cạn giữa chừng => có đơn bị từ chối
  if (Math.random() < 0.33) picked.add(SKUS[Math.floor(Math.random() * 3)]);
  while (picked.size < n) picked.add(SKUS[3 + Math.floor(Math.random() * 7)]);
  return {
    customerId: 1 + Math.floor(Math.random() * 1000),
    items: [...picked].map((sku) => ({ sku, qty: 1 + Math.floor(Math.random() * 3) })),
  };
}

/** Như 1 app di động: lỗi mạng / 5xx thì gửi lại với CÙNG Idempotency-Key tới khi được. */
async function placeOrderWithRetry(order) {
  const key = randomUUID();
  for (let attempt = 1; attempt <= 120; attempt++) {
    try {
      const res = await fetch(`http://localhost:${SERVICES.order.port}/api/orders`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
        body: JSON.stringify(order),
        signal: AbortSignal.timeout(5000),
      });
      if (res.ok) return { ok: true, attempts: attempt };
    } catch {
      // service đang chết => thử lại
    }
    await sleep(1000);
  }
  return { ok: false };
}

/** @param onSubmitted gọi khi đã bắn hết đơn (chaos dừng ở đây; các đơn đang retry thì cứ retry tiếp) */
async function runLoad(onSubmitted) {
  const results = [];
  const interval = 1000 / opts.rate;
  const started = Date.now();
  for (let i = 0; i < opts.orders; i++) {
    results.push(placeOrderWithRetry(randomOrder()));
    const due = started + (i + 1) * interval;
    if (due > Date.now()) await sleep(due - Date.now());
  }
  onSubmitted();
  const settled = await Promise.all(results);
  return {
    accepted: settled.filter((r) => r.ok).length,
    clientRetries: settled.reduce((s, r) => s + (r.attempts ?? 1) - 1, 0),
  };
}

async function runChaos(signal) {
  while (!signal.stopped) {
    await sleep(opts.killEvery * 1000);
    if (signal.stopped) break;
    const alive = Object.keys(SERVICES).filter((n) => procs[n]);
    if (alive.length === 0) continue;
    const victim = alive[Math.floor(Math.random() * alive.length)];
    log(`💥 kill -9 ${victim}`);
    kill9(victim);
    kills[victim]++;
    await assertDead(victim);
    // Khởi động lại ở luồng riêng: chaos không đợi, có thể giết service khác trong lúc này.
    (async () => {
      await sleep(opts.down * 1000);
      start(victim);
      await waitHealthy(victim).catch(() => {});
      log(`↺ ${victim} back`);
    })();
  }
}

// ---------------------------------------------------------------- ráo hàng đợi + đối soát

async function drain() {
  const started = Date.now();
  let last = '';
  let lastChange = Date.now();
  while ((Date.now() - started) / 1000 < opts.drainTimeout) {
    try {
      const summary = await api('order', '/api/admin/orders/summary');
      const pending = await Promise.all(Object.keys(SERVICES).map((n) => api(n, '/api/admin/messaging')));
      const outboxPending = pending.reduce((s, m) => s + m.outboxPending, 0);
      const fingerprint = `${summary.stuck}/${outboxPending}`;
      if (summary.stuck === 0 && outboxPending === 0) return (Date.now() - started) / 1000;
      if (fingerprint !== last) {
        last = fingerprint;
        lastChange = Date.now();
      }
      // NAIVE: message mất rồi thì không bao giờ "ráo" => dừng khi 15s không còn gì thay đổi.
      if (Date.now() - lastChange > 15000) return (Date.now() - started) / 1000;
    } catch {
      // service vừa khởi động lại
    }
    await sleep(1000);
  }
  return opts.drainTimeout;
}

async function reconcile() {
  const [orders, reservations, payments, stock, money, summary] = await Promise.all([
    api('order', '/api/admin/orders/states'),
    api('inventory', '/api/admin/reservations'),
    api('payment', '/api/admin/payments'),
    api('inventory', '/api/admin/inventory'),
    api('payment', '/api/admin/money'),
    api('order', '/api/admin/orders/summary'),
  ]);
  const messaging = Object.fromEntries(
    await Promise.all(Object.keys(SERVICES).map(async (n) => [n, await api(n, '/api/admin/messaging')])),
  );
  const resv = new Map(reservations.map((r) => [Number(r.orderId), r.status]));
  const pay = new Map(payments.map((p) => [Number(p.orderId), p]));

  const anomalies = {
    stuckOrders: 0, // đơn treo, không bao giờ xong
    chargedNotConfirmed: 0, // khách bị trừ tiền nhưng đơn không thành công
    confirmedNotCharged: 0, // giao hàng mà chưa thu tiền
    stockHeld: 0, // hàng bị giữ chỗ mãi mãi (không bán, không trả kho)
    confirmedStockNotFinal: 0, // đơn xong nhưng kho chưa chốt
    missingEmails: 0,
    duplicateEmails: 0,
  };
  for (const o of orders) {
    const id = Number(o.orderId);
    const r = resv.get(id);
    const p = pay.get(id);
    const charged = p?.status === 'CHARGED';
    if (!TERMINAL.has(o.status)) anomalies.stuckOrders++;
    if (charged && o.status !== 'CONFIRMED') anomalies.chargedNotConfirmed++;
    if (o.status === 'CONFIRMED') {
      if (!charged || Number(p.amount) !== Number(o.totalAmount)) anomalies.confirmedNotCharged++;
      if (r !== 'CONFIRMED') anomalies.confirmedStockNotFinal++;
      if (Number(o.emails) === 0) anomalies.missingEmails++;
    }
    // Sau khi ráo hàng đợi, reservation còn RESERVED = hàng bị giữ chết: không bán được, không về kho.
    if (r === 'RESERVED') anomalies.stockHeld++;
    anomalies.duplicateEmails += Math.max(0, Number(o.emails) - 1);
  }
  const byStatus = summary.byStatus;
  const totalAnomalies = Object.values(anomalies).reduce((a, b) => a + b, 0)
    + (stock.consistent ? 0 : 1) + (money.consistent ? 0 : 1);
  return {
    orders: orders.length,
    byStatus,
    anomalies,
    totalAnomalies,
    stockInvariant: stock.consistent,
    moneyInvariant: money.consistent,
    duplicatesSuppressed: Object.values(messaging).reduce((s, m) => s + m.duplicatesSuppressed, 0),
    deadLetters: Object.values(messaging).reduce((s, m) => s + m.outboxDead, 0),
    naiveSendFailures: Object.values(messaging).reduce((s, m) => s + m.naiveSendFailures, 0),
    staleEventsIgnored: summary.staleEventsIgnored,
  };
}

// ---------------------------------------------------------------- main

async function main() {
  JAVA = realJavaBinary();
  log(`java=${JAVA}`);
  log(`mode=${opts.mode} orders=${opts.orders} rate=${opts.rate}/s chaos=${opts.chaos} killEvery=${opts.killEvery}s down=${opts.down}s dupRate=${opts.dupRate}`);
  for (const name of Object.keys(SERVICES)) {
    if (await healthy(name)) throw new Error(`Port ${SERVICES[name].port} đang bận — tắt service đang chạy trước`);
  }
  Object.keys(SERVICES).forEach(start);
  await Promise.all(Object.keys(SERVICES).map((n) => waitHealthy(n)));
  await Promise.all([
    api('order', '/api/admin/orders/reset', 'POST'),
    api('inventory', '/api/admin/reset', 'POST'),
    api('payment', '/api/admin/reset', 'POST'),
  ]);
  log('services up, data reset');

  const chaosSignal = { stopped: false };
  const chaos = opts.chaos ? runChaos(chaosSignal) : Promise.resolve();
  const loadStarted = Date.now();
  const load = await runLoad(() => {
    chaosSignal.stopped = true;
    log('all orders submitted, chaos stopped');
  });
  await chaos;
  log(`load done in ${((Date.now() - loadStarted) / 1000).toFixed(0)}s, accepted ${load.accepted}/${opts.orders}`);

  // Bảo đảm mọi service đều sống trước khi ráo hàng đợi.
  for (const name of Object.keys(SERVICES)) {
    if (!procs[name]) start(name);
  }
  await Promise.all(Object.keys(SERVICES).map((n) => waitHealthy(n)));
  const drainSec = await drain();
  log(`drained in ${drainSec.toFixed(0)}s`);

  const report = { opts, kills, load, drainSec, ...(await reconcile()) };

  if (opts.redrive && report.deadLetters > 0) {
    // Sự cố đã qua: đưa toàn bộ dead letter về hàng đợi rồi đối soát lại.
    const redriven = await Promise.all(Object.keys(SERVICES).map((n) => api(n, '/api/admin/messaging/dead/redrive-all', 'POST')));
    log(`redrive: ${redriven.map((r) => r.redriven).join(' + ')} messages`);
    const drainAfter = await drain();
    report.afterRedrive = { drainSec: drainAfter, ...(await reconcile()) };
  }
  console.log('\n' + JSON.stringify(report, null, 2));

  mkdirSync(join(here, 'results'), { recursive: true });
  const file = join(here, 'results', `${opts.mode}-${opts.chaos ? 'chaos' : 'calm'}-${runId}.json`);
  writeFileSync(file, JSON.stringify(report, null, 2));
  log(`saved ${file}`);
  return report;
}

let exitCode = 0;
try {
  const report = await main();
  if (opts.assert && report.totalAnomalies > 0) {
    console.error(`❌ ${report.totalAnomalies} anomalies`);
    exitCode = 1;
  }
} catch (e) {
  console.error(e);
  exitCode = 2;
} finally {
  Object.keys(SERVICES).forEach(kill9);
}
process.exit(exitCode);
