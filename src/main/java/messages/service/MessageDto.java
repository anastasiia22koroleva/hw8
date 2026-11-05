package messages.service;

import messages.model.Message;

import java.time.LocalDateTime;

public record MessageDto(Long id, String author, String text, LocalDateTime createdAt) {

    public static MessageDto from(Message message) {
        return new MessageDto(
                message.getId(),
                message.getAuthor().getUsername(),
                message.getText(),
                message.getCreatedAt());
    }
}
