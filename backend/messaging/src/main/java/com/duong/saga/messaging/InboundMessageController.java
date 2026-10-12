package com.duong.saga.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cổng nhận message nội bộ. Mã trả về là "giao kèo" với relay bên gửi:
 * 200 = đã xử lý (hoặc đã xử lý từ trước), 400 = message hỏng đừng gửi lại, 5xx = gửi lại sau.
 */
@RestController
public class InboundMessageController {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageController.class);

    private final MessageCodec codec;
    private final MessageDispatcher dispatcher;

    public InboundMessageController(MessageCodec codec, MessageDispatcher dispatcher) {
        this.codec = codec;
        this.dispatcher = dispatcher;
    }

    @PostMapping("/internal/messages")
    public ResponseEntity<String> receive(@RequestBody String body) {
        try {
            dispatcher.dispatch(codec.decode(body));
            return ResponseEntity.ok("OK");
        } catch (NonRetryableMessageException e) {
            log.warn("Rejecting poison message: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (TransientMessageException e) {
            return ResponseEntity.status(503).body(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Message processing failed, sender will retry: {}", e.toString());
            return ResponseEntity.internalServerError().body(e.getClass().getSimpleName());
        }
    }
}
