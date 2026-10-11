package io.github.baoloc1993.zalobot.model.zalo;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ZaloWebhookEvent {

    @JsonProperty("event_name")
    private String eventName;

    @JsonProperty("sender_id")
    private String senderId;

    @JsonProperty("message")
    private Message message;

    // Getters and Setters
    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public Message getMessage() {
        return message;
    }

    public void setMessage(Message message) {
        this.message = message;
    }

    public static class Message {
        @JsonProperty("msg_id")
        private String msgId;

        @JsonProperty("text")
        private String text;

        // Getters and Setters
        public String getMsgId() {
            return msgId;
        }

        public void setMsgId(String msgId) {
            this.msgId = msgId;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}
