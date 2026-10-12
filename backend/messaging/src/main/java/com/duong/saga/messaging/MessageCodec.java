package com.duong.saga.messaging;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class MessageCodec {

    private final JsonMapper jsonMapper;

    public MessageCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String encode(Message message) {
        return jsonMapper.writeValueAsString(message);
    }

    public Message decode(String json) {
        try {
            Message message = jsonMapper.readValue(json, Message.class);
            if (message.messageId() == null || message.type() == null) {
                throw new NonRetryableMessageException("Message without id or type");
            }
            return message;
        } catch (JacksonException e) {
            throw new NonRetryableMessageException("Malformed message: " + e.getOriginalMessage());
        }
    }
}
