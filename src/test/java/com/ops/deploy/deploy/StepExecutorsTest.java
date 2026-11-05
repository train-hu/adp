package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.project.PackageStorage;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 四个步骤执行器的单测（mock SshService / PackageStorage）。
 */
@ExtendWith(MockitoExtension.class)
class StepExecutorsTest {

    @Mock
    private SshService sshService;

    @Mock
    private PackageStorage packageStorage;

    // ---------- StartStepExecutor.buildUnitContent（纯函数） ----------

    @Test
    @DisplayName("buildUnitContent 包含 unit 三节与关键键值")
    void buildUnitContent_hasAllSectionsAndValues() {
        String c = StartStepExecutor.buildUnitContent("deploy-demo.service",
                "/opt/deploy/demo/1", "java -jar app.jar");
        assertThat(c).contains("[Unit]");
        assertThat(c).contains("[Service]");
        assertThat(c).contains("[Install]");
        assertThat(c).contains("Description=deploy-demo.service");
        assertThat(c).contains("After=network.target");
        assertThat(c).contains("WorkingDirectory=/opt/deploy/demo/1");
        assertThat(c).contains("ExecStart=java -jar app.jar");
        assertThat(c).contains("Restart=on-failure");
        assertThat(c).contains("WantedBy=multi-user.target");
    }

    @Test
    @DisplayName("buildUnitContent 对特殊 startCmd 原样写入")
    void buildUnitContent_preservesStartCmdVerbatim() {
        String c = StartStepExecutor.buildUnitContent("deploy-x.service", "/opt/deploy/x/1",
                "java -Xmx512m -jar app.jar --spring.profiles=prod");
        assertThat(c).contains("ExecStart=java -Xmx512m -jar app.jar --spring.profiles=prod");
    }

    @Test
    @DisplayName("resolveRuntimeDir：jar/war 落 workdir 根，其余（zip/tar.gz/tgz/html/未知）落 extract（线上 service 缺陷回归）")
    void resolveRuntimeDir_byPackageType() {
        // jar/war：install 后 mv 到 workdir 根（app.jar），运行目录为 workdir 根
        assertThat(InstallStepExecutor.resolveRuntimeDir("/opt/deploy/demo/1", "app.jar"))
                .isEqualTo("/opt/deploy/demo/1");
        assertThat(InstallStepExecutor.resolveRuntimeDir("/opt/deploy/demo/1", "WAR-1.war"))
                .isEqualTo("/opt/deploy/demo/1");
        // 其余类型内容在 extract，运行目录为 extract
        assertThat(InstallStepExecutor.resolveRuntimeDir("/opt/deploy/demo/1", "site.zip"))
                .isEqualTo("/opt/deploy/demo/1/extract");
        assertThat(InstallStepExecutor.resolveRuntimeDir("/opt/deploy/categraf/16", "categraf-v0.5.9-linux-amd64.tar.gz"))
                .isEqualTo("/opt/deploy/categraf/16/extract");
        assertThat(InstallStepExecutor.resolveRuntimeDir("/opt/deploy/demo/1", "pkg.tgz"))
                .isEqualTo("/opt/deploy/demo/1/extract");
        // fileName 为 null/无法识别 → 默认 extract
        assertThat(InstallStepExecutor.resolveRuntimeDir("/opt/deploy/demo/1", null))
                .isEqualTo("/opt/deploy/demo/1/extract");
        assertThat(InstallStepExecutor.resolveRuntimeDir("/opt/deploy/demo/1", "unknown.bin"))
                .isEqualTo("/opt/deploy/demo/1/extract");
    }

    @Test
    @DisplayName("buildUnitContent WorkingDirectory 取运行目录：zip 包（startCmd 相对路径）→ extract")
    void buildUnitContent_workingDirIsRuntimeDir() {
        // 线上 service 缺陷：zip 包内容在 extract，相对路径 startCmd 需 WorkingDirectory=extract 才能命中文件
        String c = StartStepExecutor.buildUnitContent("deploy-categraf.service",
                "/opt/deploy/categraf/16/extract", "./bin/categraf --configs ./conf");
        assertThat(c).contains("WorkingDirectory=/opt/deploy/categraf/16/extract");
        // ExecStart 首 token 相对路径必须被拼成绝对路径（systemd 不接受 ./xxx，unit 会 bad-setting）
        assertThat(c).contains("ExecStart=/opt/deploy/categraf/16/extract/./bin/categraf --configs ./conf");
    }

    @Test
    @DisplayName("absolutizeExecStart：./ ../ 前缀拼 workingDir；绝对路径/bare 命令/参数相对路径不动")
    void absolutizeExecStart_rules() {
        // ./ 前缀 → 拼到 workingDir（categraf 线上缺陷回归：ExecStart=./categraf 被 systemd 判 bad-setting）
        assertThat(StartStepExecutor.absolutizeExecStart("/opt/deploy/categraf/16/extract",
                "./categraf --configs ./conf"))
                .isEqualTo("/opt/deploy/categraf/16/extract/./categraf --configs ./conf");
        // ../ 前缀
        assertThat(StartStepExecutor.absolutizeExecStart("/opt/deploy/demo/1/extract",
                "../app.jar"))
                .isEqualTo("/opt/deploy/demo/1/extract/../app.jar");
        // workingDir 带尾斜杠不产生双斜杠
        assertThat(StartStepExecutor.absolutizeExecStart("/opt/deploy/demo/1/", "./app"))
                .isEqualTo("/opt/deploy/demo/1/./app");
        // 绝对路径原样
        assertThat(StartStepExecutor.absolutizeExecStart("/opt/deploy/demo/1",
                "/opt/categraf/categraf run"))
                .isEqualTo("/opt/categraf/categraf run");
        // bare 命令（java/python3 等，systemd 沿 PATH 查找）原样
        assertThat(StartStepExecutor.absolutizeExecStart("/opt/deploy/demo/1",
                "java -jar app.jar"))
                .isEqualTo("java -jar app.jar");
        // 参数中的相对路径（--configs ./conf）不动，仅改首 token
        assertThat(StartStepExecutor.absolutizeExecStart("/opt/deploy/demo/1",
                "./run.sh --dir ./data"))
                .isEqualTo("/opt/deploy/demo/1/./run.sh --dir ./data");
        // 空命令
        assertThat(StartStepExecutor.absolutizeExecStart("/opt/deploy/demo/1", "  ")).isEmpty();
        assertThat(StartStepExecutor.absolutizeExecStart("/opt/deploy/demo/1", null)).isEmpty();
    }

    @Test
    @DisplayName("buildUnitContent 提取前导内联环境变量为 Environment（E2E 缺陷7回归）")
    void buildUnitContent_extractsInlineEnvPrefix() {
        // 回归：systemd 的 ExecStart 不接受 shell 风格内联环境变量，
        // 会把 PORT=8001 当作可执行文件名报 "Executable not found"（真实 252 E2E 实锤）。
        // 正确 unit 应拆出 Environment=PORT=8001，ExecStart 只保留命令。
        String c = StartStepExecutor.buildUnitContent("deploy-p.service", "/opt/deploy/p/1",
                "PORT=8001 /usr/bin/python3 extract/app.py");
        assertThat(c).contains("Environment=PORT=8001");
        assertThat(c).contains("ExecStart=/usr/bin/python3 extract/app.py");
        assertThat(c).doesNotContain("ExecStart=PORT=8001");
    }

    @Test
    @DisplayName("buildUnitContent 多个前导环境变量按序提取，命令含等号参数不被误判")
    void buildUnitContent_multipleEnvAndEqArgs() {
        // 多个前导 env + 命令内含等号的参数（--spring.profiles=prod）不得被误提取为环境变量。
        String c = StartStepExecutor.buildUnitContent("deploy-m.service", "/opt/deploy/m/1",
                "A=1 B=x java -jar app.jar --spring.profiles=prod");
        assertThat(c).contains("Environment=A=1");
        assertThat(c).contains("Environment=B=x");
        assertThat(c).contains("ExecStart=java -jar app.jar --spring.profiles=prod");
        assertThat(c).doesNotContain("Environment=--spring");
    }

    @Test
    @DisplayName("splitInlineEnv 边界：null/空/无 env 前缀")
    void splitInlineEnv_edgeCases() {
        assertThat(StartStepExecutor.splitInlineEnv(null)).containsExactly("", "");
        assertThat(StartStepExecutor.splitInlineEnv("   ")).containsExactly("", "");
        // 无 env 前缀：命令整体原样
        assertThat(StartStepExecutor.splitInlineEnv("npm start"))
                .containsExactly("", "npm start");
        // 命令是 "KEY=VALUE" 形式且后面没有命令（如仅 env 无命令）→ 全部当 env 拆出
        assertThat(StartStepExecutor.splitInlineEnv("PORT=8001"))
                .containsExactly("PORT=8001", "");
    }

    @Test
    @DisplayName("write unit: heredoc 定界符随机且带引号，两次不同")
    void writeUnitCommand_randomQuotedDelimiter() {
        String c1 = StartStepExecutor.buildWriteUnitCommand("/etc/systemd/system/x.service", "[Unit]\n");
        String c2 = StartStepExecutor.buildWriteUnitCommand("/etc/systemd/system/x.service", "[Unit]\n");
        assertThat(c1).startsWith("mkdir -p /etc/systemd/system && cat > ");
        assertThat(c1).contains("<< 'UNIT_EOF_");
        assertThat(c1).isNotEqualTo(c2);
    }

    @Test
    @DisplayName("start: 启动后 SubState 连续两次 running 且 MainPID 稳定 → 成功（E2E 缺陷8回归）")
    void startExecute_stableRunning_success() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0))           // 写 unit
                .thenReturn(CommandResult.ok("", 0))           // daemon-reload && enable && restart
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1001", 0))
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1001", 0));
        StartStepExecutor ex = new StartStepExecutor(sshService, 2, 0L, 0);
        var detail = ex.execute(1L, "deploy-demo.service", "/opt/deploy/demo/1",
                "app.zip", "PORT=8001 /usr/bin/python3 app.py");
        assertThat(detail.get("unitName")).isEqualTo("deploy-demo.service");
        assertThat(detail.get("workdir")).isEqualTo("/opt/deploy/demo/1");
        assertThat(detail.get("workingDir")).isEqualTo("/opt/deploy/demo/1/extract");
    }

    @Test
    @DisplayName("start: 启动命令用 restart 而非 enable --now（缺陷10回归：enable --now 对已 active unit 是 no-op）")
    void startExecute_usesRestartNotEnableNow() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0))
                .thenAnswer(inv -> {
                    // 第二个调用（启动命令）必须包含 restart 且不含 enable --now
                    String cmd = inv.getArgument(1);
                    assertThat(cmd).contains("systemctl restart deploy-demo.service");
                    assertThat(cmd).doesNotContain("enable --now");
                    return CommandResult.ok("", 0);
                })
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1001", 0))
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1001", 0));
        StartStepExecutor ex = new StartStepExecutor(sshService, 2, 0L, 0);
        ex.execute(1L, "deploy-demo.service", "/opt/deploy/demo/1", "app.zip", "PORT=8001 /usr/bin/python3 app.py");
    }

    @Test
    @DisplayName("start: SubState 非 running（崩溃循环）→ SSH_ERROR 触发回滚（缺陷8回归）")
    void startExecute_crashLoop_throws() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0))           // 写 unit
                .thenReturn(CommandResult.ok("", 0))           // restart
                .thenReturn(CommandResult.ok("SubState=dead\nMainPID=0", 0));   // 首次采样即非 running → 早退
        StartStepExecutor ex = new StartStepExecutor(sshService, 2, 0L, 0);
        assertThatThrownBy(() -> ex.execute(1L, "deploy-demo.service", "/opt/deploy/demo/1",
                "app.zip", "PORT=8091 /usr/bin/python3 app.py"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR))
                .hasMessageContaining("not stable")
                .hasMessageContaining("samples: dead");
    }

    @Test
    @DisplayName("start: 首次 running 第二次 auto-restart（循环中途崩溃）→ SSH_ERROR")
    void startExecute_runningThenCrash_throws() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1001", 0))
                .thenReturn(CommandResult.ok("SubState=auto-restart\nMainPID=0", 0));
        StartStepExecutor ex = new StartStepExecutor(sshService, 2, 0L, 0);
        assertThatThrownBy(() -> ex.execute(1L, "deploy-demo.service", "/opt/deploy/demo/1", "app.zip", "cmd"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR))
                .hasMessageContaining("not stable");
    }

    @Test
    @DisplayName("start: SubState 持续 running 但 MainPID 变化（进程被反复重启）→ SSH_ERROR（缺陷10回归）")
    void startExecute_runningButMainPidChanges_throws() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1001", 0))
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1002", 0));  // 进程被替换
        StartStepExecutor ex = new StartStepExecutor(sshService, 2, 0L, 0);
        assertThatThrownBy(() -> ex.execute(1L, "deploy-demo.service", "/opt/deploy/demo/1", "app.zip", "cmd"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR))
                .hasMessageContaining("MainPID changed between samples")
                .hasMessageContaining("1001,1002");
    }

    @Test
    @DisplayName("parseSubState/parseMainPid: 多行 Key=Value 解析（含缩进/空/缺失）")
    void parseSubStateAndMainPid_cases() {
        assertThat(StartStepExecutor.parseSubState("SubState=running")).isEqualTo("running");
        assertThat(StartStepExecutor.parseSubState("  SubState=auto-restart\n")).isEqualTo("auto-restart");
        assertThat(StartStepExecutor.parseSubState("SubState=running\nMainPID=1001")).isEqualTo("running");
        assertThat(StartStepExecutor.parseSubState("MainPID=0\nSubState=dead")).isEqualTo("dead");
        assertThat(StartStepExecutor.parseSubState(null)).isEqualTo("unknown");
        assertThat(StartStepExecutor.parseSubState("")).isEqualTo("unknown");
        assertThat(StartStepExecutor.parseSubState("SubState=")).isEqualTo("unknown");
        assertThat(StartStepExecutor.parseSubState("garbage-no-key")).isEqualTo("unknown");
        // MainPID
        assertThat(StartStepExecutor.parseMainPid("MainPID=1001\nSubState=running")).isEqualTo("1001");
        assertThat(StartStepExecutor.parseMainPid("SubState=running\nMainPID=0")).isEqualTo("0");
        assertThat(StartStepExecutor.parseMainPid(null)).isEqualTo("unknown");
        assertThat(StartStepExecutor.parseMainPid("SubState=running")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("mainPidStable: 全一致稳定 / 两次不同不稳定 / 全 0 或 unknown 不稳定")
    void mainPidStable_cases() {
        assertThat(StartStepExecutor.mainPidStable(java.util.List.of("1001", "1001"))).isTrue();
        assertThat(StartStepExecutor.mainPidStable(java.util.List.of("0", "1001", "1001"))).isTrue();
        assertThat(StartStepExecutor.mainPidStable(java.util.List.of("1001", "1002"))).isFalse();
        assertThat(StartStepExecutor.mainPidStable(java.util.List.of("0", "0"))).isFalse();
        assertThat(StartStepExecutor.mainPidStable(java.util.List.of("unknown", "unknown"))).isFalse();
        assertThat(StartStepExecutor.mainPidStable(java.util.List.of("0", "unknown"))).isFalse();
    }

    @Test
    @DisplayName("buildSubStateCommand: 一次取 SubState + MainPID")
    void buildSubStateCommand_format() {
        assertThat(StartStepExecutor.buildSubStateCommand("deploy-demo.service"))
                .isEqualTo("systemctl show -p SubState -p MainPID deploy-demo.service");
        assertThat(StartStepExecutor.buildSubStateCommand("we'ird name"))
                .isEqualTo("systemctl show -p SubState -p MainPID 'we'\\''ird name'");
    }

    // ---------- StartStepExecutor 启动命令冒烟校验（坏 flag 线上回归） ----------

    @Test
    @DisplayName("buildSmokeTestCommand：cd 运行目录 + env + timeout 包裹 startCmd，相对路径保持原样")
    void buildSmokeTestCommand_shape() {
        // 纯相对命令：cd 到运行目录，timeout 包裹，2>&1 合并 stderr
        assertThat(StartStepExecutor.buildSmokeTestCommand("/opt/deploy/categraf/16/extract", 5,
                "./categraf --configs ./conf"))
                .isEqualTo("cd /opt/deploy/categraf/16/extract && timeout 5s ./categraf --configs ./conf 2>&1");
        // 前导内联 env 拆出为 env 前缀（复刻 unit 的 Environment=）
        assertThat(StartStepExecutor.buildSmokeTestCommand("/opt/deploy/demo/1/extract", 5,
                "PORT=8001 /usr/bin/python3 app.py"))
                .isEqualTo("cd /opt/deploy/demo/1/extract && env PORT=8001 timeout 5s /usr/bin/python3 app.py 2>&1");
        // 多个 env 按序
        assertThat(StartStepExecutor.buildSmokeTestCommand("/opt/deploy/m/1", 3,
                "A=1 B=x java -jar app.jar"))
                .isEqualTo("cd /opt/deploy/m/1 && env A=1 B=x timeout 3s java -jar app.jar 2>&1");
        // workingDir 尾斜杠不产生双斜杠
        assertThat(StartStepExecutor.buildSmokeTestCommand("/opt/deploy/demo/1/", 5, "./app"))
                .isEqualTo("cd /opt/deploy/demo/1 && timeout 5s ./app 2>&1");
    }

    @Test
    @DisplayName("smoke: 快速非 0 退出（坏 flag exit=2）→ SSH_ERROR 且携带 stderr，且不写 unit")
    void smokeTest_badFlagFailsBeforeUnitWrite() {
        when(sshService.execute(anyLong(), anyString(), anyInt()))
                .thenReturn(CommandResult.fail(2, "", "flag provided but not defined: -config"));
        StartStepExecutor ex = new StartStepExecutor(sshService, 2, 0L, 5);
        assertThatThrownBy(() -> ex.execute(1L, "deploy-categraf.service", "/opt/deploy/categraf/16",
                "categraf.tar.gz", "./categraf --config=conf"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR))
                .hasMessageContaining("smoke test failed (exit=2)")
                .hasMessageContaining("flag provided but not defined: -config");
        // 关键：冒烟失败发生在写 unit 之前 → 只调用了冒烟那一次 3 参 execute，
        // 2 参 execute（写 unit / 启动）从未被调用 → 不留下崩溃循环 unit
        verify(sshService, times(1)).execute(anyLong(), anyString(), anyInt());
        verify(sshService, never()).execute(anyLong(), anyString());
    }

    @Test
    @DisplayName("smoke: 退出码 124（长驻进程超时仍在跑）→ 视为通过，继续写 unit + 启动")
    void smokeTest_timeout124Passes() {
        when(sshService.execute(anyLong(), anyString(), anyInt()))
                .thenReturn(CommandResult.fail(124, "", ""));          // 冒烟：进程仍在跑
        when(sshService.execute(anyLong(), anyString()))
                .thenReturn(CommandResult.ok("", 0))                  // 写 unit
                .thenReturn(CommandResult.ok("", 0))                  // restart
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1001", 0))
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1001", 0));
        StartStepExecutor ex = new StartStepExecutor(sshService, 2, 0L, 5);
        var detail = ex.execute(1L, "deploy-demo.service", "/opt/deploy/demo/1", "app.zip", "java -jar app.jar");
        assertThat(detail.get("unitName")).isEqualTo("deploy-demo.service");
        // 冒烟(3 参) 1 次 + 写 unit/restart/2 次采样(2 参共 4 次)
        verify(sshService, times(1)).execute(anyLong(), anyString(), anyInt());
        verify(sshService, times(4)).execute(anyLong(), anyString());
    }

    @Test
    @DisplayName("smoke: 退出码 0（批处理命令主动正常退出）→ 视为通过")
    void smokeTest_exit0Passes() {
        when(sshService.execute(anyLong(), anyString(), anyInt()))
                .thenReturn(CommandResult.ok("done", 0));
        when(sshService.execute(anyLong(), anyString()))
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1001", 0))
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1001", 0));
        StartStepExecutor ex = new StartStepExecutor(sshService, 2, 0L, 5);
        var detail = ex.execute(1L, "deploy-demo.service", "/opt/deploy/demo/1", "app.jar", "java -jar app.jar");
        assertThat(detail.get("unitName")).isEqualTo("deploy-demo.service");
    }

    @Test
    @DisplayName("smoke: smokeTestSeconds=0（单测/关闭）→ 跳过冒烟，行为与改造前一致")
    void smokeTest_disabledSkips() {
        StartStepExecutor ex = new StartStepExecutor(sshService, 2, 0L, 0);
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1001", 0))
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1001", 0));
        ex.execute(1L, "deploy-demo.service", "/opt/deploy/demo/1", "app.zip", "cmd");
        // 关闭冒烟：不应出现 3 参 execute 调用
        verify(sshService, never()).execute(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("buildStartCommand: daemon-reload + enable + restart（含 restart，不含 enable --now）")
    void buildStartCommand_usesRestart() {
        String cmd = StartStepExecutor.buildStartCommand("deploy-demo.service");
        assertThat(cmd).isEqualTo("systemctl daemon-reload && systemctl enable deploy-demo.service && systemctl restart deploy-demo.service");
        assertThat(cmd).contains("systemctl restart deploy-demo.service");
        assertThat(cmd).doesNotContain("enable --now");
    }

    // ---------- InstallStepExecutor.buildInstallCommand（纯函数，5 种后缀 + 不支持） ----------

    /** buildFlattenCommand("/opt/deploy/demo/1") 的期望值（POSIX sh 单根目录拍平脚本）。 */
    private static final String FLATTEN_DEMO =
            "cd /opt/deploy/demo/1/extract && if [ \"$(find . -mindepth 1 -maxdepth 1 | wc -l)\" -eq 1 ]; then "
                    + "d=$(find . -mindepth 1 -maxdepth 1); "
                    + "if [ -d \"$d\" ]; then find \"$d\" -mindepth 1 -maxdepth 1 -exec mv -f {} . \\; && rmdir \"$d\"; fi; "
                    + "fi";

    @Test
    @DisplayName("install: .zip → unzip + 单根目录拍平")
    void installCommand_zip() {
        assertThat(InstallStepExecutor.buildInstallCommand("app.zip", "/opt/deploy/demo/1"))
                .isEqualTo("unzip -o -q /opt/deploy/demo/1/app.zip -d /opt/deploy/demo/1/extract"
                        + " && " + FLATTEN_DEMO);
    }

    @Test
    @DisplayName("install: .tar.gz → mkdir extract + tar + 单根目录拍平")
    void installCommand_tarGz() {
        assertThat(InstallStepExecutor.buildInstallCommand("app.tar.gz", "/opt/deploy/demo/1"))
                .isEqualTo("mkdir -p /opt/deploy/demo/1/extract && tar xzf /opt/deploy/demo/1/app.tar.gz -C /opt/deploy/demo/1/extract"
                        + " && " + FLATTEN_DEMO);
    }

    @Test
    @DisplayName("install: .tgz → mkdir extract + tar + 单根目录拍平")
    void installCommand_tgz() {
        assertThat(InstallStepExecutor.buildInstallCommand("app.tgz", "/opt/deploy/demo/1"))
                .isEqualTo("mkdir -p /opt/deploy/demo/1/extract && tar xzf /opt/deploy/demo/1/app.tgz -C /opt/deploy/demo/1/extract"
                        + " && " + FLATTEN_DEMO);
    }

    @Test
    @DisplayName("install: .jar → mv 到 app.jar（不拍平）")
    void installCommand_jar() {
        assertThat(InstallStepExecutor.buildInstallCommand("app.jar", "/opt/deploy/demo/1"))
                .isEqualTo("mv /opt/deploy/demo/1/app.jar /opt/deploy/demo/1/app.jar");
    }

    @Test
    @DisplayName("install: .html/.htm → mkdir extract + mv 到 index.html（不拍平）")
    void installCommand_html() {
        assertThat(InstallStepExecutor.buildInstallCommand("testindex.html", "/opt/deploy/demo/1"))
                .isEqualTo("mkdir -p /opt/deploy/demo/1/extract && mv /opt/deploy/demo/1/testindex.html /opt/deploy/demo/1/extract/index.html");
        assertThat(InstallStepExecutor.buildInstallCommand("page.HTM", "/opt/deploy/demo/1"))
                .isEqualTo("mkdir -p /opt/deploy/demo/1/extract && mv /opt/deploy/demo/1/page.HTM /opt/deploy/demo/1/extract/index.html");
    }

    @Test
    @DisplayName("install: buildFlattenCommand 输出 POSIX 拍平脚本且不含单引号")
    void flattenCommand_format() {
        assertThat(InstallStepExecutor.buildFlattenCommand("/opt/deploy/demo/1")).isEqualTo(FLATTEN_DEMO);
        // 脚本体只使用双引号包 $( )/$d，避免单引号嵌套；普通路径下整体不含单引号
        assertThat(InstallStepExecutor.buildFlattenCommand("/opt/deploy/demo/1")).doesNotContain("'");
        // 含特殊字符的 workdir 路径会被 quote（仅路径段带单引号）
        assertThat(InstallStepExecutor.buildFlattenCommand("/opt/deploy/my proj/1"))
                .contains("'/opt/deploy/my proj/1/extract'");
    }

    @Test
    @DisplayName("install: 其他后缀 → BAD_REQUEST unsupported package format")
    void installCommand_unsupported() {
        assertThatThrownBy(() -> InstallStepExecutor.buildInstallCommand("app.war", "/opt/deploy/demo/1"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST))
                .hasMessageContaining("unsupported package format");
    }

    @Test
    @DisplayName("install: 路径含空格/特殊字符时 quote")
    void installCommand_quotesPathsWithSpecialChars() {
        String cmd = InstallStepExecutor.buildInstallCommand("a b.zip", "/opt/deploy/my proj/1");
        assertThat(cmd).contains("'/opt/deploy/my proj/1/a b.zip'");
    }

    @Test
    @DisplayName("install: execute 成功返回 detail")
    void installExecute_success() {
        when(sshService.executeWithRetry(eq(1L), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.ok("", 0, 123L));
        InstallStepExecutor ex = new InstallStepExecutor(sshService);
        var detail = ex.execute(1L, "/opt/deploy/demo/1", "app.zip");
        assertThat(detail.get("command")).isEqualTo(
                "unzip -o -q /opt/deploy/demo/1/app.zip -d /opt/deploy/demo/1/extract"
                        + " && " + FLATTEN_DEMO);
        assertThat((Long) detail.get("durationMs")).isEqualTo(123L);
    }

    @Test
    @DisplayName("install: exit!=0 → SSH_ERROR 附 stderr")
    void installExecute_nonZeroExit_throws() {
        when(sshService.executeWithRetry(eq(1L), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.fail(1, "", "unzip: not found"));
        InstallStepExecutor ex = new InstallStepExecutor(sshService);
        assertThatThrownBy(() -> ex.execute(1L, "/opt/deploy/demo/1", "app.zip"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR))
                .hasMessageContaining("unzip: not found");
    }

    @Test
    @DisplayName("install: timeout → SSH_ERROR")
    void installExecute_timeout_throws() {
        when(sshService.executeWithRetry(eq(1L), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.timeout(300_000L));
        InstallStepExecutor ex = new InstallStepExecutor(sshService);
        assertThatThrownBy(() -> ex.execute(1L, "/opt/deploy/demo/1", "app.zip"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR))
                .hasMessageContaining("timeout");
    }

    // ---------- VerifyStepExecutor（curl 三种：2xx 成功 / 全败 / 中间成功） ----------

    @Test
    @DisplayName("verify: 第一轮 2xx → 成功 attempts=1")
    void verify_firstRoundSuccess() {
        when(sshService.execute(eq(1L), anyString(), anyInt())).thenReturn(CommandResult.ok("200", 0));
        VerifyStepExecutor ex = new VerifyStepExecutor(sshService, 30, 0L);
        var detail = ex.execute(1L, 8080, "/health");
        assertThat(detail.get("url")).isEqualTo("http://127.0.0.1:8080/health");
        assertThat((Integer) detail.get("attempts")).isEqualTo(1);
        assertThat(detail.get("httpCode")).isEqualTo("200");
    }

    @Test
    @DisplayName("verify: 中间成功（前两轮 503，第三轮 200）→ attempts=3")
    void verify_middleSuccess() {
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok("503", 0))
                .thenReturn(CommandResult.ok("503", 0))
                .thenReturn(CommandResult.ok("200", 0));
        VerifyStepExecutor ex = new VerifyStepExecutor(sshService, 30, 0L);
        var detail = ex.execute(1L, 8080, "/");
        assertThat((Integer) detail.get("attempts")).isEqualTo(3);
        assertThat(detail.get("httpCode")).isEqualTo("200");
    }

    @Test
    @DisplayName("verify: 全部失败 → DEPLOY_FAILED 附最后一轮输出")
    void verify_allFail_throwsWithLastOutput() {
        when(sshService.execute(eq(1L), anyString(), anyInt())).thenReturn(CommandResult.ok("503", 0));
        VerifyStepExecutor ex = new VerifyStepExecutor(sshService, 3, 0L);
        assertThatThrownBy(() -> ex.execute(1L, 8080, "/health"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.DEPLOY_FAILED))
                .hasMessageContaining("health check failed after 3 attempts")
                .hasMessageContaining("round=3");
    }

    @Test
    @DisplayName("verify: buildVerifyCommand 构造 curl 命令")
    void verifyCommand() {
        assertThat(VerifyStepExecutor.buildVerifyCommand(8080, "/health"))
                .isEqualTo("curl -sf -o /dev/null -w '%{http_code}' --max-time 5 'http://127.0.0.1:8080/health'");
        assertThat(VerifyStepExecutor.buildVerifyCommand(9090, null))
                .isEqualTo("curl -sf -o /dev/null -w '%{http_code}' --max-time 5 'http://127.0.0.1:9090/'");
    }

    @Test
    @DisplayName("verify: 注入路径时整 URL 被单引号包裹（纵深防御）")
    void verifyCommand_injectionPathQuoted() {
        String cmd = VerifyStepExecutor.buildVerifyCommand(8080, "/; rm -rf /");
        assertThat(cmd).contains("'http://127.0.0.1:8080/; rm -rf /'");
    }

    @Test
    @DisplayName("verify: 首轮 SSH_ERROR（channel open failed）→ 下轮新连接自愈 attempts=2")
    void verify_sshErrorRound_selfHeals() {
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenThrow(new BizException(ErrorCode.SSH_ERROR, "exec failed on ...: open failed"))
                .thenReturn(CommandResult.ok("200", 0));
        VerifyStepExecutor ex = new VerifyStepExecutor(sshService, 30, 0L);
        var detail = ex.execute(1L, 8080, "/health");
        assertThat((Integer) detail.get("attempts")).isEqualTo(2);
        assertThat(detail.get("httpCode")).isEqualTo("200");
    }

    @Test
    @DisplayName("verify: 非 SSH 错误立即上抛、不消耗轮次")
    void verify_nonSshError_throwsImmediately() {
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenThrow(new BizException(ErrorCode.BAD_REQUEST, "bad param"));
        VerifyStepExecutor ex = new VerifyStepExecutor(sshService, 30, 0L);
        assertThatThrownBy(() -> ex.execute(1L, 8080, "/health"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST))
                .hasMessageContaining("bad param");
        verify(sshService, times(1)).execute(eq(1L), anyString(), anyInt());
    }

    @Test
    @DisplayName("verify: 全部轮次 SSH_ERROR → DEPLOY_FAILED 附 sshError 信息")
    void verify_allRoundsSshError_throwsDeployFailed() {
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenThrow(new BizException(ErrorCode.SSH_ERROR, "open failed"));
        VerifyStepExecutor ex = new VerifyStepExecutor(sshService, 3, 0L);
        assertThatThrownBy(() -> ex.execute(1L, 8080, "/health"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.DEPLOY_FAILED))
                .hasMessageContaining("health check failed after 3 attempts")
                .hasMessageContaining("sshError");
    }

    // ---------- VerifyStepExecutor 进程存活降级（方案A：无 HTTP 端口的 agent 类服务） ----------

    @Test
    @DisplayName("verify降级：curl 全 000（连接失败）+ unitName → 查 SubState=running → 通过（process_alive）")
    void verify_fallbackProcessAlive_running() {
        // curl 连接失败（端口无 HTTP 监听，code=000）×3 轮，随后第 4 次调用是降级存活校验
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok("000", 7))
                .thenReturn(CommandResult.ok("000", 7))
                .thenReturn(CommandResult.ok("000", 7))
                .thenReturn(CommandResult.ok("SubState=running\nMainPID=1", 0));
        VerifyStepExecutor ex = new VerifyStepExecutor(sshService, 3, 0L);
        var detail = ex.execute(1L, 8082, "/", "deploy-categraf.service");
        assertThat(detail.get("verifyMethod")).isEqualTo("process_alive");
        assertThat(detail.get("subState")).isEqualTo("running");
        assertThat(detail.get("unitName")).isEqualTo("deploy-categraf.service");
    }

    @Test
    @DisplayName("verify降级：curl 全 000 + unitName 但进程 dead → DEPLOY_FAILED 触发回滚")
    void verify_fallbackProcessAlive_dead_throws() {
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok("000", 7))
                .thenReturn(CommandResult.ok("000", 7))
                .thenReturn(CommandResult.ok("000", 7))
                .thenReturn(CommandResult.ok("SubState=dead\nMainPID=0", 0));
        VerifyStepExecutor ex = new VerifyStepExecutor(sshService, 3, 0L);
        assertThatThrownBy(() -> ex.execute(1L, 8082, "/", "deploy-categraf.service"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.DEPLOY_FAILED))
                .hasMessageContaining("health check failed after 3 attempts");
    }

    @Test
    @DisplayName("verify降级：出现真实 HTTP 错误（500）说明服务在跑但不健康 → 不降级直接失败")
    void verify_realHttpError_noFallback_throws() {
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok("500", 0)); // 端口有监听但返回 500
        VerifyStepExecutor ex = new VerifyStepExecutor(sshService, 3, 0L);
        assertThatThrownBy(() -> ex.execute(1L, 8080, "/health", "deploy-demo.service"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.DEPLOY_FAILED))
                .hasMessageContaining("health check failed after 3 attempts");
        // 真实 HTTP 错误不应触发存活校验（只调用 3 次 curl，无第 4 次 systemctl show）
        verify(sshService, times(3)).execute(eq(1L), anyString(), anyInt());
    }

    @Test
    @DisplayName("verify：3 参重载（unitName=null）保持原语义——连接失败不降级直接失败")
    void verify_nullUnitNoFallback() {
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok("000", 7));
        VerifyStepExecutor ex = new VerifyStepExecutor(sshService, 3, 0L);
        assertThatThrownBy(() -> ex.execute(1L, 8080, "/health"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.DEPLOY_FAILED));
    }

    // ---------- UploadStepExecutor ----------

    @Test
    @DisplayName("upload: execute 成功写 detail 并委托 uploadStream")
    void upload_execute_success() throws Exception {
        when(packageStorage.open(anyString()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(sshService.execute(anyLong(), anyString())).thenReturn(CommandResult.ok("", 0));
        when(sshService.uploadStream(anyLong(), anyString(), anyString(),
                any(java.io.InputStream.class), anyLong(), anyString())).thenReturn(true);
        UploadStepExecutor ex = new UploadStepExecutor(packageStorage, sshService);
        var detail = ex.execute(1L, "/opt/deploy/demo/1", "app.zip",
                "packages/1/1-x/app.zip", 5L, "a".repeat(64));
        assertThat(detail.get("sha256")).isEqualTo("a".repeat(64));
        assertThat((Long) detail.get("sizeBytes")).isEqualTo(5L);
        assertThat((Long) detail.get("durationMs")).isNotNull().isBetween(0L, 60_000L);
        verify(sshService).uploadStream(eq(1L), eq("/opt/deploy/demo/1"), eq("app.zip"),
                any(java.io.InputStream.class), eq(5L), eq("a".repeat(64)));
    }

    @Test
    @DisplayName("upload: mkdir 失败 → SSH_ERROR")
    void upload_mkdirFails_throws() {
        when(sshService.execute(anyLong(), anyString())).thenReturn(CommandResult.fail(1, "", "mkdir: no"));
        UploadStepExecutor ex = new UploadStepExecutor(packageStorage, sshService);
        assertThatThrownBy(() -> ex.execute(1L, "/opt/deploy/demo/1", "app.zip",
                "packages/1/1-x/app.zip", 5L, "a".repeat(64)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR))
                .hasMessageContaining("mkdir");
    }

    // ---------- NginxStartExecutor（NGINX 模式：配置下发 + 版本软链 + reload） ----------

    @Test
    @DisplayName("nginx: 默认站点配置含 listen/root(软链)/index/try_files")
    void nginxDefaultConf_hasServerBlock() {
        String conf = NginxStartExecutor.buildDefaultNginxConf("demo", 80);
        assertThat(conf).contains("listen 80;");
        assertThat(conf).contains("root /opt/deploy/demo/current;");
        assertThat(conf).contains("index index.html");
        assertThat(conf).contains("try_files $uri $uri/ =404;");
        assertThat(conf).startsWith("server {");
        assertThat(conf).endsWith("}\n");
    }

    @Test
    @DisplayName("nginx: 路径/命令纯函数（conf 路径、软链、ensure、reload、回滚）")
    void nginxPureFunctions_format() {
        assertThat(NginxStartExecutor.confPath("demo"))
                .isEqualTo("/etc/nginx/conf.d/deploy-demo.conf");
        assertThat(NginxStartExecutor.symlinkPath("demo"))
                .isEqualTo("/opt/deploy/demo/current");
        assertThat(NginxStartExecutor.buildSymlinkCommand("demo", "/opt/deploy/demo/7"))
                .isEqualTo("ln -sfn /opt/deploy/demo/7/extract /opt/deploy/demo/current");
        assertThat(NginxStartExecutor.buildEnsureCommand())
                .contains("command -v nginx")
                .contains("apt-get install -y nginx")
                .contains("yum install -y nginx")
                .contains("dnf install -y nginx")
                .contains("systemctl start nginx");
        assertThat(NginxStartExecutor.buildReloadCommand())
                .isEqualTo("nginx -t && nginx -s reload");
        // 回滚 = 软链切回旧版 + reload（站点配置 root 指向 current 软链，无需重写）
        assertThat(NginxStartExecutor.buildRollbackCommand("demo", "/opt/deploy/demo/6"))
                .isEqualTo("ln -sfn /opt/deploy/demo/6/extract /opt/deploy/demo/current && nginx -s reload");
    }

    @Test
    @DisplayName("nginx: 写配置命令为 quoted heredoc，定界符随机两次不同")
    void nginxWriteConfCommand_randomQuotedDelimiter() {
        String c1 = NginxStartExecutor.buildWriteConfCommand("/etc/nginx/conf.d/deploy-demo.conf", "server {}\n");
        String c2 = NginxStartExecutor.buildWriteConfCommand("/etc/nginx/conf.d/deploy-demo.conf", "server {}\n");
        assertThat(c1).startsWith("mkdir -p /etc/nginx/conf.d && cat > ");
        assertThat(c1).contains("<< 'NGINX_EOF_");
        assertThat(c1).isNotEqualTo(c2);
    }

    @Test
    @DisplayName("nginx: execute 无自定义配置 → 默认配置 + 软链 + reload，返回 detail")
    void nginxExecute_defaultFlow_success() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0))   // 写 conf
                .thenReturn(CommandResult.ok("", 0))   // 软链
                .thenReturn(CommandResult.ok("nginx: configuration file test is successful", 0));
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok("", 0));   // ensure nginx
        NginxStartExecutor ex = new NginxStartExecutor(sshService);
        var detail = ex.execute(1L, "demo", null, 80, "/opt/deploy/demo/7", null);
        assertThat(detail.get("confPath")).isEqualTo("/etc/nginx/conf.d/deploy-demo.conf");
        assertThat(detail.get("customConf")).isEqualTo(false);
        assertThat(detail.get("symlink")).isEqualTo("/opt/deploy/demo/current");
        assertThat(detail.get("target")).isEqualTo("/opt/deploy/demo/7/extract");
    }

    @Test
    @DisplayName("nginx: execute 自定义配置(file_key=nginx) → 按原样写入，不再生成默认配置")
    void nginxExecute_customConf_writtenVerbatim() {
        String custom = "server {\n    listen 8080;\n    root /srv/www;\n}\n";
        when(sshService.execute(eq(1L), anyString()))
                .thenAnswer(inv -> {
                    // 第一条命令（写 conf）必须包含用户自定义内容
                    String cmd = inv.getArgument(1);
                    assertThat(cmd).contains(custom);
                    assertThat(cmd).doesNotContain("try_files");
                    return CommandResult.ok("", 0);
                })
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.ok("", 0));
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok("", 0));   // ensure nginx
        NginxStartExecutor ex = new NginxStartExecutor(sshService);
        var detail = ex.execute(1L, "demo", null, null, "/opt/deploy/demo/7", custom);
        assertThat(detail.get("customConf")).isEqualTo(true);
    }

    @Test
    @DisplayName("nginx: port 为 null → 默认配置 listen 80")
    void nginxExecute_nullPort_defaultsTo80() {
        when(sshService.execute(eq(1L), anyString()))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    assertThat(cmd).contains("listen 80;");
                    return CommandResult.ok("", 0);
                })
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.ok("", 0));
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok("", 0));   // ensure nginx
        NginxStartExecutor ex = new NginxStartExecutor(sshService);
        ex.execute(1L, "demo", null, null, "/opt/deploy/demo/7", null);
    }

    @Test
    @DisplayName("nginx: 写配置失败 → SSH_ERROR 附 stderr")
    void nginxExecute_writeConfFails_throws() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.fail(1, "", "bash: /etc/nginx/conf.d: Permission denied"));
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok("", 0));   // ensure nginx
        NginxStartExecutor ex = new NginxStartExecutor(sshService);
        assertThatThrownBy(() -> ex.execute(1L, "demo", null, 80, "/opt/deploy/demo/7", null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR))
                .hasMessageContaining("write nginx conf")
                .hasMessageContaining("Permission denied");
    }

    @Test
    @DisplayName("nginx: 软链失败 → SSH_ERROR")
    void nginxExecute_symlinkFails_throws() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.fail(1, "", "ln: failed"));
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok("", 0));   // ensure nginx
        NginxStartExecutor ex = new NginxStartExecutor(sshService);
        assertThatThrownBy(() -> ex.execute(1L, "demo", null, 80, "/opt/deploy/demo/7", null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR))
                .hasMessageContaining("symlink");
    }

    @Test
    @DisplayName("nginx: nginx -t 失败 → SSH_ERROR 附输出（不 reload）")
    void nginxExecute_nginxTestFails_throws() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.fail(1, "nginx: [emerg] invalid", "nginx -t failed"));
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok("", 0));   // ensure nginx
        NginxStartExecutor ex = new NginxStartExecutor(sshService);
        assertThatThrownBy(() -> ex.execute(1L, "demo", null, 80, "/opt/deploy/demo/7", null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR))
                .hasMessageContaining("nginx -t / reload failed")
                .hasMessageContaining("invalid");
    }

    // ---------- DockerStartExecutor（DOCKER 模式：compose/.env 下发 + up -d） ----------

    @Test
    @DisplayName("docker: buildUpCommand / buildRollbackCommand 格式（独立版 docker-compose）")
    void dockerCommands_format() {
        assertThat(DockerStartExecutor.composePath("/opt/deploy/demo/7"))
                .isEqualTo("/opt/deploy/demo/7/docker-compose.yml");
        assertThat(DockerStartExecutor.envPath("/opt/deploy/demo/7"))
                .isEqualTo("/opt/deploy/demo/7/.env");
        assertThat(DockerStartExecutor.dockerfilePath("/opt/deploy/demo/7"))
                .isEqualTo("/opt/deploy/demo/7/extract/Dockerfile");
        assertThat(DockerStartExecutor.buildUpCommand("/opt/deploy/demo/7"))
                .isEqualTo("docker-compose -f /opt/deploy/demo/7/docker-compose.yml up -d --build");
        // 回滚：先 down 清掉同 project 名下的旧容器（含同名新容器），再 up 旧版本 compose
        assertThat(DockerStartExecutor.buildRollbackCommand("/opt/deploy/demo/6"))
                .isEqualTo("docker-compose -f /opt/deploy/demo/6/docker-compose.yml down"
                        + " && docker-compose -f /opt/deploy/demo/6/docker-compose.yml up -d");
    }

    @Test
    @DisplayName("docker: execute 无 .env 无 Dockerfile → 写 compose + up -d，返回 detail")
    void dockerExecute_noEnv_success() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0));  // 写 compose
        when(sshService.executeWithRetry(eq(1L), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.ok("", 0, 42L));
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        var detail = ex.execute(1L, "/opt/deploy/demo/7", "services: {}", null, null, null);
        assertThat(detail.get("composePath")).isEqualTo("/opt/deploy/demo/7/docker-compose.yml");
        assertThat(detail.get("envWritten")).isEqualTo(false);
        assertThat(detail.get("dockerfileWritten")).isEqualTo(false);
        assertThat(detail.get("resolvedPort")).isNull();
    }

    @Test
    @DisplayName("docker: execute 有 Dockerfile → 先写 extract/Dockerfile 再写 compose 再 up -d")
    void dockerExecute_withDockerfile_success() {
        when(sshService.execute(eq(1L), anyString()))
                .thenAnswer(inv -> {
                    // 第一条命令必须是写 Dockerfile（mkdir -p extract + heredoc）
                    String cmd = inv.getArgument(1);
                    assertThat(cmd).contains("mkdir -p /opt/deploy/demo/7/extract");
                    assertThat(cmd).contains("<< 'DOCKERFILE_EOF_");
                    assertThat(cmd).contains("FROM eclipse-temurin:17-jre");
                    return CommandResult.ok("", 0);
                })
                .thenReturn(CommandResult.ok("", 0));  // 写 compose
        when(sshService.executeWithRetry(eq(1L), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.ok("", 0, 1L));
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        var detail = ex.execute(1L, "/opt/deploy/demo/7", "services: {}", null,
                "FROM eclipse-temurin:17-jre\nENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]\n", null);
        assertThat(detail.get("dockerfileWritten")).isEqualTo(true);
    }

    @Test
    @DisplayName("docker: execute 有 .env → 先写 compose 再写 .env 再 up -d")
    void dockerExecute_withEnv_success() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0))   // 写 compose
                .thenReturn(CommandResult.ok("", 0));  // 写 .env
        when(sshService.executeWithRetry(eq(1L), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.ok("", 0, 1L));
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        var detail = ex.execute(1L, "/opt/deploy/demo/7", "services: {}", "DEBUG=1\n", null, null);
        assertThat(detail.get("envWritten")).isEqualTo(true);
    }

    @Test
    @DisplayName("docker: execute 自定义 compose 内容按字面量写入（heredoc 带引号）")
    void dockerExecute_composeWrittenVerbatim() {
        String compose = "services:\n  web:\n    image: 'nginx:alpine'\n";
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0))
                .thenAnswer(inv -> {
                    // 第二条命令（写 compose）必须包含用户内容原文（heredoc 定界符带引号）
                    String cmd = inv.getArgument(1);
                    assertThat(cmd).contains("<< 'COMPOSE_EOF_");
                    assertThat(cmd).contains(compose);
                    return CommandResult.ok("", 0);
                });
        when(sshService.executeWithRetry(eq(1L), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.ok("", 0, 1L));
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        ex.execute(1L, "/opt/deploy/demo/7", compose, null, "FROM nginx:alpine\n", null);
    }

    @Test
    @DisplayName("docker: 写 compose 失败 → SSH_ERROR 附 stderr")
    void dockerExecute_writeComposeFails_throws() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.fail(1, "", "bash: line 1: syntax error"));
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        assertThatThrownBy(() -> ex.execute(1L, "/opt/deploy/demo/7", "services: {}", null, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR))
                .hasMessageContaining("write docker-compose.yml")
                .hasMessageContaining("syntax error");
    }

    @Test
    @DisplayName("docker: 写 .env 失败 → SSH_ERROR")
    void dockerExecute_writeEnvFails_throws() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0))    // 写 compose 成功
                .thenReturn(CommandResult.fail(1, "", "env write denied"));
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        assertThatThrownBy(() -> ex.execute(1L, "/opt/deploy/demo/7", "services: {}", "A=1", null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR))
                .hasMessageContaining("write .env")
                .hasMessageContaining("env write denied");
    }

    @Test
    @DisplayName("docker: up -d 失败 → SSH_ERROR 附 stderr（compose up 拉镜像较慢，走 executeWithRetry）")
    void dockerExecute_upFails_throws() {
        when(sshService.execute(eq(1L), anyString()))
                .thenReturn(CommandResult.ok("", 0));   // 写 compose
        when(sshService.executeWithRetry(eq(1L), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.fail(1, "", "docker: not found"));
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        assertThatThrownBy(() -> ex.execute(1L, "/opt/deploy/demo/7", "services: {}", null, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR))
                .hasMessageContaining("docker-compose up")
                .hasMessageContaining("docker: not found");
    }

    // ---------- 端口避让（execute 第 6 参 port） ----------

    @Test
    @DisplayName("resolvePort: 空闲返原值；占用递增；耗尽回落；null 透传")
    void resolvePort_scansUpward() {
        assertThat(DockerStartExecutor.resolvePort(null, Set.of())).isNull();
        assertThat(DockerStartExecutor.resolvePort(8080, Set.of())).isEqualTo(8080);
        assertThat(DockerStartExecutor.resolvePort(8080, Set.of(8080))).isEqualTo(8081);
        assertThat(DockerStartExecutor.resolvePort(8080, Set.of(8080, 8081))).isEqualTo(8082);
        // 65535 截断：占用 65534/65535 → 65536 越界 → 回落原值
        assertThat(DockerStartExecutor.resolvePort(65533, Set.of(65533, 65534, 65535)))
                .isEqualTo(65533);
        // 扫描耗尽（100 次内全占用）→ 回落原值
        Set<Integer> allBusy = new java.util.HashSet<>();
        for (int i = 0; i < 101; i++) {
            allBusy.add(10000 + i);
        }
        assertThat(DockerStartExecutor.resolvePort(10000, allBusy)).isEqualTo(10000);
    }

    @Test
    @DisplayName("rewriteComposePort: 仅重写 host 侧匹配端口（带/不带引号），container 侧不动")
    void rewriteComposePort_onlyHostSideMatched() {
        String compose = "services:\n  web:\n    ports:\n      - \"8080:8080\"\n";
        assertThat(DockerStartExecutor.rewriteComposePort(compose, 8080, 8082))
                .isEqualTo("services:\n  web:\n    ports:\n      - \"8082:8080\"\n");
        assertThat(DockerStartExecutor.rewriteComposePort(compose, 9000, 9001)).isNull();
        String unquoted = "services:\n  web:\n    ports:\n      - 8080:9000\n";
        assertThat(DockerStartExecutor.rewriteComposePort(unquoted, 8080, 8090))
                .isEqualTo("services:\n  web:\n    ports:\n      - 8090:9000\n");
        // 无 ports 段
        assertThat(DockerStartExecutor.rewriteComposePort("services:\n  web:\n    image: nginx\n", 8080, 8082)).isNull();
        // 同端口不重写
        assertThat(DockerStartExecutor.rewriteComposePort(compose, 8080, 8080)).isNull();
        assertThat(DockerStartExecutor.rewriteComposePort(null, 8080, 8082)).isNull();
    }

    @Test
    @DisplayName("execute: 部署端口被占用 → compose host 端口自动避让 + detail.resolvedPort")
    void dockerExecute_portBusy_avoidsAndRewritesCompose() {
        String compose = "services:\n  web:\n    build:\n      context: ./extract\n    ports:\n      - \"8080:8080\"\n";
        String ssOut = "State  Recv-Q  Send-Q  Local Address:Port  Process\n"
                + "LISTEN 0      128    0.0.0.0:8080    0.0.0.0:*  users:((\"switch-monitor\",pid=734))\n"
                + "LISTEN 0      511    0.0.0.0:8081    0.0.0.0:*  users:((\"nginx\",pid=100))\n"
                + "LISTEN 0      128    [::]:8086       [::]:*     users:((\"other\",pid=99))\n";
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok(ssOut, 0));
        when(sshService.execute(eq(1L), anyString()))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    // 写 compose 的命令必须包含避让后的 host 端口（容器侧不动）
                    assertThat(cmd).contains("\"8082:8080\"");
                    return CommandResult.ok("", 0);
                });
        when(sshService.executeWithRetry(eq(1L), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.ok("", 0, 1L));
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        var detail = ex.execute(1L, "/opt/deploy/demo/7", compose, null, null, 8080);
        assertThat(detail.get("resolvedPort")).isEqualTo(8082);
    }

    @Test
    @DisplayName("execute: 部署端口空闲 → compose 原样写入，resolvedPort 为原端口")
    void dockerExecute_portFree_keepsOriginalCompose() {
        String compose = "services:\n  web:\n    ports:\n      - \"8080:8080\"\n";
        String ssOut = "State  Recv-Q  Send-Q  Local Address:Port  Process\n"
                + "LISTEN 0      128    0.0.0.0:22      0.0.0.0:*  users:((\"sshd\",pid=1))\n";
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok(ssOut, 0));
        when(sshService.execute(eq(1L), anyString()))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    assertThat(cmd).contains("\"8080:8080\"");
                    return CommandResult.ok("", 0);
                });
        when(sshService.executeWithRetry(eq(1L), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.ok("", 0, 1L));
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        var detail = ex.execute(1L, "/opt/deploy/demo/7", compose, null, null, 8080);
        assertThat(detail.get("resolvedPort")).isEqualTo(8080);
    }

    @Test
    @DisplayName("execute: 端口探测失败（ss 命令非 0）→ 尽力不阻断，compose 原样")
    void dockerExecute_probeFails_keepsOriginal() {
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.fail(127, "", "ss: not found"));
        when(sshService.execute(eq(1L), anyString()))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    assertThat(cmd).contains("\"8080:8080\"");
                    return CommandResult.ok("", 0);
                });
        when(sshService.executeWithRetry(eq(1L), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.ok("", 0, 1L));
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        var detail = ex.execute(1L, "/opt/deploy/demo/7",
                "services:\n  web:\n    ports:\n      - \"8080:8080\"\n", null, null, 8080);
        assertThat(detail.get("resolvedPort")).isEqualTo(8080);
    }

    @Test
    @DisplayName("extractHostPort: 提取 compose 首个 host 端口；无 ports / null 返回 null")
    void extractHostPort_readsFirstMapping() {
        assertThat(DockerStartExecutor.extractHostPort(
                "services:\n  web:\n    ports:\n      - \"8080:9000\"\n")).isEqualTo(8080);
        assertThat(DockerStartExecutor.extractHostPort(
                "services:\n  web:\n    ports:\n      - 7000:7000\n")).isEqualTo(7000);
        assertThat(DockerStartExecutor.extractHostPort("services:\n  web:\n    image: nginx\n")).isNull();
        assertThat(DockerStartExecutor.extractHostPort(null)).isNull();
    }

    // ---------- build context 规范化（上传 compose context: . → ./extract） ----------

    @Test
    @DisplayName("normalizeBuildContext: context 为根且根无 Dockerfile、extract 存在 → 改写为 ./extract")
    void normalizeBuildContext_rootToExtract() {
        String compose = "services:\n  backend:\n    build:\n      context: .\n      dockerfile: Dockerfile.backend\n";
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    if (cmd.contains("/opt/deploy/api-manager/29/Dockerfile")) {
                        return CommandResult.ok("MISSING", 1); // 根目录无 Dockerfile
                    }
                    return CommandResult.ok("EXISTS", 0);      // extract 存在
                });
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        String out = ex.normalizeBuildContext("/opt/deploy/api-manager/29", compose, 1L);
        assertThat(out).contains("context: ./extract");
        assertThat(out).contains("dockerfile: extract/Dockerfile.backend");
    }

    @Test
    @DisplayName("normalizeBuildContext: 根目录已有 Dockerfile → 不改写")
    void normalizeBuildContext_rootHasDockerfile_keeps() {
        String compose = "services:\n  backend:\n    build:\n      context: .\n";
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok("EXISTS", 0)); // 根目录有 Dockerfile
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        assertThat(ex.normalizeBuildContext("/opt/x", compose, 1L)).isEqualTo(compose);
    }

    @Test
    @DisplayName("normalizeBuildContext: context 已是 ./extract 或无 build 段 → 不改写")
    void normalizeBuildContext_alreadyCorrect_orNoBuild_keeps() {
        String already = "services:\n  web:\n    build:\n      context: ./extract\n";
        String noBuild = "services:\n  web:\n    image: nginx\n";
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        // 无 SSH 探测也应原样返回（不触发改写路径）
        assertThat(ex.normalizeBuildContext("/opt/x", already, 1L)).isEqualTo(already);
        assertThat(ex.normalizeBuildContext("/opt/x", noBuild, 1L)).isEqualTo(noBuild);
    }

    @Test
    @DisplayName("normalizeBuildContext: extract 不存在 → 原样（让 up -d 自行报错）")
    void normalizeBuildContext_noExtract_keeps() {
        String compose = "services:\n  web:\n    build:\n      context: .\n";
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    if (cmd.contains("/opt/deploy/api-manager/29/Dockerfile")) {
                        return CommandResult.ok("MISSING", 1);
                    }
                    return CommandResult.ok("MISSING", 1); // extract 也不存在
                });
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        assertThat(ex.normalizeBuildContext("/opt/x", compose, 1L)).isEqualTo(compose);
    }

    @Test
    @DisplayName("execute: 上传 compose context: . → 写盘前规范化为 ./extract（复现部署 94/95 缺陷）")
    void dockerExecute_rootContext_normalizedBeforeWrite() {
        String compose = "services:\n  backend:\n    build:\n      context: .\n      dockerfile: Dockerfile.backend\n";
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    if (cmd.contains("/opt/deploy/api-manager/29/Dockerfile")) {
                        return CommandResult.ok("MISSING", 1);
                    }
                    return CommandResult.ok("EXISTS", 0);
                });
        when(sshService.execute(eq(1L), anyString()))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    assertThat(cmd).contains("context: ./extract");
                    assertThat(cmd).contains("dockerfile: extract/Dockerfile.backend");
                    return CommandResult.ok("", 0);
                });
        when(sshService.executeWithRetry(eq(1L), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.ok("", 0, 1L));
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        ex.execute(1L, "/opt/deploy/api-manager/29", compose, null, null, null);
    }

    @Test
    @DisplayName("execute: deployment.port 已被上轮回写(8082)但 compose 原端口 8080 被占 → 锚定 compose 端口避让")
    void dockerExecute_composePortBusyWhileDeploymentPortFree_avoidsComposePort() {
        // 复现 deployment 20 缺陷：port=8082（回写值，空闲）+ compose 仍是 8080:8080（8080 被 switch-monitor 占）
        String compose = "services:\n  web:\n    build:\n      context: ./extract\n    ports:\n      - \"8080:8080\"\n";
        String ssOut = "State  Recv-Q  Send-Q  Local Address:Port  Process\n"
                + "LISTEN 0      128    0.0.0.0:8080    0.0.0.0:*  users:((\"switch-monitor\",pid=734))\n"
                + "LISTEN 0      511    0.0.0.0:8081    0.0.0.0:*  users:((\"nginx\",pid=100))\n";
        when(sshService.execute(eq(1L), anyString(), anyInt()))
                .thenReturn(CommandResult.ok(ssOut, 0));
        when(sshService.execute(eq(1L), anyString()))
                .thenAnswer(inv -> {
                    String cmd = inv.getArgument(1);
                    // 写 compose 的命令必须把 host 侧 8080 避让为 8082（容器侧 8080 不动）
                    assertThat(cmd).contains("\"8082:8080\"");
                    return CommandResult.ok("", 0);
                });
        when(sshService.executeWithRetry(eq(1L), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.ok("", 0, 1L));
        DockerStartExecutor ex = new DockerStartExecutor(sshService);
        var detail = ex.execute(1L, "/opt/deploy/demo/7", compose, null, null, 8082);
        assertThat(detail.get("resolvedPort")).isEqualTo(8082);
    }
}
