package com.ops.deploy.aiassistant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * AI 助手消息仓库。
 */
public interface AiChatMessageRepository extends JpaRepository<AiChatMessageEntity, Long> {

    /** 某会话的全部消息（id 升序 = 时间升序）。 */
    List<AiChatMessageEntity> findByConversationIdOrderByIdAsc(String conversationId);

    /** 某会话最近的消息（id 倒序，调用方截断取所需条数）。 */
    List<AiChatMessageEntity> findByConversationIdOrderByIdDesc(String conversationId);

    /** 某用户会话活跃情况（每会话最近消息，倒序）。 */
    @Query("SELECT m FROM AiChatMessageEntity m WHERE m.userId = :userId ORDER BY m.id DESC")
    List<AiChatMessageEntity> findByUserIdDesc(@Param("userId") Long userId);
}
