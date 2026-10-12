package com.duong.saga.messaging;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nhận message, chống trùng, giao cho handler — tất cả trong 1 transaction:
 * <pre>
 *   BEGIN
 *     INSERT inbox(message_id)      -- trùng khoá => đã xử lý rồi => COMMIT, trả 200, không làm gì thêm
 *     handler: đổi dữ liệu nghiệp vụ + INSERT outbox (message trả lời)
 *   COMMIT                          -- inbox, nghiệp vụ, outbox: cùng thành công hoặc cùng biến mất
 * </pre>
 * Handler lỗi => rollback cả inbox => bên gửi retry => xử lý lại từ đầu như chưa từng nhận.
 */
@Component
public class MessageDispatcher {

    private final Map<String, MessageHandler> handlers;
    private final SagaProperties properties;
    private final MessagingStore store;
    private final TransactionTemplate transactionTemplate;

    public MessageDispatcher(List<MessageHandler> handlerBeans,
                             SagaProperties properties,
                             MessagingStore store,
                             TransactionTemplate transactionTemplate) {
        this.handlers = handlerBeans.stream().collect(Collectors.toMap(MessageHandler::type, Function.identity()));
        this.properties = properties;
        this.store = store;
        this.transactionTemplate = transactionTemplate;
    }

    public void dispatch(Message message) {
        MessageHandler handler = handlers.get(message.type());
        if (handler == null) {
            throw new NonRetryableMessageException("No handler for type " + message.type());
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (properties.mode() == SagaMode.OUTBOX && !store.recordInbox(message)) {
                store.increment(MessagingStore.DUPLICATES_SUPPRESSED);
                return;
            }
            handler.handle(message);
        });
    }
}
