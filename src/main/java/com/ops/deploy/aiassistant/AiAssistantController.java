package com.ops.deploy.aiassistant;

import com.ops.deploy.aiassistant.AssistantReActService.AiChatVO;
import com.ops.deploy.common.Result;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 助手接口（/api/ai-assistant）。任何已登录用户可访问，工具执行内部把关权限。
 */
@RestController
@RequestMapping("/api/ai-assistant")
public class AiAssistantController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 聊天请求体。conversationId 为空时服务端生成新会话。 */
    public record ChatRequest(String conversationId, String message, Long modelId) {}

    /** 单条消息查询结果。 */
    public record MessageVO(Long id, String role, String content, String modelName,
                            String trajectoryJson, boolean degraded, String createdAt) {}

    /** 会话列表项。 */
    public record ConversationVO(String conversationId, String lastMessage,
                                 String modelName, long messageCount, String lastAt) {}

    private final AssistantReActService assistantService;
    private final AiChatMessageRepository messageRepository;

    public AiAssistantController(AssistantReActService assistantService,
                                 AiChatMessageRepository messageRepository) {
        this.assistantService = assistantService;
        this.messageRepository = messageRepository;
    }

    @PostMapping("/chat")
    public Result<AiChatVO> chat(@RequestBody ChatRequest req, @CurrentUser AuthUser user) {
        AiChatVO vo = assistantService.chat(
                req == null ? null : req.conversationId(),
                req == null ? null : req.message(),
                req == null ? null : req.modelId(),
                user);
        return Result.ok(vo);
    }

    /** 某会话消息列表（仅当前用户归属）。 */
    @GetMapping("/history")
    public Result<List<MessageVO>> history(@RequestParam String conversationId,
                                           @CurrentUser AuthUser user) {
        List<AiChatMessageEntity> all = messageRepository.findByConversationIdOrderByIdAsc(conversationId);
        List<MessageVO> result = new ArrayList<>();
        for (AiChatMessageEntity m : all) {
            if (m.getUserId() != null && m.getUserId().equals(user.getUserId())) {
                result.add(toMessageVO(m));
            }
        }
        return Result.ok(result);
    }

    /**
     * 会话列表：按 userId 取全部消息（id 倒序），每会话保留最近一条并统计消息数。
     */
    @GetMapping("/conversations")
    public Result<List<ConversationVO>> conversations(@CurrentUser AuthUser user) {
        List<AiChatMessageEntity> all = messageRepository.findByUserIdDesc(user.getUserId());
        Map<String, ConversationVO> byConv = new LinkedHashMap<>();
        for (AiChatMessageEntity m : all) {
            String cid = m.getConversationId();
            ConversationVO vo = byConv.get(cid);
            if (vo == null) {
                byConv.put(cid, new ConversationVO(cid, m.getContent(), m.getModelName(),
                        1L, formatTime(m.getCreatedAt())));
            } else {
                byConv.put(cid, new ConversationVO(cid, vo.lastMessage(), vo.modelName(),
                        vo.messageCount() + 1, vo.lastAt()));
            }
        }
        return Result.ok(new ArrayList<>(byConv.values()));
    }

    private MessageVO toMessageVO(AiChatMessageEntity m) {
        return new MessageVO(m.getId(), m.getRole(), m.getContent(), m.getModelName(),
                m.getTrajectoryJson(), m.isDegraded(), formatTime(m.getCreatedAt()));
    }

    private static String formatTime(LocalDateTime t) {
        return t == null ? null : FMT.format(t);
    }
}