package com.ops.deploy.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmChatClient DTO 单测：锁定 OpenAI 兼容协议的蛇形命名映射回归。
 *
 * <p>本地私有化模型（OpenAI 兼容）返回 {@code tool_calls}/{@code tool_call_id}（snake_case），
 * 而 DTO record 字段为 {@code toolCalls}/{@code toolCallId}（camelCase）。必须依赖
 * {@code @JsonProperty} 显式映射，否则 Jackson 按字段名匹配会把 function call 整体丢弃
 * （表现为 toolCalls 恒为 null，AI 永远降级规则引擎）。同时回传 assistant 消息时不能带
 * {@code "tool_calls":null}（部分端点会拒收），由 {@code @JsonInclude(NON_NULL)} 保证。
 */
class LlmChatClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializes_openAiToolCalls_snakeCase() throws Exception {
        String json = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [
                        {
                          "id": "call_abc",
                          "type": "function",
                          "function": {
                            "name": "inspect_package",
                            "arguments": "{}"
                          }
                        }
                      ]
                    }
                  }]
                }
                """;
        LlmChatClient.ChatWithToolsResponseBody body =
                mapper.readValue(json, LlmChatClient.ChatWithToolsResponseBody.class);

        LlmChatClient.ToolMessage msg = body.choices().get(0).message();
        assertThat(msg.role()).isEqualTo("assistant");
        assertThat(msg.content()).isNull();
        assertThat(msg.toolCalls()).hasSize(1);
        LlmChatClient.ToolCall call = msg.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_abc");
        assertThat(call.type()).isEqualTo("function");
        assertThat(call.function().name()).isEqualTo("inspect_package");
        assertThat(call.function().arguments()).isEqualTo("{}");
    }

    @Test
    void deserializes_plainAssistant_contentOnly() throws Exception {
        String json = """
                {
                  "choices": [{
                    "message": { "role": "assistant", "content": "done" }
                  }]
                }
                """;
        LlmChatClient.ChatWithToolsResponseBody body =
                mapper.readValue(json, LlmChatClient.ChatWithToolsResponseBody.class);
        LlmChatClient.ToolMessage msg = body.choices().get(0).message();
        assertThat(msg.content()).isEqualTo("done");
        assertThat(msg.toolCalls()).isNull();
        assertThat(msg.toolCallId()).isNull();
    }

    @Test
    void serializes_assistantToolMessage_usesSnakeCaseAndOmitsNulls() throws Exception {
        LlmChatClient.ToolMessage msg =
                new LlmChatClient.ToolMessage("assistant", null,
                        List.of(new LlmChatClient.ToolCall("call_abc", "function",
                                new LlmChatClient.ToolCallFunction("inspect_package", "{}"))),
                        null);
        String json = mapper.writeValueAsString(msg);
        // 回传必须用蛇形 key，且不能带 tool_calls:null / tool_call_id:null
        assertThat(json).contains("\"tool_calls\"");
        assertThat(json).doesNotContain("toolCalls");
        assertThat(json).doesNotContain("null");
    }

    @Test
    void serializes_plainMessage_omitsToolFields() throws Exception {
        LlmChatClient.ToolMessage msg = LlmChatClient.ToolMessage.user("hello");
        String json = mapper.writeValueAsString(msg);
        assertThat(json).contains("\"role\":\"user\"");
        assertThat(json).contains("\"content\":\"hello\"");
        assertThat(json).doesNotContain("tool_calls");
        assertThat(json).doesNotContain("tool_call_id");
    }

    @Test
    void serializes_toolResultMessage_usesToolCallId() throws Exception {
        LlmChatClient.ToolMessage msg = LlmChatClient.ToolMessage.tool("call_abc", "{\"ok\":true}");
        String json = mapper.writeValueAsString(msg);
        assertThat(json).contains("\"tool_call_id\":\"call_abc\"");
        assertThat(json).contains("\"role\":\"tool\"");
        assertThat(json).doesNotContain("toolCallId");
        assertThat(json).doesNotContain("tool_calls");
    }
}
