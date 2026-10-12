# order-saga-outbox-spring-boot

[![ci](https://github.com/123letlearnML/order-saga-outbox-spring-boot/actions/workflows/ci.yml/badge.svg)](https://github.com/123letlearnML/order-saga-outbox-spring-boot/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
![java](https://img.shields.io/badge/java-21-ED8B00)

<p align="left">
<img src="https://img.shields.io/badge/Java%2021-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21"> <img src="https://img.shields.io/badge/Spring%20Boot%204-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot 4"> <img src="https://img.shields.io/badge/MySQL-4479A1?style=for-the-badge&logo=mysql&logoColor=white" alt="MySQL"> <img src="https://img.shields.io/badge/Transactional%20Outbox-8E44AD?style=for-the-badge" alt="Transactional Outbox"> <img src="https://img.shields.io/badge/Saga-FF6F00?style=for-the-badge" alt="Saga"> <img src="https://img.shields.io/badge/Chaos%20test%20kill%20--9-C0392B?style=for-the-badge" alt="Chaos test kill -9"> <img src="https://img.shields.io/badge/React%20%2B%20Vite-61DAFB?style=for-the-badge&logo=react&logoColor=black" alt="React + Vite">
</p>

Đặt hàng đi qua **3 service, 3 database**: giữ hàng → thu tiền → gửi email. Bắn 3.000 đơn trong lúc **`kill -9`
ngẫu nhiên** các service, rồi đối soát chéo từng đơn giữa 3 database: có đơn nào treo, ai bị trừ tiền oan, hàng nào
bị giữ chết, email nào gửi trùng?

> **English summary.** Three Spring Boot services (order, inventory, payment), each with its own MySQL database,
> run an orchestrated saga with compensation. A load generator places 3,000 orders while a chaos loop `kill -9`s a
> random service every ~10 s and the network duplicates 5% of messages; afterwards every order is cross-checked
> across the three databases. With the naive dual-write (commit, then HTTP), **2,409 of 3,000 orders never finished**, 374 kept stock reserved forever, two
> customers were charged for orders that never completed, and 19 confirmation emails went out twice. With the Transactional Outbox,
> an inbox for deduplication and state guards, the same 7 kills left **zero anomalies**: every order reached a final state
> and 840 duplicate deliveries were absorbed. Giving up retries after 30 s re-created the failure through the back door
> (459 dead letters, 455 stuck orders) until the dead letters were redriven. The chaos harness itself had to be debugged:
> the first runs "killed" the Windows `java` launcher stub instead of the JVM and reported zero failures.

## Kết quả chính

3.000 đơn (40 đơn/giây), mỗi ~10 giây `kill -9` 1 service ngẫu nhiên (7 cú kill mỗi lượt), mạng giao trùng 5% message,
nhà cung cấp email lỗi 20% số lần gọi. Hết tải → chờ hàng đợi ráo → đối soát **từng đơn** giữa 3 database.

| bất thường sau khi hệ thống ổn định | **OUTBOX** | NAIVE (dual-write) | OUTBOX, bỏ cuộc sau 30s | → sau redrive DLQ |
|---|---:|---:|---:|---:|
| đơn treo (không bao giờ xong) | **0** | **2.409 / 3.000** | 455 | **0** |
| khách bị trừ tiền nhưng đơn không xong | **0** | 2 | 0 | **0** |
| đơn giữ hàng chết (không bán, không về kho) | **0** | 374 | 111 | **0** |
| khách không nhận email | **0** | 18 | 0 | **0** |
| email gửi trùng | **0** | 19 | 0 | **0** |
| message mất / nằm trong dead letter | **0** | ≥ 216 mất hẳn ¹ | 459 DEAD | **0** |
| message trùng bị Inbox chặn | 840 | — (không có inbox) | 714 | 809 |
| bất biến kho / bất biến tiền | ✅ / ✅ | ✅ / ✅ ² | ✅ / ✅ | ✅ / ✅ |
| đơn tới trạng thái cuối (CONFIRMED / REJECTED / CANCELLED) | 2.051 / 470 / 479 | 491 / 0 / 100 | 1.797 / 376 / 372 | 2.086 / 476 / 438 |

¹ Chỉ đếm được message gửi lỗi khi bên **gửi** còn sống. Message mất vì chính bên gửi bị kill thì không ai kịp đếm.
² Bất biến trong **từng** database vẫn đúng (mỗi transaction cục bộ vẫn ACID). Lỗi nằm **giữa** các database, đúng chỗ
transaction không với tới, nên phải đối soát chéo mới thấy.

Laptop Ryzen 5 5625U, 3 JVM + MySQL + Node chung 1 máy. Lệnh tái hiện ở mục [Chạy thử](#chạy-thử).

Rút ra:
- **Dual-write hỏng nặng hơn mình nghĩ:** 80% đơn treo chỉ với 7 cú kill. Mỗi lần 1 service chết vài giây, mọi message
  gửi tới nó trong lúc đó (và mọi message còn nằm trong RAM của nó) biến mất, không ai biết để gửi lại.
- **Outbox biến "mất" thành "chậm":** cùng 7 cú kill, mọi đơn đều về trạng thái cuối sau khi hệ thống ổn định (~40 giây).
- **Inbox thật sự có việc làm:** hơn 800 message trùng mỗi lượt bị chặn. Không có nó (NAIVE) thì khách nhận email trùng.
- **Outbox không cứu được nếu bỏ cuộc quá sớm:** giới hạn retry 30 giây → 459 message vào DEAD, 455 đơn treo. Dead
  letter không phải là "mất" (message vẫn nằm đó); redrive sau sự cố đưa toàn bộ về 0 bất thường.

## Cách hoạt động

```
POST /api/orders (Idempotency-Key)
   │  BEGIN; INSERT order PENDING; INSERT outbox(ReserveStock); COMMIT;   ← 1 transaction, không gọi mạng
   ▼
 order ──ReserveStock──► inventory ── giữ hàng (khoá theo thứ tự SKU) ──StockReserved──► order
                                     └─ hết hàng ──────────────────────StockRejected──► order: REJECTED
 order ──ChargePayment─► payment ──── trừ ví (UPDATE có điều kiện) ───PaymentCompleted─► order: CONFIRMED
                                     └─ thiếu tiền / thẻ bị từ chối ──PaymentFailed────► order: CANCELLED
 order ──ConfirmReservation──► inventory                (chốt hàng đã bán)
 order ──ReleaseStock────────► inventory                (BÙ TRỪ: trả hàng về kho)
 order ──NotifyCustomer──────► order → nhà cung cấp email (lỗi 20% số lần, KHÔNG idempotent)

Mọi mũi tên = outbox ─► relay ─► HTTP POST /internal/messages ─► inbox ─► handler
```

| Thành phần | Làm gì | Vì sao |
|---|---|---|
| **Transactional Outbox** | Message được INSERT cùng transaction với thay đổi nghiệp vụ | Không còn khe hở "đã commit nhưng chưa gửi" |
| **Relay** | `SELECT ... FOR UPDATE SKIP LOCKED` + lease, gửi song song, retry backoff + jitter | Nhiều relay không nhận trùng; không giữ khoá DB khi gọi mạng; relay chết thì lease hết hạn |
| **Dead letter queue** | Message hỏng (4xx) hoặc quá hạn retry → DEAD; có API redrive | Không retry vô ích, không làm tắc hàng đợi |
| **Inbox** | `INSERT inbox(message_id)` cùng transaction với handler | Giao trùng (at-least-once) chỉ có tác dụng 1 lần |
| **State guard** | Chỉ chuyển trạng thái khi đơn đang ở đúng trạng thái mong đợi | Chặn message cũ, sai thứ tự, trùng mà khác id |
| **Saga orchestration** | `OrderSaga` quyết định bước tiếp theo hoặc bù trừ | Luồng nằm 1 chỗ, dễ đọc và debug |
| **Database-per-service** | `saga_order`, `saga_inventory`, `saga_payment` | Không service nào đọc DB của service khác |

Chế độ `NAIVE` (`SAGA_MODE=NAIVE`) chạy cùng code nghiệp vụ nhưng gửi message kiểu **dual-write**: commit xong mới gọi
HTTP, retry 1 lần trong RAM, không có inbox. Đây là cách viết phổ biến nhất, và là thứ chaos test dùng để so sánh.

Giải thích chi tiết từng khái niệm và câu hỏi phỏng vấn: **[KNOWLEDGE.md](KNOWLEDGE.md)**.

## Những lần mình làm sai

Dự án này "dạy" mình nhiều nhất qua chính các lỗi của bộ chaos test. Theo thứ tự gặp phải:

1. **Chaos test "kill" mà không ai chết.** Trên Windows, `java` trong PATH là stub của Oracle (`javapath\java.exe`),
   nó chạy JVM thật thành **tiến trình con**. Kill stub thì JVM vẫn sống, và test sẽ báo "0 lỗi" **giả**. Phát hiện vì
   lượt chạy sau báo "cổng 8091 đang bận". Sửa: lấy `java.home` để chạy thẳng JVM thật, và sau mỗi cú kill **kiểm tra
   cổng đã đóng**, không đóng thì dừng test. Bài học: phải kiểm chứng rằng chaos *thật sự xảy ra*, không chỉ kiểm chứng kết quả.
2. **Outbox vẫn "mất" message vì bỏ cuộc quá sớm.** Bản đầu retry tối đa 25 lần (khoảng 90 giây). Chaos kill mỗi 6 giây
   trúng cả service đang khởi động, nên inventory/payment chết liền nhiều phút: **473 message rơi vào DEAD, 415 đơn treo,
   38 khách bị trừ tiền mà đơn không xong**, dù đang dùng outbox. Sửa: giới hạn retry **theo thời gian** (30 phút),
   phải dài hơn sự cố lâu nhất có thể xảy ra; kèm API redrive. Kịch bản `--retry-budget 30 --redrive` ở bảng trên tái hiện lỗi này.
3. **Redrive xong vẫn mất email.** Tuổi message tính từ `created_at`, nên message vừa redrive đã "quá hạn" sẵn và rơi lại
   vào DEAD ở lần nhà cung cấp email lỗi đầu tiên (3 email bị mất). Sửa: cột `retry_started_at`, redrive thì reset; có test hồi quy.
4. **Migration của thư viện dùng chung.** Migration outbox/inbox (`V0_1`) chung bảng lịch sử Flyway với service (`V1`).
   Thêm `V0_2` thì mọi database cũ báo *"resolved migration not applied: 0.2"* và service không khởi động được.
   Sửa: thư viện có Flyway riêng, bảng lịch sử riêng (`flyway_messaging_history`), chạy sau Flyway của service.
5. **Kết nối "ma" làm treo trình bắn tải.** Sau nhiều lần kill, client giữ ~950 kết nối keep-alive vẫn ở trạng thái
   ESTABLISHED tới các process đã chết; `AbortSignal.timeout` của `fetch` không cắt được, nên vòng tải không bao giờ xong.
   Sửa: timeout cứng bằng `Promise.race` cho mọi lần gọi.
6. **Gap lock (bắt được khi review, trước khi chạy).** Kiểm tra "đơn đã giữ chỗ chưa" bằng `SELECT ... FOR UPDATE` trên
   dòng **chưa tồn tại** sẽ đặt gap lock ở REPEATABLE READ; 2 đơn mới cùng lúc chặn INSERT của nhau và deadlock. Sửa: đọc
   không khoá, để PK chặn trùng.
7. **Quy trình:** chạy luôn 3.000 đơn × 3 kịch bản, nên mỗi bug ở trên mất 10–15 phút mới lộ ra. Giờ luôn chạy bản
   nhỏ (300 đơn, khoảng 1 phút) cho từng kịch bản trước, ổn rồi mới chạy bản lớn.

## Kiểm chứng tự động

**JUnit trên MySQL thật** (15 test):

| test | chứng minh |
|---|---|
| `orderAndItsOutboxMessageAreSavedInTheSameTransaction` | đơn và message ReserveStock nằm trong cùng 1 commit |
| `sameIdempotencyKeySentConcurrentlyCreatesOneOrder` | 30 request cùng key → 1 đơn, 1 message |
| `duplicateDeliveryIsSuppressedByInbox` | giao trùng → 1 email |
| `lateOrDuplicateEventIsIgnoredByStateGuard` | PaymentCompleted đến sớm → bỏ qua |
| `paymentFailureTriggersCompensation` | thanh toán lỗi → CANCELLED + ReleaseStock |
| `unreachablePeerIsRetriedWithBackoffThenDeadLettered` | service đích chết → retry → DEAD |
| `redriveGivesTheMessageAFreshRetryWindow` | message redrive được hạn retry mới (bug chaos test bắt được) |
| `poisonMessageIsDeadLetteredImmediately` | message hỏng → DEAD ngay, không retry |
| `concurrentRelaysNeverClaimTheSameMessage` | 8 relay, 200 message, SKIP LOCKED → không trùng |
| `concurrentReservationsNeverOversellScarceStock` | 400 đơn giành 300 sản phẩm → đúng 300 |
| `duplicateDeliveriesOfTheSameMessageReserveOnce` | 10 bản trùng → giữ hàng 1 lần |
| `releasingTwiceReturnsStockOnce` | hoàn kho 2 lần (khác id) → hoàn 1 lần |
| `sameMessageDeliveredConcurrentlyChargesOnce` | 20 bản trùng → trừ tiền 1 lần |
| `differentMessagesForTheSameOrderStillChargeOnce` | trùng khác id → PK order_id chặn |
| `insufficientFundsIsRecordedAndReported` | thiếu tiền → PaymentFailed |

**CI chạy cả chaos test:** mỗi lần push, GitHub Actions build 3 service, bắn 600 đơn, `kill -9` ngẫu nhiên, rồi
đối soát; có bất thường là CI đỏ.

## Chạy thử

Cần: JDK 21+, Node 18+, MySQL 8.4+ (không cần Docker).

```powershell
.\scripts\dev-mysql.ps1                       # MySQL riêng ở cổng 3307, tạo 3 database

cd backend
./mvnw package                                # build + 15 test

cd ../chaos
node chaos.mjs --mode OUTBOX                  # tự bật 3 service, bắn 3.000 đơn, kill -9, đối soát
node chaos.mjs --mode NAIVE
node chaos.mjs --mode OUTBOX --retry-budget 30 --redrive
```

Chạy tay từng service + giao diện:
```powershell
java -jar backend/order-service/target/order-service-0.0.1-SNAPSHOT.jar          # :8091
java -jar backend/inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar  # :8092
java -jar backend/payment-service/target/payment-service-0.0.1-SNAPSHOT.jar      # :8093
cd frontend && npm install && npm run dev                                        # http://localhost:5173
```

## API

| Service | Method | Path | Mô tả |
|---|---|---|---|
| order | `POST` | `/api/orders` | Header `Idempotency-Key`, body `{customerId, items:[{sku, qty}]}` → 202 |
| order | `GET` | `/api/orders/{id}`, `/api/orders?limit=` | Trạng thái đơn |
| order | `GET` | `/api/admin/orders/summary` | Số đơn theo trạng thái, đơn treo, email thiếu/trùng |
| inventory | `GET` | `/api/admin/inventory` | Tồn kho + bất biến `initial = available + reserved + confirmed` |
| payment | `GET` | `/api/admin/money` | Bất biến `initial = ví + đã thu` |
| mọi service | `GET` | `/api/admin/messaging` | Outbox chờ/đã gửi/DEAD, inbox, số message trùng bị chặn |
| mọi service | `GET`/`POST` | `/api/admin/messaging/dead`, `.../dead/redrive-all` | Dead letter queue, redrive |
| mọi service | `POST` | `/internal/messages` | Cổng nhận message (200 / 400 poison / 5xx retry) |

## Cấu trúc

```
backend/
├── messaging/          Thư viện dùng chung: MessagePublisher (OUTBOX | NAIVE), OutboxRelay, MessageDispatcher + Inbox,
│                       MessageSender (phân loại lỗi), MessagingStore (SQL outbox/inbox), SagaContract (tên message)
├── order-service/      OrderService (Idempotency-Key), OrderSaga (điều phối + bù trừ), NotifyCustomerHandler
├── inventory-service/  ReserveStock / ConfirmReservation / ReleaseStock (khoá theo thứ tự SKU)
└── payment-service/    ChargePayment (UPDATE có điều kiện, PK order_id chống trừ 2 lần)
chaos/chaos.mjs         Bật 3 service, bắn tải, kill -9, ráo hàng đợi, đối soát chéo
frontend/               React + Vite: trạng thái 3 service, hàng đợi, DLQ, đặt hàng, đơn gần đây
```

## Lộ trình

- [x] Phase 1: outbox, relay, inbox, DLQ, saga có bù trừ, state guard, chaos test kill -9 trong CI
- [ ] Phase 2: saga timeout: quét đơn kẹt, tự chạy bù trừ
- [ ] Phase 3: thay HTTP bằng Kafka qua cùng interface `MessagePublisher`
- [ ] Phase 4: CDC (Debezium) thay polling; tracing OpenTelemetry theo `orderId`

Cùng loạt: [flash-sale-spring-boot](https://github.com/123letlearnML/flash-sale-spring-boot) (khoá trên 1 dòng nóng) ·
[ewallet-ledger-spring-boot](https://github.com/123letlearnML/ewallet-ledger-spring-boot) (sổ cái kép, deadlock, idempotency).
