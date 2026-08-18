package com.rikkei.persistent.controller;

import com.rikkei.persistent.dto.ChatRequest;
import com.rikkei.persistent.dto.ChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/booking-agent")
public class BookingAgentController {

    private final ChatClient chatClient;

    public BookingAgentController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String conversationId = (request != null && request.conversationId() != null && !request.conversationId().isBlank())
                ? request.conversationId().trim()
                : UUID.randomUUID().toString();

        String userMessage = (request != null && request.message() != null)
                ? request.message()
                : "";

        String responseContent = this.chatClient.prompt()
                .user(userMessage)
                .advisors(advisorSpec -> advisorSpec.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                .call()
                .content();

        return ChatResponse.of(conversationId, responseContent);
    }
}
