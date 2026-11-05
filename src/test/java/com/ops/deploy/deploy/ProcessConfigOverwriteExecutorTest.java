package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.project.ProjectConfigFileEntity;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProcessConfigOverwriteExecutor}（方案 C 包内文件覆盖）单测：
 * <ul>
 *   <li>{@code isPathKey} / {@code pathKeys}：相对路径型 fileKey 识别与筛选；</li>
 *   <li>{@code targetPath} / {@code buildWriteCommand}：覆盖目标路径与 quoted heredoc 命令构造（纯函数）；</li>
 *   <li>{@code execute}：逐文件写入 + 失败抛 SSH_ERROR + detail 汇总。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProcessConfigOverwriteExecutorTest {

    @Mock
    private SshService sshService;

    @Captor
    private ArgumentCaptor<String> cmdCaptor;

    private ProcessConfigOverwriteExecutor executor;

    private ProjectConfigFileEntity cfg(String key, String content) {
        ProjectConfigFileEntity e = new ProjectConfigFileEntity();
        e.setFileKey(key);
        e.setContent(content);
        return e;
    }

    private CommandResult ok() {
        return CommandResult.ok("", 0);
    }

    // ---------- isPathKey / pathKeys ----------

    @Test
    @DisplayName("isPathKey：包内文件型 key（含子路径与根级文件）真；4 个特殊 key 与空值否")
    void isPathKey() {
        assertThat(ProcessConfigOverwriteExecutor.isPathKey("conf/config.toml")).isTrue();
        assertThat(ProcessConfigOverwriteExecutor.isPathKey("conf/plugins/a/b.toml")).isTrue();
        // 根级文件（不含 /）也是包内覆盖目标（szhmh 缺陷回归：requirements.txt 漏判）
        assertThat(ProcessConfigOverwriteExecutor.isPathKey("requirements.txt")).isTrue();
        assertThat(ProcessConfigOverwriteExecutor.isPathKey("package.json")).isTrue();
        assertThat(ProcessConfigOverwriteExecutor.isPathKey("nginx")).isFalse();
        assertThat(ProcessConfigOverwriteExecutor.isPathKey("compose")).isFalse();
        assertThat(ProcessConfigOverwriteExecutor.isPathKey("env")).isFalse();
        assertThat(ProcessConfigOverwriteExecutor.isPathKey("dockerfile")).isFalse();
        assertThat(ProcessConfigOverwriteExecutor.isPathKey("")).isFalse();
        assertThat(ProcessConfigOverwriteExecutor.isPathKey("   ")).isFalse();
        assertThat(ProcessConfigOverwriteExecutor.isPathKey(null)).isFalse();
    }

    @Test
    @DisplayName("pathKeys：筛出包内文件型（子路径 + 根级），排除特殊 key，保持顺序；null 输入返回空")
    void pathKeys() {
        List<ProjectConfigFileEntity> all = List.of(
                cfg("nginx", "server{}"),
                cfg("conf/config.toml", "[global]\nhostname = \"x\""),
                cfg("requirements.txt", "PyJWT>=2.6"),
                cfg("env", "FOO=1"),
                cfg("conf/plugins/disk.toml", "[[outputs]]"));
        List<ProjectConfigFileEntity> paths = ProcessConfigOverwriteExecutor.pathKeys(all);
        assertThat(paths).hasSize(3);
        assertThat(paths.get(0).getFileKey()).isEqualTo("conf/config.toml");
        assertThat(paths.get(1).getFileKey()).isEqualTo("requirements.txt");
        assertThat(paths.get(2).getFileKey()).isEqualTo("conf/plugins/disk.toml");
        assertThat(ProcessConfigOverwriteExecutor.pathKeys(null)).isEmpty();
        assertThat(ProcessConfigOverwriteExecutor.pathKeys(List.of())).isEmpty();
    }

    // ---------- targetPath / buildWriteCommand ----------

    @Test
    @DisplayName("targetPath：拼 {extract}/{relPath}，workdir 尾斜杠规整")
    void targetPath() {
        assertThat(ProcessConfigOverwriteExecutor.targetPath("/opt/deploy/categraf/16", "conf/config.toml"))
                .isEqualTo("/opt/deploy/categraf/16/extract/conf/config.toml");
        assertThat(ProcessConfigOverwriteExecutor.targetPath("/opt/deploy/categraf/16/", "conf/config.toml"))
                .isEqualTo("/opt/deploy/categraf/16/extract/conf/config.toml");
    }

    @Test
    @DisplayName("buildWriteCommand：先 mkdir -p 父目录 + quoted heredoc 写目标，内容原样")
    void buildWriteCommand() {
        String content = "[global]\nhostname = \"web-1\"\ninterval = 10\n";
        String cmd = ProcessConfigOverwriteExecutor.buildWriteCommand(
                "/opt/deploy/categraf/16", "conf/config.toml", content);
        // 路径无特殊字符时 ShellQuoter 原样输出（不加引号），含特殊字符才单引号包裹
        assertThat(cmd).contains("mkdir -p /opt/deploy/categraf/16/extract/conf");
        assertThat(cmd).contains("cat > /opt/deploy/categraf/16/extract/conf/config.toml");
        assertThat(cmd).contains("<< 'CFG_EOF_");
        assertThat(cmd).contains("[global]\nhostname = \"web-1\"\ninterval = 10\n");
        // 末尾以定界符结束（独立成行）
        assertThat(cmd).matches("(?s).*\\nCFG_EOF_[0-9a-f-]+$");
    }

    // ---------- execute ----------

    @Test
    @DisplayName("execute：多文件逐个写入成功，detail 汇总 written/count")
    void execute_multiWriteSuccess() {
        executor = new ProcessConfigOverwriteExecutor(sshService);
        when(sshService.execute(anyLong(), anyString())).thenReturn(ok());
        List<ProjectConfigFileEntity> configs = List.of(
                cfg("conf/config.toml", "a=1"),
                cfg("nginx", "ignore"), // 非路径 key，应被跳过
                cfg("conf/plugins/disk.toml", "b=2"));
        Map<String, Object> detail = executor.execute(7L, "/opt/deploy/categraf/16", configs);
        assertThat(detail.get("count")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<String> written = (List<String>) detail.get("written");
        assertThat(written).containsExactly("conf/config.toml", "conf/plugins/disk.toml");
        verify(sshService, org.mockito.Mockito.times(2)).execute(anyLong(), anyString());
    }

    @Test
    @DisplayName("execute：无路径 key 时不发起 SSH，detail 为空")
    void execute_noPathKeys() {
        executor = new ProcessConfigOverwriteExecutor(sshService);
        Map<String, Object> detail = executor.execute(7L, "/opt/deploy/categraf/16",
                List.of(cfg("nginx", "x"), cfg("compose", "y")));
        assertThat(detail.get("count")).isEqualTo(0);
        verify(sshService, org.mockito.Mockito.never()).execute(anyLong(), anyString());
    }

    @Test
    @DisplayName("execute：任一文件写入失败 → SSH_ERROR，含 fileKey 与 exit")
    void execute_writeFailure() {
        executor = new ProcessConfigOverwriteExecutor(sshService);
        CommandResult fail = CommandResult.fail(1, "", "permission denied");
        when(sshService.execute(anyLong(), anyString())).thenReturn(fail);
        List<ProjectConfigFileEntity> configs = List.of(cfg("conf/config.toml", "a=1"));
        assertThatThrownBy(() -> executor.execute(7L, "/opt/deploy/categraf/16", configs))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("conf/config.toml")
                .hasMessageContaining("exit=1");
    }
}
