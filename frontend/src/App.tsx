import { useEffect, useState } from 'react'
import { api, SERVICES, SKUS, vnd, type MessagingStats, type Order, type OrderSummary, type ServiceName } from './api'

const POLL_INTERVAL_MS = 1000

/** Gọi fn mỗi giây; lỗi (service đang chết) => trả null để UI hiện "DOWN". */
function usePolling<T>(fn: () => Promise<T>): T | null {
  const [data, setData] = useState<T | null>(null)
  useEffect(() => {
    let cancelled = false
    const tick = () =>
      fn()
        .then((d) => !cancelled && setData(d))
        .catch(() => !cancelled && setData(null))
    tick()
    const timer = setInterval(tick, POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
    // fn là hàm tĩnh từ module api, không đổi giữa các lần render
  }, [])
  return data
}

export default function App() {
  return (
    <main className="page">
      <header>
        <h1>Order Saga Lab</h1>
        <p className="muted">
          Đặt hàng đi qua 3 service (order → inventory → payment). Kill thử 1 service rồi bật lại: đơn phải tự đi tiếp,
          không mất, không trừ tiền 2 lần.
        </p>
      </header>
      <div className="services">
        {SERVICES.map((s) => (
          <ServiceCard key={s} name={s} />
        ))}
      </div>
      <SummaryPanel />
      <div className="grid">
        <OrderForm />
        <section className="card">
          <h2>Chaos test</h2>
          <p className="muted">
            Tắt các service đang chạy, rồi mở terminal ở thư mục <code>03-order-saga/chaos</code>:
          </p>
          <pre>node chaos.mjs --mode OUTBOX{'\n'}node chaos.mjs --mode NAIVE</pre>
        </section>
      </div>
      <RecentOrders />
    </main>
  )
}

function ServiceCard({ name }: { name: ServiceName }) {
  const stats = usePolling<MessagingStats>(() => api.messaging(name))
  return (
    <section className="card">
      <div className="row space-between">
        <h2>{name}</h2>
        <span className={`badge ${stats ? 'ok' : 'bad'}`}>{stats ? `UP · ${stats.mode}` : 'DOWN'}</span>
      </div>
      {stats && (
        <dl className="stats compact">
          <Stat label="Outbox chờ gửi" value={stats.outboxPending} />
          <Stat label="Đã gửi" value={stats.outboxSent} />
          <Stat label="Dead letter" value={stats.outboxDead} danger={stats.outboxDead > 0} />
          <Stat label="Inbox đã xử lý" value={stats.inboxProcessed} />
          <Stat label="Trùng bị chặn" value={stats.duplicatesSuppressed} />
          {stats.mode === 'NAIVE' && (
            <Stat label="Message mất (NAIVE)" value={stats.naiveSendFailures} danger={stats.naiveSendFailures > 0} />
          )}
        </dl>
      )}
    </section>
  )
}

function SummaryPanel() {
  const summary = usePolling<OrderSummary>(api.summary)
  if (!summary) return null
  const s = summary.byStatus
  return (
    <section className="card">
      <div className="row space-between">
        <h2>Đơn hàng</h2>
        <span className={`badge ${summary.stuck === 0 ? 'ok' : 'warn'}`}>
          {summary.stuck === 0 ? 'Không đơn nào đang dở' : `${summary.stuck} đơn đang xử lý / treo`}
        </span>
      </div>
      <dl className="stats">
        <Stat label="CONFIRMED" value={s.CONFIRMED ?? 0} />
        <Stat label="REJECTED (hết hàng)" value={s.REJECTED ?? 0} />
        <Stat label="CANCELLED (đã hoàn kho)" value={s.CANCELLED ?? 0} />
        <Stat label="Đang dở" value={summary.stuck} />
        <Stat label="Thiếu email" value={summary.confirmedNoEmail} danger={summary.confirmedNoEmail > 0} />
        <Stat label="Email trùng" value={summary.duplicateEmails} danger={summary.duplicateEmails > 0} />
      </dl>
    </section>
  )
}

function Stat({ label, value, danger = false }: { label: string; value: number; danger?: boolean }) {
  return (
    <div className={`stat ${danger ? 'danger' : ''}`}>
      <dt>{label}</dt>
      <dd>{value.toLocaleString('vi-VN')}</dd>
    </div>
  )
}

function OrderForm() {
  const [customerId, setCustomerId] = useState(1)
  const [sku, setSku] = useState<string>('SKU-01')
  const [qty, setQty] = useState(1)
  const [lastKey, setLastKey] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const send = async (key: string) => {
    setLastKey(key)
    try {
      const order = await api.placeOrder(key, customerId, sku, qty)
      setMessage(`Đơn #${order.id}: ${order.status}`)
    } catch (e) {
      setMessage(`Lỗi: ${(e as Error).message} — order-service đang chết? Thử "Gửi lại cùng key".`)
    }
  }

  return (
    <section className="card">
      <h2>Đặt hàng</h2>
      <form
        className="form"
        onSubmit={(e) => {
          e.preventDefault()
          send(crypto.randomUUID())
        }}
      >
        <label>
          Khách hàng (1 – 1.000; số chia hết cho 10 là ví mỏng)
          <input type="number" min={1} max={1000} value={customerId} onChange={(e) => setCustomerId(Number(e.target.value))} />
        </label>
        <div className="row">
          <label>
            Sản phẩm
            <select value={sku} onChange={(e) => setSku(e.target.value)}>
              {SKUS.map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
          </label>
          <label>
            Số lượng
            <input type="number" min={1} max={10} value={qty} onChange={(e) => setQty(Number(e.target.value))} />
          </label>
        </div>
        <div className="row">
          <button type="submit">Đặt (key mới)</button>
          <button type="button" className="secondary" disabled={!lastKey} onClick={() => lastKey && send(lastKey)}>
            Gửi lại cùng key
          </button>
        </div>
        {message && <p className="muted small">{message}</p>}
      </form>
    </section>
  )
}

function RecentOrders() {
  const orders = usePolling<Order[]>(api.recentOrders)
  return (
    <section className="card">
      <h2>Đơn gần đây</h2>
      <table className="table">
        <thead>
          <tr>
            <th>#</th>
            <th>Khách</th>
            <th>Hàng</th>
            <th className="num">Tổng tiền</th>
            <th>Trạng thái</th>
            <th className="num">Email</th>
          </tr>
        </thead>
        <tbody>
          {(orders ?? []).map((o) => (
            <tr key={o.id}>
              <td>{o.id}</td>
              <td>{o.customerId}</td>
              <td>{o.items.map((i) => `${i.sku}×${i.qty}`).join(', ')}</td>
              <td className="num">{vnd(o.totalAmount)}</td>
              <td>
                <span className={`chip ${o.status}`}>{o.status}</span>
                {o.failureReason && <span className="muted small"> {o.failureReason}</span>}
              </td>
              <td className="num">{o.emailsSent}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
