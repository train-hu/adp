package com.ops.deploy.ai;

import com.ops.deploy.audit.Auditable;
import com.ops.deploy.common.Result;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUser;
import com.ops.deploy.security.RequireRole;
import com.ops.deploy.user.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 自主部署接口（/api/ai-deployments，M2 AI 能力，FR-02/FR-05）。
 *
 * <p>AI 以 ReAct 循环解析部署包 + 只读探测目标机，产出结构化部署方案（DRAFT）；
 * 人工确认（可微调 startCmd/port/healthPath）后才由既有安全管线执行。
 * JWT 认证由 JwtAuthFilter 统一拦截，角色由 {@code @RequireRole} 切面校验，写操作由 {@code @Auditable} 审计。
 * generate / confirm / abandon 需 OPS；get / list 无角色限制（任何已登录角色可读）。
 */
@RestController
@RequestMapping("/api/ai-deployments")
public class AiDeployController {

    private final AiDeployService service;

    public AiDeployController(AiDeployService service) {
        this.service = service;
    }

    /** AI 生成部署方案（DRAFT）。modelId 可选，缺省取第一个启用模型；LLM 不可用时降级规则引擎。 */
    @PostMapping("/generate")
    @RequireRole(Role.OPS)
    @Auditable(action = "AI_DEPLOY_GENERATE", targetType = "AI_DEPLOYMENT_PLAN")
    public Result<AiDeployService.AiPlanVO> generate(@RequestBody GenerateRequest req,
                                                     @CurrentUser AuthUser user) {
        return Result.ok(service.generate(req.versionId(), req.serverId(), req.modelId(), user, req.mode(), req.port()));
    }

    @GetMapping
    public Result<List<AiDeployService.AiPlanVO>> list(@RequestParam Long projectId,
                                                       @CurrentUser AuthUser user) {
        return Result.ok(service.listByProject(projectId, user));
    }

    @GetMapping("/{id}")
    public Result<AiDeployService.AiPlanVO> get(@PathVariable Long id, @CurrentUser AuthUser user) {
        return Result.ok(service.get(id, user));
    }

    /** 确认方案并执行：回填项目类型 → 调既有部署管线创建任务。body 可空（空则用方案原值）。 */
    @PostMapping("/{id}/confirm")
    @RequireRole(Role.OPS)
    @Auditable(action = "AI_DEPLOY_CONFIRM", targetType = "AI_DEPLOYMENT_PLAN")
    public Result<AiDeployService.AiPlanVO> confirm(@PathVariable Long id,
                                                    @RequestBody(required = false) AiDeployService.ConfirmRequest req,
                                                    @CurrentUser AuthUser user) {
        return Result.ok(service.confirm(id, req, user));
    }

    /** 放弃方案（仅 DRAFT 可放弃）。 */
    @PostMapping("/{id}/abandon")
    @RequireRole(Role.OPS)
    @Auditable(action = "AI_DEPLOY_ABANDON", targetType = "AI_DEPLOYMENT_PLAN")
    public Result<Void> abandon(@PathVariable Long id, @CurrentUser AuthUser user) {
        service.abandon(id, user);
        return Result.ok(null);
    }

    /** 生成请求：versionId/serverId 必填，modelId/mode/port 可选（mode=用户手选部署方式，port=用户手选服务端口）。 */
    public record GenerateRequest(Long versionId, Long serverId, Long modelId, String mode, Integer port) {
    }
}
