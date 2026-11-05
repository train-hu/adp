package com.ops.deploy.llm;

import com.ops.deploy.audit.Auditable;
import com.ops.deploy.common.Result;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUser;
import com.ops.deploy.security.RequireRole;
import com.ops.deploy.user.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * LLM 模型配置接口（/api/llm-models，M2 AI 能力，FR-11）。
 *
 * <p>读操作需 OPS；写操作（创建/更新/删除）需 ADMIN（api_key 属敏感凭证，
 * 与服务器凭证管理同等级）。test 探测与 chat 测试只回传诊断信息，不回传 key。
 */
@RestController
@RequestMapping("/api/llm-models")
public class LlmModelController {

    public record ChatTestRequest(@NotBlank @Size(max = 2000) String prompt) {
    }

    private final LlmModelConfigService service;

    public LlmModelController(LlmModelConfigService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole(Role.OPS)
    public Result<List<LlmModelConfigService.LlmModelVO>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/{id}")
    @RequireRole(Role.OPS)
    public Result<LlmModelConfigService.LlmModelVO> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @PostMapping
    @RequireRole(Role.ADMIN)
    @Auditable(action = "LLM_MODEL_CREATE", targetType = "LLM_MODEL")
    public ResponseEntity<Result<LlmModelConfigService.LlmModelVO>> create(
            @Valid @RequestBody LlmModelConfigService.CreateRequest req,
            @CurrentUser AuthUser user) {
        return ResponseEntity.status(201).body(Result.ok(service.create(req)));
    }

    @PutMapping("/{id}")
    @RequireRole(Role.ADMIN)
    @Auditable(action = "LLM_MODEL_UPDATE", targetType = "LLM_MODEL")
    public Result<LlmModelConfigService.LlmModelVO> update(@PathVariable Long id,
                                                           @Valid @RequestBody LlmModelConfigService.UpdateRequest req) {
        return Result.ok(service.update(id, req));
    }

    @PutMapping("/{id}/default")
    @RequireRole(Role.ADMIN)
    @Auditable(action = "LLM_MODEL_SET_DEFAULT", targetType = "LLM_MODEL")
    public Result<LlmModelConfigService.LlmModelVO> setDefault(@PathVariable Long id) {
        return Result.ok(service.setDefault(id));
    }

    @DeleteMapping("/{id}")
    @RequireRole(Role.ADMIN)
    @Auditable(action = "LLM_MODEL_DELETE", targetType = "LLM_MODEL")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok(null);
    }

    /** 连接探测（GET /models）。 */
    @PostMapping("/{id}/test")
    @RequireRole(Role.OPS)
    @Auditable(action = "LLM_MODEL_TEST", targetType = "LLM_MODEL")
    public Result<LlmModelConfigService.TestResult> test(@PathVariable Long id) {
        return Result.ok(service.testConnection(id));
    }

    /** 对话测试（一次最小 chat 调用，返回模型回复）。 */
    @PostMapping("/{id}/chat-test")
    @RequireRole(Role.OPS)
    public Result<String> chatTest(@PathVariable Long id, @Valid @RequestBody ChatTestRequest req) {
        return Result.ok(service.testChat(id, req.prompt()));
    }
}
