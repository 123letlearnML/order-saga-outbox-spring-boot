package com.duong.saga.messaging;

import java.util.function.Consumer;

/** Khai báo handler bằng lambda: {@code new SimpleMessageHandler("StockReserved", saga::onStockReserved)}. */
public record SimpleMessageHandler(String type, Consumer<Message> action) implements MessageHandler {

    @Override
    public void handle(Message message) {
        action.accept(message);
    }
}
