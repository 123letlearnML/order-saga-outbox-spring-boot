# Order Saga — Tổng hợp kiến thức

> **Bài toán:** đặt hàng cần 3 việc ở **3 service khác nhau**: giữ hàng (inventory), thu tiền (payment), gửi email
> (order). Không có transaction nào bao trùm cả 3 database. Service có thể chết **bất kỳ lúc nào**, mạng có thể giao
> 1 message **2 lần**. Yêu cầu: đơn không bao giờ treo, khách không bị trừ tiền oan, hàng không bị giữ chết,
> mỗi khách nhận đúng 1 email.

Đọc theo thứ tự. Mỗi phần: vấn đề → cách giải → code ở đâu → câu hỏi phỏng vấn.

---

## Mục lục
1. [Vì sao không dùng 1 transaction cho cả 3 service?](#1-vì-sao-không-dùng-1-transaction)
2. [Dual-write: lỗi "nằm giữa 2 dòng code"](#2-dual-write)
3. [Transactional Outbox](#3-transactional-outbox)
4. [Relay: SKIP LOCKED, lease, backoff, dead letter](#4-relay)
5. [At-least-once và Inbox](#5-at-least-once-và-inbox)
6. [Saga: orchestration và bù trừ](#6-saga)
7. [State guard: lớp chống trùng thứ hai](#7-state-guard)
8. [Khoá trong từng service (gap lock!)](#8-khoá-trong-từng-service)
9. [Chaos test: kill -9 thật](#9-chaos-test)
10. [Kết quả đo](#10-kết-quả-đo)
11. [Câu hỏi phỏng vấn](#11-câu-hỏi-phỏng-vấn)
12. [Lộ trình](#12-lộ-trình)

---

## 1. Vì sao không dùng 1 transaction?

**Database-per-service:** mỗi service sở hữu database của mình (`saga_order`, `saga_inventory`, `saga_payment`),
không service nào đọc/ghi DB của service khác. Lợi ích: deploy độc lập, đổi schema không ảnh hưởng ai, 1 DB chết
không kéo cả hệ thống. Cái giá: **mất ACID xuyên service**.

**Two-phase commit (2PC / XA)?** Có tồn tại, nhưng:
- Coordinator chết giữa phase 1 và 2 → mọi bên **giữ khoá chờ vô thời hạn**.
- Mọi bên phải hỗ trợ XA (HTTP API của ngân hàng, nhà cung cấp email thì không).
- Chậm: thêm round-trip, giữ khoá lâu. Hầu hết hệ thống microservice không dùng.

→ Chấp nhận **eventual consistency** (nhất quán sau cùng) và tự xây cơ chế đảm bảo "sau cùng" đó thật sự xảy ra.

---

## 2. Dual-write

Cách tự nhiên nhất (chế độ `NAIVE`, `MessagePublisher.sendAfterCommit`):
```java
@Transactional
void placeOrder() {
    orderRepo.save(order);        // 1. ghi DB
}                                 // COMMIT
httpClient.post(inventory, msg);  // 2. gửi message
```
Giữa 1 và 2 có một khoảng hở. Process bị kill ở đó, hoặc inventory đang chết → **message mất vĩnh viễn**:
đơn PENDING mãi mãi, không ai biết để gửi lại.

Đảo thứ tự (gửi trước, commit sau) cũng hỏng: gửi xong mà commit lỗi → inventory giữ hàng cho 1 đơn không tồn tại.

Retry trong RAM (`retry 1 lần sau 500ms`) cũng không cứu được: process chết thì hàng đợi trong RAM chết theo.

> Đây là lỗi kinh điển: code **trông** đúng, test thường **luôn** pass. Chỉ chaos test (kill giữa chừng) mới lộ.

---

## 3. Transactional Outbox

Ý tưởng: **đừng gửi message trong lúc xử lý nghiệp vụ, hãy GHI nó vào DB** cùng transaction nghiệp vụ.

```
BEGIN
  INSERT INTO orders (...)                          -- nghiệp vụ
  INSERT INTO outbox (destination, type, body, ...)  -- "việc cần gửi"
COMMIT                                              -- cả 2 cùng có, hoặc cùng không
```
Commit thành công = message **chắc chắn** nằm trong outbox. Việc gửi do **relay** làm sau, retry tới khi được.

Code: `MessagePublisher.publish` → `MessagingStore.insertOutbox` có `@Transactional(propagation = MANDATORY)`:
gọi ngoài transaction là lỗi ngay, vì outbox ngoài transaction thì mất hết ý nghĩa.

**Cách khác để đọc outbox:** CDC (Debezium đọc binlog MySQL → Kafka): không cần polling, độ trễ thấp hơn, nhưng thêm
hạ tầng. Dự án dùng polling vì đơn giản, đủ nhanh (độ trễ ~50ms), và dạy được các vấn đề cốt lõi.

---

## 4. Relay

`OutboxRelay` chạy nền, mỗi vòng:
```sql
-- 1. Nhận lô (transaction ngắn, READ COMMITTED)
SELECT id FROM outbox
 WHERE status = 'PENDING' AND next_attempt_at <= NOW(6)
   AND (lease_until IS NULL OR lease_until < NOW(6))
 ORDER BY id LIMIT 50
   FOR UPDATE SKIP LOCKED;
UPDATE outbox SET lease_until = NOW(6) + 10s, attempts = attempts + 1 WHERE id IN (...);
COMMIT;
-- 2. Gửi HTTP song song (KHÔNG giữ khoá DB)
-- 3. Ghi kết quả: SENT | hẹn lại sau backoff | DEAD
```

| Kỹ thuật | Giải quyết |
|---|---|
| `FOR UPDATE SKIP LOCKED` | Nhiều relay chạy song song: bỏ qua dòng relay khác đang khoá thay vì đứng chờ. Không nhận trùng, không chặn nhau. Có test chứng minh (8 relay, 200 message, không dòng nào bị nhận 2 lần). |
| **Lease** (`lease_until`) | Không giữ khoá DB trong lúc gọi mạng (bài học dự án 2). Relay chết giữa chừng → lease hết hạn → relay khác nhận lại. |
| **Exponential backoff + jitter** | 200ms, 400ms, 800ms… tối đa 5s, cộng ngẫu nhiên. Không có jitter thì cả nghìn message retry đúng 1 thời điểm và **đè chết service vừa sống lại**. |
| **Dead letter (DEAD)** | Lỗi tạm thời kéo dài quá **30 phút**, hoặc nhận 4xx (message hỏng) → dừng, chờ người xem. API `POST /api/admin/messaging/dead/redrive-all` để gửi lại sau khi sửa. |
| **Retry budget theo thời gian** | Bản đầu bỏ cuộc sau 25 lần (~90 giây). Chaos test cho thấy sự cố có thể dài hơn thế nhiều → hàng trăm message DEAD → đơn treo. Giới hạn retry phải **dài hơn sự cố lâu nhất có thể xảy ra**. |
| **Redrive reset đồng hồ retry** | Tuổi message tính từ `retry_started_at`, không từ `created_at`. Nếu không, message vừa redrive đã "quá hạn" sẵn và rơi lại vào DEAD ở lần lỗi đầu tiên. |
| **Phân loại lỗi** | 2xx xong · 4xx = poison, retry vô ích → DEAD ngay · 5xx/timeout = tạm thời → retry. |
| **READ COMMITTED** cho câu claim | Ở REPEATABLE READ, câu quét theo khoảng khoá cả gap trong index → chặn INSERT outbox của transaction nghiệp vụ. |

---

## 5. At-least-once và Inbox

Relay gửi xong, inventory xử lý xong, **relay bị kill trước khi kịp ghi SENT** → lease hết hạn → gửi lại.
Nghĩa là outbox cho **at-least-once** (ít nhất 1 lần), không phải exactly-once.

> **"Exactly-once delivery" gần như không tồn tại qua mạng.** Thứ làm được là **exactly-once *effect***:
> giao nhiều lần nhưng **tác dụng** chỉ 1 lần. Đó là việc của bên nhận.

**Inbox** (`MessageDispatcher`):
```
BEGIN
  INSERT INTO inbox (message_id)   -- trùng khoá => đã xử lý rồi => COMMIT, trả 200, không làm gì
  handler(...)                     -- đổi nghiệp vụ + ghi outbox trả lời
COMMIT
```
Inbox và nghiệp vụ **cùng transaction**: handler lỗi thì inbox cũng rollback, lần giao sau xử lý lại từ đầu.
Hai bản trùng đến **cùng lúc**: bản sau chờ khoá của bản trước; bản trước commit thì bản sau nhận `DuplicateKey`.

**Vì sao email là ví dụ hay nhất?** Nhà cung cấp email **không idempotent**: gọi 2 lần là khách nhận 2 email, và không
có trạng thái nghiệp vụ nào để chặn. Chế độ NAIVE (không inbox) → email trùng thật sự, đo được trong chaos test.

---

## 6. Saga

**Saga** = chuỗi transaction cục bộ, mỗi bước có **bước bù trừ** (compensation) nếu bước sau thất bại.

```
          ReserveStock          ChargePayment            ConfirmReservation + NotifyCustomer
 order ───────────────► inv ──► order ──────────► pay ──► order ──────────────────────────► ✓ CONFIRMED
                         │                         │
                    hết hàng                  thiếu tiền / thẻ bị từ chối
                         ▼                         ▼
                 REJECTED (không cần bù)    CANCELLED + ReleaseStock ──► inv: trả hàng về kho
```

| | Orchestration (dự án này) | Choreography |
|---|---|---|
| Ai điều khiển | 1 bộ điều phối (`OrderSaga`) | Mỗi service tự nghe sự kiện và phản ứng |
| Ưu | Luồng nằm 1 chỗ, dễ đọc, dễ debug | Không có điểm trung tâm, service độc lập hơn |
| Nhược | Order-service biết nhiều | Luồng rải rác, khó thấy toàn cảnh, dễ vòng lặp sự kiện |

**Bù trừ khác rollback:** bước "giữ hàng" đã COMMIT ở inventory rồi, không rollback được. Bù trừ là một **transaction
mới, viết tay**, làm điều ngược lại (`ReleaseStockHandler`). Bước bù trừ cũng phải idempotent.

**Giá trị trả về do bên sở hữu dữ liệu tính:** tổng tiền đơn do inventory tính (inventory sở hữu giá) và gửi kèm
`StockReserved`. Order-service không gọi đồng bộ sang inventory để hỏi giá.

---

## 7. State guard

Mỗi chuyển trạng thái chỉ xảy ra khi đơn **đang ở đúng trạng thái mong đợi** (`OrderSaga.guarded`):
```java
guarded(message, "STOCK_RESERVED")   // SELECT ... FOR UPDATE; trạng thái khác => bỏ qua, đếm "stale"
```
Chặn được: message đến **sai thứ tự**, message **cũ**, và message trùng mà **khác messageId** (inbox không chặn được).
Tương tự ở inventory (`RESERVED → CONFIRMED / RELEASED`) và payment (`payments.order_id` là PK: đơn đã thanh toán
thì chỉ trả lời lại kết quả cũ).

**Hai lớp chống trùng bổ sung cho nhau:**

| | Inbox | State guard / natural key |
|---|---|---|
| Chặn | Cùng messageId giao lại | Cùng ý nghĩa nghiệp vụ, khác messageId |
| Áp dụng | Mọi message, chung 1 cơ chế | Từng handler tự viết |
| Không chặn được | Message trùng nhưng khác id | Thao tác không có trạng thái (gửi email) |

---

## 8. Khoá trong từng service

- **Inventory khoá theo thứ tự SKU** (`ORDER BY sku FOR UPDATE`): đơn A (SKU-01, SKU-02) và đơn B (SKU-02, SKU-01)
  không deadlock. Bài học trực tiếp từ dự án 2.
- **⚠️ Gap lock:** bản đầu kiểm tra "đơn này đã giữ chỗ chưa" bằng `SELECT ... FOR UPDATE` trên reservation **chưa
  tồn tại**. Ở REPEATABLE READ, khoá một dòng chưa tồn tại sẽ đặt **gap lock** lên khoảng trống trong index. Hai đơn mới
  (id tăng dần) cùng rơi vào khoảng cuối index, cùng giữ gap lock (gap lock không xung đột nhau), rồi cùng INSERT
  → insert intention lock của bên này bị gap lock của bên kia chặn → **deadlock**. Sửa: kiểm tra bằng `SELECT`
  thường, để PK chặn trùng ở bước INSERT. Bắt được khi review code, trước khi chạy.
- **Trừ tiền:** `UPDATE wallets SET balance = balance - ? WHERE balance >= ?` (atomic conditional update, dự án 2).

---

## 9. Chaos test

`chaos/chaos.mjs`:
1. Khởi động 3 service (jar thật, process thật).
2. Bắn 3.000 đơn (40 đơn/giây). Client giống app di động: lỗi thì gửi lại với **cùng Idempotency-Key**.
3. Song song: cứ ~6 giây **`kill -9` ngẫu nhiên** 1 service, 3 giây sau khởi động lại. Có lúc kill trúng service
   đang khởi động.
4. Mạng "giao trùng" 5% message (`SAGA_DUPLICATE_RATE`).
5. Hết tải → bật lại mọi service → chờ hàng đợi "ráo".
6. **Đối soát chéo 3 database cho từng đơn**:

| bất thường | nghĩa là |
|---|---|
| `stuckOrders` | đơn không bao giờ tới trạng thái cuối |
| `chargedNotConfirmed` | **khách bị trừ tiền nhưng đơn không thành công** |
| `confirmedNotCharged` | giao hàng mà không thu tiền |
| `stockHeld` | hàng bị giữ chỗ mãi mãi: không bán được, không về kho |
| `confirmedStockNotFinal` | đơn xong nhưng kho chưa chốt |
| `missingEmails` / `duplicateEmails` | khách không nhận / nhận trùng email |
| bất biến kho, bất biến tiền | `initial = available + reserved + confirmed`, `initial = ví + đã thu` |

**⚠️ Migration của thư viện dùng chung:** bản đầu để migration outbox/inbox (`V0_1`) chung bảng lịch sử Flyway với
service (`V1`). Thêm `V0_2` thì mọi database đã chạy tới V1 đều báo *"resolved migration not applied: 0.2"* (Flyway
không chạy migration lùi thứ tự) và service không khởi động được. Sửa: thư viện có **Flyway riêng, bảng lịch sử riêng**
(`flyway_messaging_history`), chạy **sau** Flyway của service (`MessagingConfig`). Bài học: module dùng chung cần
dòng version độc lập với module dùng nó.

**⚠️ Lỗi đo suýt bỏ qua:** lần chạy đầu, chaos "kill" mà không service nào chết. Trên Windows, `java` trong PATH là
**stub** của Oracle (`javapath\java.exe`), nó chạy JVM thật thành **tiến trình con**. Kill stub thì JVM vẫn sống, và
test sẽ báo "0 lỗi" giả. Sửa: lấy `java.home` để chạy thẳng JVM thật, và **sau mỗi cú kill phải kiểm tra cổng đã
đóng**, không đóng thì dừng test. Bài học: **kiểm chứng rằng chaos thật sự xảy ra**, không chỉ kiểm chứng kết quả.

---

## 10. Kết quả đo

3.000 đơn, 7 cú `kill -9`, mạng giao trùng 5%, email lỗi 20%. Đối soát chéo sau khi ráo hàng đợi:

| | OUTBOX | NAIVE | OUTBOX bỏ cuộc sau 30s | sau redrive |
|---|---:|---:|---:|---:|
| đơn treo | **0** | **2.409** | 455 | **0** |
| trừ tiền mà đơn không xong | 0 | 2 | 0 | 0 |
| hàng giữ chết | 0 | 374 | 111 | 0 |
| email thiếu / trùng | 0 / 0 | 18 / 19 | 0 / 0 | 0 / 0 |
| dead letter | 0 | — | 459 | 0 |
| message trùng bị inbox chặn | 840 | — | 714 | 809 |

**Đọc bảng thế nào:**
1. **Bất biến trong từng DB vẫn đúng ở cả NAIVE.** Mỗi transaction cục bộ vẫn ACID; lỗi nằm *giữa* các DB. Vì vậy
   kiểm tra từng service riêng lẻ sẽ không bao giờ thấy lỗi, phải đối soát chéo.
2. **NAIVE: 80% đơn treo với chỉ 7 cú kill.** Lỗi dual-write không hiếm như cảm giác; nó xảy ra mỗi khi bất kỳ service
   nào chết, kể cả lúc deploy bình thường.
3. **Retry budget là một phần của tính đúng đắn**, không chỉ là tham số hiệu năng.
4. **Dead letter ≠ mất:** message vẫn nằm trong DB, redrive là cứu được. Mất thật là NAIVE: message chỉ nằm trong RAM.

---

## 11. Câu hỏi phỏng vấn

<details><summary><b>Q1. Lưu đơn vào DB rồi gửi event sang Kafka/service khác — có vấn đề gì?</b></summary>

Dual-write: 2 hệ thống không có transaction chung. Crash giữa 2 bước → mất event (hoặc event "ma" nếu đảo thứ tự).
Giải pháp: Transactional Outbox (ghi event vào DB cùng transaction, relay/CDC đẩy đi sau).
</details>

<details><summary><b>Q2. Outbox đảm bảo exactly-once không?</b></summary>

Không, chỉ **at-least-once** (relay có thể gửi lại sau khi crash). Exactly-once *effect* đạt được nhờ bên nhận
idempotent: Inbox (dedup theo messageId) + state guard / natural key.
</details>

<details><summary><b>Q3. Saga là gì? Khác 2PC thế nào?</b></summary>

Chuỗi transaction cục bộ + bước bù trừ cho từng bước. Không giữ khoá xuyên service, chấp nhận trạng thái trung gian
(eventual consistency). 2PC nhất quán mạnh nhưng chặn khi coordinator lỗi và cần mọi bên hỗ trợ XA.
Orchestration vs choreography: xem mục 6.
</details>

<details><summary><b>Q4. Nhiều instance cùng đọc 1 bảng hàng đợi thì tránh xử lý trùng thế nào?</b></summary>

`SELECT ... FOR UPDATE SKIP LOCKED` + lease ngắn, commit ngay; không giữ khoá DB trong lúc gọi mạng.
Lease hết hạn khi instance chết để instance khác nhận lại.
</details>

<details><summary><b>Q5. Retry thế nào cho đúng?</b></summary>

Chỉ retry lỗi tạm thời (5xx, timeout); lỗi 4xx thì không. Exponential backoff + jitter, có giới hạn số lần, hết
lượt thì vào dead letter queue có công cụ redrive. Bên nhận phải idempotent, vì retry tạo ra trùng lặp.
</details>

<details><summary><b>Q6. Một bước của saga không bao giờ trả lời thì sao?</b></summary>

Cần **saga timeout**: job quét đơn kẹt ở 1 trạng thái quá lâu → hỏi lại hoặc chạy bù trừ. Dự án này chưa làm
(lộ trình), vì outbox đã bảo đảm message tới nơi khi service sống lại; timeout xử lý trường hợp service chết hẳn hoặc
message rơi vào DEAD.
</details>

<details><summary><b>Q7. Gap lock là gì? Khi nào gây deadlock?</b></summary>

Ở REPEATABLE READ, InnoDB khoá cả khoảng trống giữa các bản ghi để chống phantom. `SELECT ... FOR UPDATE` trên dòng
chưa tồn tại → gap lock. Hai transaction cùng giữ gap lock rồi cùng INSERT vào khoảng đó → chờ nhau → deadlock.
Tránh: không khoá dòng chưa tồn tại (để unique key chặn), hoặc dùng READ COMMITTED.
</details>

---

## 12. Lộ trình

- [x] **Phase 1:** outbox, relay (SKIP LOCKED + lease + backoff + DLQ), inbox, saga có bù trừ, state guard, chaos test kill -9, CI chạy chaos.
- [ ] **Phase 2:** saga timeout (quét đơn kẹt, chạy bù trừ).
- [ ] **Phase 3:** thay HTTP bằng Kafka qua cùng interface `MessagePublisher`, so sánh độ trễ và độ phức tạp.
- [ ] **Phase 4:** CDC (Debezium) thay cho polling, đo độ trễ outbox → consumer.
- [ ] **Phase 5:** tracing xuyên service (OpenTelemetry) theo `orderId`.
