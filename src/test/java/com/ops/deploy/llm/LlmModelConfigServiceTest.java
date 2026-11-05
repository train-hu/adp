package com.ops.deploy.llm;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.crypto.CredentialCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmModelConfigService 单测：mock Repository + 真 CredentialCryptoService + mock LlmChatClient。
 * 覆盖：create（key 加密落库 + VO 脱敏掩码 + 重名 409 两路）、update（部分字段/换 key/改 enabled）、
 * get/delete 404、testConnection 成功与失败透传、testChat 截断、mask 规则、URL 规范化。
 */
@ExtendWith(MockitoExtension.class)
class LlmModelConfigServiceTest {

    private static final String KEK = "unit-test-kek-32-bytes-long-enough!";

    @Mock
    private LlmModelConfigRepository repository;

    private CredentialCryptoService crypto;
    private LlmChatClient client;
    private LlmModelConfigService service;

    @BeforeEach
    void setUp() {
        crypto = new CredentialCryptoService(KEK);
        client = org.mockito.Mockito.mock(LlmChatClient.class);
        service = new LlmModelConfigService(repository, crypto, client);
    }

    private LlmModelConfigEntity entity(Long id, String name, String baseUrl, String model) {
        LlmModelConfigEntity e = new LlmModelConfigEntity();
        e.setId(id);
        e.setName(name);
        e.setBaseUrl(baseUrl);
        e.setModel(model);
        e.setApiKeyCipher(crypto.encrypt("sk-1234567890abcdef"));
        e.setEnabled(true);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    @Test
    void create_encryptsKey_andMasksInVO() {
        when(repository.existsByName("deepseek-v4")).thenReturn(false);
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        var vo = service.create(new LlmModelConfigService.CreateRequest(
                "deepseek-v4", "http://api.example.com/api/v1/", "deepseek-v4", "sk-1234567890abcdef"));

        // 尾斜杠被规范化
        assertThat(vo.baseUrl()).isEqualTo("http://api.example.com/api/v1");
        // 脱敏掩码：前6 + **** + 后4
        assertThat(vo.apiKeyMask()).isEqualTo("sk-123****cdef");
        assertThat(vo.enabled()).isTrue();

        // 落库的是密文（能解密回原值，且与明文不同）
        org.mockito.ArgumentCaptor<LlmModelConfigEntity> captor =
                org.mockito.ArgumentCaptor.forClass(LlmModelConfigEntity.class);
        verify(repository).save(captor.capture());
        assertThat(crypto.decrypt(captor.getValue().getApiKeyCipher())).isEqualTo("sk-1234567890abcdef");
    }

    @Test
    void create_duplicateName_conflict() {
        when(repository.existsByName("x")).thenReturn(true);
        assertThatThrownBy(() -> service.create(
                new LlmModelConfigService.CreateRequest("x", "http://h/v1", "m", "sk-1234567890abcdef")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void create_raceIntegrityViolation_conflict() {
        when(repository.existsByName("x")).thenReturn(false);
        when(repository.save(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("unique"));
        assertThatThrownBy(() -> service.create(
                new LlmModelConfigService.CreateRequest("x", "http://h/v1", "m", "sk-1234567890abcdef")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void update_partialFields_keepsKeyWhenBlank() {
        LlmModelConfigEntity e = entity(1L, "a", "http://h/v1", "m1");
        when(repository.findById(1L)).thenReturn(Optional.of(e));

        var vo = service.update(1L, new LlmModelConfigService.UpdateRequest(
                "http://h2/v1", null, "", false));

        assertThat(vo.baseUrl()).isEqualTo("http://h2/v1");
        assertThat(vo.model()).isEqualTo("m1"); // null 不改
        assertThat(vo.enabled()).isFalse();
        // 空 key 不重新加密 → 密文未变，仍可解密
        assertThat(crypto.decrypt(e.getApiKeyCipher())).isEqualTo("sk-1234567890abcdef");
    }

    @Test
    void update_withNewKey_reencrypts() {
        LlmModelConfigEntity e = entity(1L, "a", "http://h/v1", "m1");
        byte[] oldCipher = e.getApiKeyCipher().clone();
        when(repository.findById(1L)).thenReturn(Optional.of(e));

        service.update(1L, new LlmModelConfigService.UpdateRequest(null, null, "sk-9999999999zzzzzz", null));

        assertThat(crypto.decrypt(e.getApiKeyCipher())).isEqualTo("sk-9999999999zzzzzz");
        assertThat(e.getApiKeyCipher()).isNotEqualTo(oldCipher);
    }

    @Test
    void get_notFound_404() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(9L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void testConnection_ok_returnsModels() {
        LlmModelConfigEntity e = entity(1L, "a", "http://h/v1", "m1");
        when(repository.findById(1L)).thenReturn(Optional.of(e));
        when(client.listModels("http://h/v1", "sk-1234567890abcdef")).thenReturn(List.of("m1", "m2"));

        var r = service.testConnection(1L);

        assertThat(r.ok()).isTrue();
        assertThat(r.availableModels()).containsExactly("m1", "m2");
    }

    @Test
    void testConnection_failure_passesThroughMessage() {
        LlmModelConfigEntity e = entity(1L, "a", "http://h/v1", "m1");
        when(repository.findById(1L)).thenReturn(Optional.of(e));
        when(client.listModels(anyString(), anyString()))
                .thenThrow(new BizException(ErrorCode.LLM_ERROR, "GET http://h/v1/models 连接失败: timeout"));

        var r = service.testConnection(1L);

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("连接失败");
        assertThat(r.availableModels()).isEmpty();
    }

    @Test
    void testChat_truncatesLongReply() {
        LlmModelConfigEntity e = entity(1L, "a", "http://h/v1", "m1");
        when(repository.findById(1L)).thenReturn(Optional.of(e));
        when(client.chat(eq("http://h/v1"), eq("sk-1234567890abcdef"), eq("m1"), anyString()))
                .thenReturn("x".repeat(3000));

        String reply = service.testChat(1L, null);

        assertThat(reply).hasSize(2003); // 2000 + "..."
        assertThat(reply.endsWith("...")).isTrue();
    }

    @Test
    void mask_shortKey_fullyMasked() {
        assertThat(LlmModelConfigService.mask("sk-1234567890")).isEqualTo("sk-123****7890"); // len 13，按规则掩码
        assertThat(LlmModelConfigService.mask("sk-12345678")).isEqualTo("****"); // len 11 ≤ 12 全掩
        // len 14 > 12：前6 "sk-123" + **** + 后4 "8901"
        assertThat(LlmModelConfigService.mask("sk-12345678901")).isEqualTo("sk-123****8901");
    }

    @Test
    void urlNormalization() {
        assertThat(LlmChatClient.baseV1("http://api.example.com/api/v1"))
                .isEqualTo("http://api.example.com/api/v1");
        assertThat(LlmChatClient.baseV1("http://api.example.com/api/v1/"))
                .isEqualTo("http://api.example.com/api/v1");
        assertThat(LlmChatClient.baseV1("http://host:30088"))
                .isEqualTo("http://host:30088/v1");
        assertThat(LlmChatClient.completionsUrl("http://host:30088"))
                .isEqualTo("http://host:30088/v1/chat/completions");
        assertThat(LlmChatClient.modelsUrl("http://host:30088/api/v1/"))
                .isEqualTo("http://host:30088/api/v1/models");
    }
}
