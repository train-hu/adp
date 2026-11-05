package com.ops.deploy.aiassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ops.deploy.common.BizException;
import com.ops.deploy.crypto.CredentialCryptoService;
import com.ops.deploy.llm.LlmChatClient;
import com.ops.deploy.llm.LlmModelConfigEntity;
import com.ops.deploy.llm.LlmModelConfigService;
import com.ops.deploy.security.AuthUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 助手编排（设计文档 §3）：以 ReAct 工具调用循环响应用户对话。
 */
@Service
public class AssistantReActService {

    private static final Logger log = LoggerFactory.getLogger(AssistantReActService.class);

    private static final ObjectMapper JSON;

    static {
        // 注册 JSR-310 模块并禁用时间戳：VO 含 LocalDateTime 字段，裸 ObjectMapper 无法序列化
        // （会抛 InvalidDefinitionException 被 toJson 吞成 "[]"，导致 AI 工具全部返回空）。
        JSON = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    static final int MAX_STEPS = 12;
    static final int MAX_NUDGE = 2;
    static final int HISTORY_MESSAGES = 20;

    private static final String DEGRADED_TEXT =
            "LLM 不可用，无法执行该操作。请先在「LLM 模型」页配置并启用一个模型，或稍后重试。";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiChatMessageRepository messageRepository;
    private final AssistantToolExecutor toolExecutor;
    private final LlmModelConfigService modelConfigService;
    private final CredentialCryptoService crypto;
    private final LlmChatClient llmClient;

    public AssistantReActService(AiChatMessageRepository messageRepository,
                                 AssistantToolExecutor toolExecutor,
                                 LlmModelConfigService modelConfigService,
                                 CredentialCryptoService crypto,
                                 LlmChatClient llmClient) {
        this.messageRepository = messageRepository;
        this.toolExecutor = toolExecutor;
        this.modelConfigService = modelConfigService;
        this.crypto = crypto;
        this.llmClient = llmClient;
    }

    public record AiChatVO(String conversationId, String reply, String model,
                           String source, boolean degraded) {
    }

    private record AiReply(String content, String trajectoryJson, boolean degraded) {
    }

    public AiChatVO chat(String conversationId, String message, Long modelId, AuthUser user) {
        String convId = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString() : conversationId;
        String text = message == null ? "" : message;

        saveMessage(convId, user, "user", null, text, null, false);

        LlmModelConfigEntity model = modelConfigService.resolveAssistantModel(modelId);
        if (model == null) {
            saveMessage(convId, user, "assistant", null, DEGRADED_TEXT, null, true);
            return new AiChatVO(convId, DEGRADED_TEXT, null, "DEGRADED", true);
        }

        String name = model.getName();
        String apiKey = crypto.decrypt(model.getApiKeyCipher());
        try {
            AiReply reply = reactLoop(model, apiKey, convId, user);
            saveMessage(convId, user, "assistant", name, reply.content(), reply.trajectoryJson(), reply.degraded());
            log.info("AI assistant chat done conv={} model={} degraded={}", convId, model.getModel(), reply.degraded());
            return new AiChatVO(convId, reply.content(), name, "AI", reply.degraded());
        } catch (BizException e) {
            log.warn("AI assistant degraded (model={}): {}", model.getModel(), e.getMessage());
            String degraded = DEGRADED_TEXT + "（" + e.getMessage() + "）";
            saveMessage(convId, user, "assistant", name, degraded, "[]", true);
            return new AiChatVO(convId, degraded, name, "DEGRADED", true);
        }
    }

    private AiReply reactLoop(LlmModelConfigEntity model, String apiKey,
                              String conversationId, AuthUser user) {
        List<LlmChatClient.ToolMessage> messages = buildMessages(user, conversationId);
        List<LlmChatClient.ToolDef> tools = toolDefs();
        List<Map<String, Object>> trajectory = new ArrayList<>();

        int nudges = 0;
        for (int step = 1; step <= MAX_STEPS; step++) {
            LlmChatClient.ToolMessage resp =
                    llmClient.chatWithTools(model.getBaseUrl(), apiKey, model.getModel(), messages, tools);
            List<LlmChatClient.ToolCall> calls = resp.toolCalls();
            log.info("AI assistant step {} toolCalls={} content={}", step,
                    calls == null ? 0 : calls.size(), truncate(resp.content(), 200));
            if (calls == null || calls.isEmpty()) {
                if (resp.content() != null && !resp.content().isBlank()) {
                    return new AiReply(resp.content(), toJson(trajectory), false);
                }
                if (nudges < MAX_NUDGE) {
                    nudges++;
                    messages.add(resp);
                    messages.add(LlmChatClient.ToolMessage.user(
                            "请直接给出回答，若有需要可先调用合适工具获取数据，但不要只描述你打算做什么。"));
                    continue;
                }
                return new AiReply("模型未给出有效回复，已中止本次对话，请稍后重试。", toJson(trajectory), true);
            }
            messages.add(resp);
            for (LlmChatClient.ToolCall call : calls) {
                String name = call.function() != null ? call.function().name() : "";
                String argsStr = call.function() != null ? call.function().arguments() : null;
                String result;
                try {
                    Map<String, Object> args = parseArgs(argsStr);
                    result = truncate(toJson(toolExecutor.dispatch(name, args, user)), 4000);
                } catch (BizException be) {
                    result = "工具执行失败（" + name + "）: " + be.getMessage();
                } catch (Exception ex) {
                    result = "工具执行异常（" + name + "）: " + ex.getMessage();
                }
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("step", step);
                rec.put("tool", name);
                rec.put("args", argsStr);
                rec.put("result", truncate(result, 2000));
                trajectory.add(rec);
                messages.add(LlmChatClient.ToolMessage.tool(call.id(), result));
            }
        }
        return new AiReply("已达到最大处理步数（" + MAX_STEPS + "），请简化问题后重试。", toJson(trajectory), true);
    }

    private List<LlmChatClient.ToolMessage> buildMessages(AuthUser user, String convId) {
        List<LlmChatClient.ToolMessage> messages = new ArrayList<>();
        messages.add(LlmChatClient.ToolMessage.system(systemPrompt(user)));
        List<AiChatMessageEntity> all = messageRepository.findByConversationIdOrderByIdDesc(convId);
        List<AiChatMessageEntity> recent = all.size() > HISTORY_MESSAGES
                ? all.subList(0, HISTORY_MESSAGES) : new ArrayList<>(all);
        Collections.reverse(recent);
        for (AiChatMessageEntity m : recent) {
            String content = truncate(m.getContent(), 4000);
            if ("user".equals(m.getRole())) {
                messages.add(LlmChatClient.ToolMessage.user(content));
            } else {
                messages.add(new LlmChatClient.ToolMessage("assistant", content, null, null));
            }
        }
        return messages;
    }

    private String systemPrompt(AuthUser user) {
        String now = FMT.format(LocalDateTime.now());
        String username = user != null ? user.getUsername() : "unknown";
        String role = user != null ? user.getRole() : "?";
        return "你是 auto-deploy 部署平台的 AI 助手。当前时间：" + now
                + "，当前用户：" + username + "（角色 " + role + "）。\n"
                + "能力：1) 分析仪表盘数据并给出运维建议；2) 查询和管理服务器/数据库/项目/部署任务/服务实例/用户；\n"
                + "3) 基于已成功部署服务的文档搜索回答运维问题（先调 search_docs 检索，回答须注明文档来源）。\n"
                + "计数口径（重要）：问『服务器/数据库资源总数』(如『有几台服务器』『数据库共多少个』)时，\n"
                + "调用 get_dashboard，用它返回的 serverTotal / databaseTotal 作为全量总数回答；不要用 list_servers / list_databases 的返回条数，\n"
                + "因为这两个工具只返回当前用户可见项、可能少于总量（尤其非 ADMIN 用户可见数为 0）。\n"
                + "规则：所有数据必须来自工具调用结果，禁止编造；执行写操作（create/update/delete/cancel/redeploy/rollback/start/stop/restart 等）\n"
                + "前必须先复述将执行的操作并向用户确认语义（在回复中说明），一次只执行一个写操作；权限不足时直接说明，不要重试。";
    }

    public static List<LlmChatClient.ToolDef> toolDefs() {
        return List.of(
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("get_dashboard", "获取仪表盘统计总览", Map.of("type","object","properties",Map.of()))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("list_servers", "列出当前用户可见服务器", Map.of("type","object","properties",Map.of()))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("get_server", "查看服务器详情", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("create_server", "创建服务器", Map.of("type","object","properties",Map.of("name",Map.of("type","string"),"ip",Map.of("type","string"),"sshPort",Map.of("type","integer"),"sshUser",Map.of("type","string"),"password",Map.of("type","string"),"envTag",Map.of("type","string"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("update_server", "更新服务器", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("delete_server", "删除服务器", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("list_databases", "列出数据库", Map.of("type","object","properties",Map.of()))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("get_database", "查看数据库详情", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("create_database", "创建数据库", Map.of("type","object","properties",Map.of("name",Map.of("type","string"),"dbType",Map.of("type","string"),"host",Map.of("type","string"),"port",Map.of("type","integer"),"databaseName",Map.of("type","string"),"username",Map.of("type","string"),"password",Map.of("type","string"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("update_database", "更新数据库", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("delete_database", "删除数据库", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("list_projects", "列出项目", Map.of("type","object","properties",Map.of()))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("get_project", "查看项目详情", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("create_project", "创建项目", Map.of("type","object","properties",Map.of("name",Map.of("type","string"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("update_project", "更新项目", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("delete_project", "删除项目", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("list_project_versions", "列出项目版本", Map.of("type","object","properties",Map.of("projectId",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("list_deployments", "列出部署任务", Map.of("type","object","properties",Map.of()))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("get_deployment", "查看部署详情", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("get_deployment_steps", "查看部署步骤", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("create_deployment", "创建部署", Map.of("type","object","properties",Map.of("versionId",Map.of("type","integer"),"serverId",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("cancel_deployment", "取消部署", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("redeploy", "重新部署", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("rollback_deployment", "回退部署", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("list_services", "列出服务实例", Map.of("type","object","properties",Map.of()))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("get_service", "查看服务实例详情", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("start_service", "启动服务实例", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("stop_service", "停止服务实例", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("restart_service", "重启服务实例", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("delete_service", "删除服务实例", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("list_users", "列出用户（仅管理员）", Map.of("type","object","properties",Map.of()))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("get_user", "查看用户详情", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("create_user", "创建用户", Map.of("type","object","properties",Map.of("username",Map.of("type","string"),"password",Map.of("type","string"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("update_user", "更新用户", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("delete_user", "删除用户", Map.of("type","object","properties",Map.of("id",Map.of("type","integer"))))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction("search_docs", "检索部署文档知识库", Map.of("type","object","properties",Map.of("keyword",Map.of("type","string"))))));
    }

    private Map<String, Object> parseArgs(String args) {
        if (args == null || args.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object v = JSON.readValue(args, Object.class);
            if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) v;
                return m;
            }
            return new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void saveMessage(String convId, AuthUser user, String role, String modelName,
                             String content, String trajectoryJson, boolean degraded) {
        AiChatMessageEntity m = new AiChatMessageEntity(
                convId, user.getUserId(), user.getUsername(), role, modelName, content, trajectoryJson, degraded);
        messageRepository.save(m);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
