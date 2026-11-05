package com.ops.deploy.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * webhook 告警分发器：部署失败/回滚失败/服务健康探针 DOWN 时，
 * 异步 POST 到全部启用的飞书/钉钉机器人。
 *
 * <p>可靠性约定：
 * <ul>
 *   <li>{@link #notify} 本身不抛异常——查配置失败、逐条发送失败均仅 log.warn，
 *       绝不影响部署/探针主流程；</li>
 *   <li>{@code notify} 整体经 {@code @Async} 提交到专用线程池 webhookExecutor
 *       （与部署线程池 deployRunnerExecutor 隔离），调用方不等待 HTTP；</li>
 *   <li>HTTP 走 JDK {@link HttpClient}（无额外依赖），超时 5 秒，单条失败不影响其余配置。</li>
 * </ul>
 *
 * <p>消息体按 type 生成：
 * <ul>
 *   <li>FEISHU：{"msg_type":"text","content":{"text":"[部署平台][WARN] title\nmessage"}}；</li>
 *   <li>DINGTALK：{"msgtype":"text","text":{"content":"[部署平台][WARN] title\nmessage"}}。</li>
 * </ul>
 */
@Component
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 飞书机器人单次消息上限 512 字节，这里保守截断到 1024 字符（含标题前缀）。 */
    static final int MAX_TEXT_CHARS = 1024;

    /** 单次 HTTP 超时。 */
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final WebhookConfigRepository repository;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookNotifier(WebhookConfigRepository repository) {
        this(repository, HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build());
    }

    /** 可注入自定义 HttpClient（单测用）。 */
    WebhookNotifier(WebhookConfigRepository repository, HttpClient httpClient) {
        this.repository = repository;
        this.httpClient = httpClient;
    }

    /**
     * 发送一条告警：查全部启用配置后逐条发送。
     * 无配置直接返回；任何异常（查库/序列化/HTTP）仅 log.warn，绝不外抛。
     *
     * <p>{@code @Async}：整体提交到 webhookExecutor 线程池，调用方立即返回；
     * 注意单测直接 {@code new WebhookNotifier(...)} 时无 AOP 代理，走同步路径（同样可测）。
     */
    @Async("webhookExecutor")
    public void notify(String level, String title, String message) {
        List<WebhookConfigEntity> configs;
        try {
            configs = repository.findByEnabledTrue();
        } catch (RuntimeException e) {
            log.warn("webhook notify: load enabled configs failed: {}", e.getMessage());
            return;
        }
        if (configs.isEmpty()) {
            return;
        }
        for (WebhookConfigEntity cfg : configs) {
            try {
                postJson(cfg, buildBody(cfg, level, title, message));
            } catch (RuntimeException e) {
                log.warn("webhook notify: send to {} (id={}) failed: {}",
                        cfg.getName(), cfg.getId(), e.getMessage());
            }
        }
    }

    /**
     * 实际 HTTP 发送（POST JSON，超时 5 秒）。任何异常仅 log.warn（webhook 失败绝不影响主流程）。
     * 包内可见以便单测 spy 验证/替换。
     */
    void postJson(WebhookConfigEntity cfg, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.getUrl()))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("webhook {} (id={}) returned http {}: {}",
                        cfg.getName(), cfg.getId(), resp.statusCode(), abbreviate(resp.body()));
            }
        } catch (Exception e) {
            log.warn("webhook {} (id={}) post failed: {}", cfg.getName(), cfg.getId(), e.getMessage());
        }
    }

    /** 生成机器人消息体：飞书/钉钉 text 消息，文本 = "[部署平台][level] title\nmessage"。 */
    static String buildBody(WebhookConfigEntity cfg, String level, String title, String message) {
        String text = abbreviate(
                "[部署平台][" + level + "] " + title + "\n"
                        + (message == null ? "" : message),
                MAX_TEXT_CHARS);
        if ("FEISHU".equalsIgnoreCase(cfg.getType())) {
            return toJson(Map.of(
                    "msg_type", "text",
                    "content", Map.of("text", text)));
        }
        return toJson(Map.of(
                "msgtype", "text",
                "text", Map.of("content", text)));
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            // 入参为纯字符串 map，序列化实际不会失败；兜底保证不外抛
            return "{}";
        }
    }

    private static String abbreviate(String s) {
        return abbreviate(s, 512);
    }

    /** 截断到 maxLen 字符（超长尾部以 … 省略），null 视为空串。 */
    private static String abbreviate(String s, int maxLen) {
        return s == null ? "" : (s.length() > maxLen ? s.substring(0, maxLen) + "…" : s);
    }
}
