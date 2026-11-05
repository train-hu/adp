package com.ops.deploy.ssh;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.server.ServerService;
import com.ops.deploy.server.ServerVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SshService 入口参数校验单测：upload/launchBackground 在取凭证/执行之前
 * 先校验 remoteDir/fileName/taskName，非法形态抛 {@code BizException(BAD_REQUEST)}。
 */
@ExtendWith(MockitoExtension.class)
class SshServiceTest {

    @Mock
    private ServerService serverService;

    @Mock
    private SshExecutor sshExecutor;

    /** 桩掉"取 VO + 解密密码"，使合法参数用例能走到 executor。 */
    private void stubServer(Long id) {
        ServerVO vo = new ServerVO();
        vo.setId(id);
        vo.setIp("10.0.0.1");
        vo.setSshUser("deploy");
        when(serverService.get(id)).thenReturn(vo);
        when(serverService.getDecryptedPassword(id)).thenReturn("pw");
    }

    @Test
    void upload_invalidTaskPathParams_throwsBadRequestBeforeExecutor() {
        SshService service = new SshService(serverService, sshExecutor);

        assertThatThrownBy(() -> service.upload(1L, "../evil", "app.jar", new byte[0]))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.upload(1L, "/data", "../../evil.jar", new byte[0]))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.upload(1L, "data", "app.jar", new byte[0]))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST));

        // 校验先于执行：底层 executor 从未被调用
        verifyNoInteractions(sshExecutor);
    }

    @Test
    void launchBackground_invalidTaskName_throwsBadRequestBeforeExecutor() {
        SshService service = new SshService(serverService, sshExecutor);

        assertThatThrownBy(() -> service.launchBackground(1L, "/opt/app", "cmd", "../../evil"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.launchBackground(1L, "/opt/app", "cmd", "a/b"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST));

        verifyNoInteractions(sshExecutor);
    }

    @Test
    void upload_validParams_delegatesToExecutor() {
        stubServer(1L);
        when(sshExecutor.upload(eq(1L), any(SshTarget.class), eq("/data"), eq("app.jar"), any(byte[].class)))
                .thenReturn(true);

        boolean ok = new SshService(serverService, sshExecutor)
                .upload(1L, "/data", "app.jar", new byte[]{1});

        assertThat(ok).isTrue();
    }

    @Test
    void uploadStream_invalidPathParams_throwsBadRequestBeforeExecutor() {
        SshService service = new SshService(serverService, sshExecutor);

        assertThatThrownBy(() -> service.uploadStream(1L, "../evil", "app.zip",
                new java.io.ByteArrayInputStream(new byte[0]), 0))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.uploadStream(1L, "/data", "../../evil.zip",
                new java.io.ByteArrayInputStream(new byte[0]), 0))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST));

        // 校验先于执行：底层 executor 从未被调用
        verifyNoInteractions(sshExecutor);
    }

    @Test
    void uploadStream_validParams_delegatesToExecutor() {
        stubServer(1L);
        when(sshExecutor.uploadStream(eq(1L), any(SshTarget.class), eq("/data"), eq("app.zip"),
                any(java.io.InputStream.class), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);

        boolean ok = new SshService(serverService, sshExecutor)
                .uploadStream(1L, "/data", "app.zip", new java.io.ByteArrayInputStream(new byte[]{1}), 1);

        assertThat(ok).isTrue();
    }

    @Test
    void uploadStream_withExpectedSha_delegatesToExecutorUploadOverload() {
        // Task 6 重载：expectedSha256 由调用方传入（来自 package_version 表），走 executor.upload
        stubServer(1L);
        when(sshExecutor.upload(eq(1L), any(SshTarget.class), eq("/data"), eq("app.zip"),
                any(java.io.InputStream.class), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);

        boolean ok = new SshService(serverService, sshExecutor)
                .uploadStream(1L, "/data", "app.zip", new java.io.ByteArrayInputStream(new byte[]{1}), 1,
                        "a".repeat(64));

        assertThat(ok).isTrue();
    }

    @Test
    void uploadStream_withExpectedSha_invalidPathParams_throwsBadRequestBeforeExecutor() {
        SshService service = new SshService(serverService, sshExecutor);

        assertThatThrownBy(() -> service.uploadStream(1L, "../evil", "app.zip",
                new java.io.ByteArrayInputStream(new byte[0]), 0, "a".repeat(64)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.BAD_REQUEST));

        verifyNoInteractions(sshExecutor);
    }

    @Test
    void launchBackground_validParams_delegatesToExecutor() {
        stubServer(1L);
        when(sshExecutor.launchBackground(anyLong(), any(SshTarget.class),
                anyString(), anyString(), anyString())).thenReturn("/opt/app/.deploy/task-1.status");

        String handle = new SshService(serverService, sshExecutor)
                .launchBackground(1L, "/opt/app", "run.sh", "task-1");

        assertThat(handle)
                .isEqualTo("/opt/app/.deploy/task-1.status");
    }

    @Test
    void executeWithRetry_validParams_delegatesToExecutor() {
        stubServer(1L);
        when(sshExecutor.executeWithRetry(anyLong(), any(SshTarget.class),
                anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.ok("ok", 0));

        CommandResult r = new SshService(serverService, sshExecutor)
                .executeWithRetry(1L, "cmd", 5000, 2);

        assertThat(r.getExitCode()).isEqualTo(0);
    }
}
