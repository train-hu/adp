package com.ops.deploy.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议 LLM 调用客户端（M2 AI 能力，FR-11）。
 *
 * <p>统一 {@code base_url + api_key + model} 三元组发起请求，云端 API 与本地私有化
 * 部署（如 vLLM / one-api 网关，{@code http://192.168.x.x:30088/api/v1}）同构。
 * 本类只做协议层：chat/completions（非流式）与 models 列表；重试策略为固定 2 次尝试
 * （仅对网络类失败重试，HTTP 4xx 业务错误不重试——key 错误/模型不存在重试无意义）。
 *
 * <p>apiKey 由调用方解密后传入，本类不落日志明文（仅记录 base_url 与 model）。
 */
@Component
public class LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(LlmChatClient.class);

    /** 默认 2 次尝试（首次 + 1 次网络失败重试），chat 大模型响应慢故重试间不加退避。 */
    private static final int MAX_ATTEMPTS = 2;

    private final RestClient restClient;

    /**
     * @param connectTimeoutSec 连接超时（秒）
     * @param readTimeoutSec    读超时（秒）——大模型生成可能较慢，默认给足
     */
    public LlmChatClient(int connectTimeoutSec, int readTimeoutSec) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(connectTimeoutSec).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(readTimeoutSec).toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 非流式 chat/completions，返回首条消息 content。
     *
     * @param baseUrl 以 /v1 结尾（容忍缺尾斜杠，自动补正）
     * @param prompt  单条 user 消息
     */
    public String chat(String baseUrl, String apiKey, String model, String prompt) {
        Object body = new ChatRequestBody(
                List.of(new Message("user", prompt)),
                model,
                false);
        ChatResponseBody resp = attempt("POST " + completionsUrl(baseUrl), baseUrl, model, () ->
                restClient.post()
                        .uri(completionsUrl(baseUrl))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(ChatResponseBody.class));
        if (resp == null || resp.choices == null || resp.choices.isEmpty()) {
            throw new BizException(ErrorCode.LLM_ERROR, "LLM 返回空 choices: " + baseUrl + " " + model);
        }
        return resp.choices.get(0).message != null ? resp.choices.get(0).message.content : "";
    }

    /**
     * 多轮对话 + 工具调用（OpenAI 兼容 chat/completions，tools 协议）。
     *
     * <p>返回首条 choice 的完整 message：assistant 可能给出 tool_calls
     * （需调用方执行工具并以 role=tool 消息续传），或给出 content（最终答复）。
     * 重试策略与 {@link #chat} 相同（2 次尝试，仅网络类失败重试）。
     *
     * @param messages 对话历史（system/user/assistant/tool 任意组合）
     * @param tools    工具定义列表（function schema），null 表示无工具
     */
    public ToolMessage chatWithTools(String baseUrl, String apiKey, String model,
                                     List<ToolMessage> messages, List<ToolDef> tools) {
        Object body = new ChatWithToolsRequestBody(
                messages, model, tools == null ? List.of() : tools, null);
        ChatWithToolsResponseBody resp = attempt("POST " + completionsUrl(baseUrl), baseUrl, model, () ->
                restClient.post()
                        .uri(completionsUrl(baseUrl))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(ChatWithToolsResponseBody.class));
        if (resp == null || resp.choices() == null || resp.choices().isEmpty()
                || resp.choices().get(0).message() == null) {
            throw new BizException(ErrorCode.LLM_ERROR, "LLM 返回空 choices: " + baseUrl + " " + model);
        }
        return resp.choices().get(0).message();
    }

    /** 拉取该端点可用模型 id 列表（GET /models，OpenAI 兼容）。 */
    public List<String> listModels(String baseUrl, String apiKey) {
        ModelsResponseBody resp = attempt("GET " + modelsUrl(baseUrl), baseUrl, null, () ->
                restClient.get()
                        .uri(modelsUrl(baseUrl))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .retrieve()
                        .body(ModelsResponseBody.class));
        if (resp == null || resp.data == null) {
            return List.of();
        }
        return resp.data.stream().map(m -> m.id).toList();
    }

    /** 2 次尝试：网络类失败（连接超时/拒绝/重置）重试；HTTP 4xx/5xx 直接抛出（含服务端 message）。 */
    private <T> T attempt(String label, String baseUrl, String model, java.util.function.Supplier<T> call) {
        for (int i = 1; i <= MAX_ATTEMPTS; i++) {
            try {
                return call.get();
            } catch (ResourceAccessException e) {
                if (i == MAX_ATTEMPTS) {
                    throw new BizException(ErrorCode.LLM_ERROR,
                            label + " 连接失败(" + baseUrl + (model != null ? " " + model : "") + "): " + e.getMessage());
                }
                log.info("LLM 请求网络失败，重试 1/1: {} ({})", label, e.getMessage());
            } catch (RestClientResponseException e) {
                // 4xx/5xx：透传服务端 message（截断防超长），不重试
                String detail = e.getResponseBodyAsString();
                if (detail != null && detail.length() > 300) {
                    detail = detail.substring(0, 300) + "...";
                }
                throw new BizException(ErrorCode.LLM_ERROR,
                        label + " HTTP " + e.getStatusCode().value() + ": " + detail);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** 容忍 base_url 是否带尾斜杠/是否已含 /v1，统一拼 completions 路径。 */
    static String completionsUrl(String baseUrl) {
        return baseV1(baseUrl) + "/chat/completions";
    }

    static String modelsUrl(String baseUrl) {
        return baseV1(baseUrl) + "/models";
    }

    /** 规范化到 .../v1：已含 /v1 结尾原样返回（去尾斜杠），否则补 /v1。 */
    static String baseV1(String baseUrl) {
        String u = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (u.endsWith("/v1")) {
            return u;
        }
        return u + "/v1";
    }

    // ---- OpenAI 兼容 DTO（record，Jackson 反序列化）----

    public record ChatRequestBody(List<Message> messages, String model, boolean stream) {
    }

    public record Message(String role, String content) {
    }

    public record ChatResponseBody(List<Choice> choices) {
    }

    public record Choice(Message message) {
    }

    public record ModelsResponseBody(List<ModelInfo> data) {
    }

    public record ModelInfo(String id) {
    }

    // ---- 多轮对话 + 工具调用 DTO（Jackson 反序列化，OpenAI 兼容）----

    /** 通用消息：role ∈ {system, user, assistant, tool}；tool 消息带 toolCallId。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolMessage(String role, String content,
                              @JsonProperty("tool_calls") List<ToolCall> toolCalls,
                              @JsonProperty("tool_call_id") String toolCallId) {
        public static ToolMessage system(String content) {
            return new ToolMessage("system", content, null, null);
        }

        public static ToolMessage user(String content) {
            return new ToolMessage("user", content, null, null);
        }

        /** 工具执行结果回传（content 为序列化 JSON 字符串）。 */
        public static ToolMessage tool(String toolCallId, String content) {
            return new ToolMessage("tool", content, null, toolCallId);
        }
    }

    /** assistant 返回的一次工具调用。arguments 为 JSON 字符串（OpenAI 原样透传）。 */
    public record ToolCall(String id, String type, ToolCallFunction function) {
    }

    public record ToolCallFunction(String name, String arguments) {
    }

    /** 工具定义：function 名 + 描述 + JSON Schema 参数。 */
    public record ToolDef(String type, ToolDefFunction function) {
    }

    public record ToolDefFunction(String name, String description, Map<String, Object> parameters) {
    }

    public record ChatWithToolsRequestBody(List<ToolMessage> messages, String model,
                                           List<ToolDef> tools, String toolChoice) {
    }

    public record ChatWithToolsResponseBody(List<ToolChoiceChoice> choices) {
    }

    public record ToolChoiceChoice(ToolMessage message) {
    }
}
