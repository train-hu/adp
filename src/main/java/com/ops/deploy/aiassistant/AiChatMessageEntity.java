package com.ops.deploy.aiassistant;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * AI 助手对话消息（V17 ai_chat_message 表）。
 * 会话与消息合一：按 conversation_id 分组，不单独建会话表。
 * role 取 user / assistant；assistant 消息携带 ReAct 轨迹与降级标记。
 */
@Entity
@Table(name = "ai_chat_message")
public class AiChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(name = "model_name", length = 128)
    private String modelName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "trajectory_json", columnDefinition = "TEXT")
    private String trajectoryJson;

    @Column(nullable = false)
    private boolean degraded = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public AiChatMessageEntity() {}

    public AiChatMessageEntity(String conversationId, Long userId, String username,
                               String role, String modelName, String content,
                               String trajectoryJson, boolean degraded) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.username = username;
        this.role = role;
        this.modelName = modelName;
        this.content = content;
        this.trajectoryJson = trajectoryJson;
        this.degraded = degraded;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTrajectoryJson() { return trajectoryJson; }
    public void setTrajectoryJson(String trajectoryJson) { this.trajectoryJson = trajectoryJson; }
    public boolean isDegraded() { return degraded; }
    public void setDegraded(boolean degraded) { this.degraded = degraded; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
