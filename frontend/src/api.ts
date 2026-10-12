export const SERVICES = ['order', 'inventory', 'payment'] as const
export type ServiceName = (typeof SERVICES)[number]

export const SKUS = [
  'SKU-01',
  'SKU-02',
  'SKU-03',
  'SKU-04',
  'SKU-05',
  'SKU-06',
  'SKU-07',
  'SKU-08',
  'SKU-09',
  'SKU-10',
] as const

export interface MessagingStats {
  service: string
  mode: 'NAIVE' | 'OUTBOX'
  outboxPending: number
  outboxSent: number
  outboxDead: number
  inboxProcessed: number
  duplicatesSuppressed: number
  naiveSendFailures: number
}

export interface Order {
  id: number
  customerId: number
  status: 'PENDING' | 'STOCK_RESERVED' | 'CONFIRMED' | 'REJECTED' | 'CANCELLED'
  totalAmount: number | null
  failureReason: string | null
  items: { sku: string; qty: number }[]
  emailsSent: number
}

export interface OrderSummary {
  byStatus: Record<string, number>
  stuck: number
  confirmedNoEmail: number
  duplicateEmails: number
  staleEventsIgnored: number
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
    signal: AbortSignal.timeout(3000),
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json() as Promise<T>
}

export const api = {
  messaging: (s: ServiceName) => request<MessagingStats>(`/svc/${s}/api/admin/messaging`),
  recentOrders: () => request<Order[]>('/svc/order/api/orders?limit=15'),
  summary: () => request<OrderSummary>('/svc/order/api/admin/orders/summary'),
  placeOrder: (key: string, customerId: number, sku: string, qty: number) =>
    request<Order>('/svc/order/api/orders', {
      method: 'POST',
      headers: { 'Idempotency-Key': key },
      body: JSON.stringify({ customerId, items: [{ sku, qty }] }),
    }),
}

export const vnd = (n: number | null) => (n === null ? '—' : `${n.toLocaleString('vi-VN')} ₫`)
