BÀI 4: TÍCH HỢP - THIẾT KẾ CHATMEMORY BỀN VỮNG (PERSISTENT MEMORY) CHO BOOKING AGENT

1. GIẢI PHÁP THIẾT KẾ VÀ PHÂN TÁCH PHIÊN CHAT (SESSION-SAFETY)

1.1. Thách thức kiến trúc trên môi trường Production
- Vấn đề của InMemoryChatMemory:
  + Mất dữ liệu khi Server Restart: Dữ liệu hội thoại lưu trong heap memory (RAM) của JVM sẽ biến mất hoàn toàn khi pod/server khởi động lại để deploy phiên bản mới.
  + Gãy luồng hội thoại khi Scale-out: Khi ứng dụng chạy trên nhiều Kubernetes Pods sau Load Balancer, các request tiếp theo của cùng một người dùng có thể được định tuyến tới các Pod khác nhau. Do các Pod không chia sẻ bộ nhớ RAM, AI trên Pod mới không thể đọc được lịch sử trò chuyện trước đó, gây ra hiện tượng mất trí nhớ giữa chừng.

1.2. Giải pháp kiến trúc ChatMemory bền vững (Persistent Storage)
- Lưu trữ tập trung (Centralized Storage): Toàn bộ tin nhắn (UserMessage, AssistantMessage, SystemMessage) được lưu trữ tại cơ sở dữ liệu quan hệ MySQL dùng chung cho toàn bộ cụm máy chủ.
- Phân tách phiên an toàn bằng conversationId:
  + Mỗi phiên hội thoại của người dùng được định danh bởi một khóa duy nhất conversationId.
  + Mọi câu truy vấn đọc/ghi lịch sử đều sử dụng điều kiện WHERE conversation_id = ?, đảm bảo tính cô lập dữ liệu tuyệt đối giữa các khách hàng.
- Cơ chế phòng thủ tự sinh UUID:
  + Nếu Client không truyền conversationId (lượt chat đầu tiên), REST Controller sẽ tự động sinh mã UUID ngẫu nhiên (UUID.randomUUID().toString()), gán vào ngữ cảnh ChatClient và trả về cho Client trong response để duy trì cho các lượt chat tiếp theo.


2. MÃ NGUỒN JAVA CẤU HÌNH VÀ CONTROLLER

2.1. Cấu trúc bảng cơ sở dữ liệu (schema.sql)
```sql
CREATE TABLE IF NOT EXISTS spring_ai_chat_memory (
    conversation_id VARCHAR(255) NOT NULL,
    message_type VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    metadata JSON NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation (conversation_id)
);
```

2.2. Class cấu hình DatabaseChatMemoryConfig.java
```java
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
```

2.3. Data Transfer Objects (DTO)
- ChatRequest.java:
```java
package com.rikkei.persistent.dto;

public record ChatRequest(
        String conversationId,
        String message
) {
}
```

- ChatResponse.java:
```java
package com.rikkei.persistent.dto;

import java.time.LocalDateTime;

public record ChatResponse(
        String conversationId,
        String answer,
        LocalDateTime timestamp
) {
    public static ChatResponse of(String conversationId, String answer) {
        return new ChatResponse(conversationId, answer, LocalDateTime.now());
    }
}
```

2.4. REST Controller BookingAgentController.java
```java
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
```


3. THUYẾT MINH KIẾN TRÚC ĐỒNG BỘ DỮ LIỆU

3.1. Sơ đồ kiến trúc xử lý đa máy chủ (Multi-node Topology)
[CLIENT (Web / Mobile App)]
      |
      | Request 1: {message: "Tìm phòng"} -> Pod A
      | Request 2: {conversationId: "uuid-1234", message: "Phòng Deluxe"} -> Pod B
      v
[LOAD BALANCER]
   /        \
  v          v
[Pod A]    [Pod B]
   \        /
    v      v
[MYSQL CLUSTER (spring_ai_chat_memory table)]

3.2. Nguyên lý Stateless Backend
- Tầng ứng dụng Spring Boot hoàn toàn không lưu giữ trạng thái của phiên trò chuyện trong RAM.
- Mọi Pod đều bình đẳng và có khả năng phục vụ bất kỳ request nào của bất kỳ phiên chat nào, miễn là nhận được mã conversationId.

3.3. Vòng đời xử lý qua MessageChatMemoryAdvisor
- Trước khi gửi Prompt: MessageChatMemoryAdvisor trích xuất conversationId, gọi chatMemory.get(conversationId, lastN) để truy vấn N tin nhắn gần nhất từ MySQL và nối vào Prompt gửi LLM.
- Sau khi nhận phản hồi từ LLM: MessageChatMemoryAdvisor thu nhận câu trả lời và gọi chatMemory.add(conversationId, List.of(userMessage, assistantMessage)) để lưu vào cơ sở dữ liệu.

3.4. Lợi ích khi Scale-out và High Availability
- Độc lập hạ tầng: Khách hàng không bị gián đoạn hay mất ngữ cảnh khi số lượng Pod co giãn (Autoscaling).
- Không mất dữ liệu: Khi một Pod bị restart, phiên trò chuyện vẫn được bảo toàn nguyên vẹn trên MySQL.
- Tối ưu hóa hiệu năng: Cột conversation_id được đánh Index giúp câu lệnh lấy lịch sử đạt tốc độ dưới 5ms.
