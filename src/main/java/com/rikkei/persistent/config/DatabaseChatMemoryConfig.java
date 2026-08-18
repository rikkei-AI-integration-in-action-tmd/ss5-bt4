package com.rikkei.persistent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Configuration
public class DatabaseChatMemoryConfig {

    @Bean
    public ChatMemory jdbcChatMemory(JdbcTemplate jdbcTemplate) {
        return new ChatMemory() {
            @Override
            public void add(String conversationId, List<Message> messages) {
                if (messages == null || messages.isEmpty()) {
                    return;
                }
                String sql = "INSERT INTO spring_ai_chat_memory (conversation_id, message_type, content) VALUES (?, ?, ?)";
                for (Message msg : messages) {
                    jdbcTemplate.update(sql, conversationId, msg.getMessageType().name(), msg.getText());
                }
            }

            @Override
            public List<Message> get(String conversationId, int lastN) {
                String sql = "SELECT message_type, content FROM spring_ai_chat_memory " +
                        "WHERE conversation_id = ? ORDER BY created_at DESC LIMIT ?";
                List<Message> reversed = jdbcTemplate.query(sql, (rs, rowNum) -> {
                    String typeStr = rs.getString("message_type");
                    String content = rs.getString("content");
                    MessageType type = MessageType.valueOf(typeStr);

                    return switch (type) {
                        case USER -> (Message) new UserMessage(content);
                        case ASSISTANT -> (Message) new AssistantMessage(content);
                        case SYSTEM -> (Message) new SystemMessage(content);
                        default -> (Message) new UserMessage(content);
                    };
                }, conversationId, lastN);

                List<Message> result = new ArrayList<>(reversed);
                Collections.reverse(result);
                return result;
            }

            @Override
            public void clear(String conversationId) {
                jdbcTemplate.update("DELETE FROM spring_ai_chat_memory WHERE conversation_id = ?", conversationId);
            }
        };
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem("Bạn là trợ lý ảo hỗ trợ đặt phòng khách sạn R-Hotels. Hãy trò chuyện thân thiện, ghi nhớ ngữ cảnh từ các câu hỏi trước để phục vụ khách hàng chu đáo.")
                .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
                .build();
    }
}
