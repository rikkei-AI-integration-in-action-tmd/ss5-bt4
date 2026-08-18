package com.rikkei.persistent.dto;

public record ChatRequest(
        String conversationId,
        String message
) {
}
