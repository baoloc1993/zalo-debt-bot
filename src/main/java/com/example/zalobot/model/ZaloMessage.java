package com.example.zalobot.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "zalo_messages")
@Data
public class ZaloMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_id", nullable = false)
    private String senderId;

    @Column(name = "receiver_id", nullable = false)
    private String receiverId;

    @Column(name = "message_content", columnDefinition = "TEXT")
    private String messageContent;

    @Column(name = "message_type", length = 50)
    private String messageType;

    @Column(name = "timestamp")
    private LocalDateTime timestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private Direction direction;

    @Column(name = "raw_data", columnDefinition = "JSON")
    private String rawData;

    @Column(name = "zalo_message_id", unique = true)
    private String zaloMessageId;

    public String getZaloMessageId() {
        return zaloMessageId;
    }

    public void setZaloMessageId(String zaloMessageId) {
        this.zaloMessageId = zaloMessageId;
    }

    public void setRawData(String rawData) {
        this.rawData = rawData;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }

    public void setMessageContent(String messageContent) {
        this.messageContent = messageContent;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public enum Direction {
        inbound,
        outbound
    }
}
