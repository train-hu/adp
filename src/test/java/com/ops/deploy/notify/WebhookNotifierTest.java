package com.ops.deploy.notify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * webhook 告警分发 {@link WebhookNotifier} 单测（mock 配置仓库 + spy 拦截 HTTP 发送）。
 *
 * <p>覆盖：无配置不发送、按 type 生成正确 JSON body（飞书 msg_type / 钉钉 msgtype）、
 * 发送异常被吞掉不外抛、查库异常不外抛、非 2xx 响应仅记日志。
 */
@ExtendWith(MockitoExtension.class)
class WebhookNotifierTest {

    @Mock
    private WebhookConfigRepository repository;

    /** spy 拦截 postJson（HTTP 发送），避免真实网络调用，同时可验证 body。 */
    private WebhookNotifier notifier;

    @Captor
    private ArgumentCaptor<String> bodyCaptor;

    @BeforeEach
    void setUp() {
        HttpClient httpClient = HttpClient.newHttpClient();
        notifier = spy(new WebhookNotifier(repository, httpClient));
    }

    private static WebhookConfigEntity config(String type, boolean enabled) {
        WebhookConfigEntity cfg = new WebhookConfigEntity();
        cfg.setId(1L);
        cfg.setName("bot-" + type.toLowerCase());
        cfg.setType(type);
        cfg.setUrl("https://example.com/hook/" + type.toLowerCase());
        cfg.setEnabled(enabled);
        return cfg;
    }

    @Test
    @DisplayName("无启用配置：直接返回，不发送任何 HTTP 请求")
    void noEnabledConfig_sendsNothing() {
        when(repository.findByEnabledTrue()).thenReturn(List.of());

        notifier.notify("WARN", "部署失败", "项目 demo 部署 1 失败");

        verify(notifier, never()).postJson(any(), any());
    }

    @Test
    @DisplayName("飞书配置：body 含 msg_type=text + content.text（标题前缀 [部署平台][WARN]）")
    void feishuBodyFormat() {
        when(repository.findByEnabledTrue()).thenReturn(List.of(config("FEISHU", true)));
        // stub postJson 为 no-op：仅验证 body，不发起真实 HTTP
        doNothing().when(notifier).postJson(any(), any());

        notifier.notify("WARN", "部署失败", "项目 demo 部署 1 失败");

        verify(notifier).postJson(any(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"msg_type\":\"text\"");
        assertThat(body).contains("\"content\"");
        assertThat(body).contains("[部署平台][WARN] 部署失败");
        assertThat(body).contains("项目 demo 部署 1 失败");
        // 钉钉字段不得混入
        assertThat(body).doesNotContain("msgtype");
    }

    @Test
    @DisplayName("钉钉配置：body 含 msgtype=text + text.content（标题前缀 [部署平台][WARN]）")
    void dingtalkBodyFormat() {
        when(repository.findByEnabledTrue()).thenReturn(List.of(config("DINGTALK", true)));
        doNothing().when(notifier).postJson(any(), any());

        notifier.notify("WARN", "部署回滚", "详情内容");

        verify(notifier).postJson(any(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"msgtype\":\"text\"");
        assertThat(body).contains("\"text\"");
        assertThat(body).contains("\"content\":\"[部署平台][WARN] 部署回滚\\n详情内容\"");
        assertThat(body).doesNotContain("msg_type");
    }

    @Test
    @DisplayName("多配置：每条各发一次；单条发送抛异常不影响其余配置且不外抛")
    void multiConfig_oneFails_othersStillSentAndNoThrow() {
        when(repository.findByEnabledTrue()).thenReturn(List.of(
                config("FEISHU", true), config("DINGTALK", true)));
        doThrow(new RuntimeException("boom")).doNothing()
                .when(notifier).postJson(any(), any());

        // 任何异常被吞：绝不外抛（webhook 失败不影响主流程）
        assertThatCode(() -> notifier.notify("WARN", "t", "m"))
                .doesNotThrowAnyException();

        verify(notifier, times(2)).postJson(any(), any());
    }

    @Test
    @DisplayName("发送层自身异常（postJson 抛错）：notify 吞掉仅 log，不外抛")
    void postJsonThrows_notifyDoesNotThrow() {
        when(repository.findByEnabledTrue()).thenReturn(List.of(config("FEISHU", true)));
        doThrow(new RuntimeException("connect timeout")).when(notifier).postJson(any(), any());

        assertThatCode(() -> notifier.notify("WARN", "t", "m")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("查库异常：notify 吞掉仅 log，不外抛")
    void repositoryThrows_notifyDoesNotThrow() {
        when(repository.findByEnabledTrue()).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> notifier.notify("WARN", "t", "m")).doesNotThrowAnyException();
        verify(notifier, never()).postJson(any(), any());
    }

    @Test
    @DisplayName("message 为 null：不 NPE，body 仅含标题")
    void nullMessage_noNpe() {
        when(repository.findByEnabledTrue()).thenReturn(List.of(config("FEISHU", true)));
        doNothing().when(notifier).postJson(any(), any());

        assertThatCode(() -> notifier.notify("WARN", "标题", null)).doesNotThrowAnyException();

        verify(notifier).postJson(any(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("[部署平台][WARN] 标题");
    }

    @Test
    @DisplayName("长消息：body 文本被截断（不超上限），不 NPE")
    void longMessageTruncated() {
        when(repository.findByEnabledTrue()).thenReturn(List.of(config("FEISHU", true)));
        doNothing().when(notifier).postJson(any(), any());
        String longMsg = "长".repeat(5000);

        assertThatCode(() -> notifier.notify("WARN", "t", longMsg)).doesNotThrowAnyException();

        verify(notifier).postJson(any(), bodyCaptor.capture());
        // 截断后 body 中的"长"字符数不超过上限（1024 字符文本 + JSON 转义余量）
        long count = bodyCaptor.getValue().chars().filter(c -> c == '长').count();
        assertThat(count).isLessThanOrEqualTo(WebhookNotifier.MAX_TEXT_CHARS);
    }

    @Test
    @DisplayName("postJson：非 2xx 响应仅记日志，不外抛（HTTP 层兜底）")
    void postJson_non2xxDoesNotThrow() throws Exception {
        // 起本机桩 HTTP 服务返回 500，验证 postJson 不抛
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/hook", exchange -> {
            byte[] body = "server error".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WebhookConfigEntity cfg = config("FEISHU", true);
            cfg.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
            WebhookNotifier plain = new WebhookNotifier(repository, HttpClient.newHttpClient());

            assertThatCode(() -> plain.postJson(cfg, "{}")).doesNotThrowAnyException();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("postJson：连接异常仅记日志，不外抛")
    void postJson_connectFailureDoesNotThrow() throws Exception {
        // 指向不可达地址（127.0.0.1:1 即时拒绝），验证异常被吞
        WebhookConfigEntity cfg = config("FEISHU", true);
        cfg.setUrl("http://127.0.0.1:1/hook");
        WebhookNotifier plain = new WebhookNotifier(repository, HttpClient.newHttpClient());

        assertThatCode(() -> plain.postJson(cfg, "{}")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("buildBody：level 直接作为标题前缀（INFO/WARN 均可）")
    void buildBodyLevelPrefix() {
        WebhookConfigEntity feishu = config("FEISHU", true);
        assertThat(WebhookNotifier.buildBody(feishu, "INFO", "服务恢复运行", "ok"))
                .contains("[部署平台][INFO] 服务恢复运行");
        assertThat(WebhookNotifier.buildBody(config("DINGTALK", true), "WARN", "x", "y"))
                .contains("[部署平台][WARN] x");
    }
}
