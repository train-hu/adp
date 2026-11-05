package com.ops.deploy.ssh;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BackgroundTaskCommands 纯逻辑单测：nohup 包装命令构造 + 状态文件解析。
 */
class BackgroundTaskCommandsTest {

    @Test
    void buildNohupCommand_containsAllRequiredParts() {
        String cmd = BackgroundTaskCommands.buildNohupCommand(
                "/opt/app", "./deploy.sh --env prod", "/var/log/deploy.log", "/tmp/status-1.txt");

        // 整个 wrapper 由 nohup 包裹：负责 wait/写终态的进程本身免疫 SIGHUP
        assertThat(cmd).startsWith("nohup sh -c '");
        assertThat(cmd).contains("cd /opt/app");
        assertThat(cmd).contains("./deploy.sh --env prod");
        assertThat(cmd).contains("/var/log/deploy.log");
        assertThat(cmd).contains("/tmp/status-1.txt");
        // 先写 RUNNING
        assertThat(cmd).contains("RUNNING");
        // 结束时写 DONE/FAILED + exit code
        assertThat(cmd).contains("DONE");
        assertThat(cmd).contains("FAILED");
        // cd 在 nohup 包裹的 wrapper 内部（不改 SSH exec 会话的父 shell 工作目录）
        assertThat(cmd.indexOf("nohup")).isLessThan(cmd.indexOf("cd /opt/app"));
        assertThat(cmd.indexOf("cd /opt/app")).isLessThan(cmd.lastIndexOf('\''));
        // 整体后台、不阻塞 SSH 会话
        assertThat(cmd).endsWith("&");
        // 日志追加
        assertThat(cmd).contains(">>");
    }

    @Test
    void buildNohupCommand_quotesWorkdirWithSpaces() {
        String cmd = BackgroundTaskCommands.buildNohupCommand(
                "/opt/my app", "run.sh", "/tmp/deploy.log", "/tmp/status.txt");

        assertThat(cmd).contains("cd '/opt/my app'");
    }

    @Test
    void buildNohupCommand_escapesSingleQuoteInWorkdir() {
        // 路径含单引号：外层 sh -c '...' 内的 '\'' 转义不能被破坏
        String cmd = BackgroundTaskCommands.buildNohupCommand(
                "/opt/a'b", "run.sh", "/tmp/deploy.log", "/tmp/status.txt");

        assertThat(cmd).contains("cd '/opt/a'\\''b'");
    }

    @Test
    void parseStatus_doneZero() {
        BackgroundStatus s = BackgroundTaskCommands.parseStatus("DONE:0");

        assertThat(s.getState()).isEqualTo("DONE");
        assertThat(s.getExitCode()).isEqualTo(0);
    }

    @Test
    void parseStatus_failedOne() {
        BackgroundStatus s = BackgroundTaskCommands.parseStatus("FAILED:1");

        assertThat(s.getState()).isEqualTo("FAILED");
        assertThat(s.getExitCode()).isEqualTo(1);
    }

    @Test
    void parseStatus_running() {
        BackgroundStatus s = BackgroundTaskCommands.parseStatus("RUNNING");

        assertThat(s.getState()).isEqualTo("RUNNING");
        assertThat(s.getExitCode()).isNull();
    }

    @Test
    void parseStatus_toleratesWhitespaceAndCrlf() {
        assertThat(BackgroundTaskCommands.parseStatus("  DONE:0  ").getState()).isEqualTo("DONE");
        assertThat(BackgroundTaskCommands.parseStatus("FAILED:123\r\n").getExitCode()).isEqualTo(123);
    }

    @Test
    void parseStatus_garbageOrEmpty_isUnknown() {
        assertThat(BackgroundTaskCommands.parseStatus("").getState()).isEqualTo("UNKNOWN");
        assertThat(BackgroundTaskCommands.parseStatus(null).getState()).isEqualTo("UNKNOWN");
        assertThat(BackgroundTaskCommands.parseStatus("whatever:99").getState()).isEqualTo("UNKNOWN");
        assertThat(BackgroundTaskCommands.parseStatus("DONE").getState()).isEqualTo("UNKNOWN");
        assertThat(BackgroundTaskCommands.parseStatus("DONE:x").getState()).isEqualTo("UNKNOWN");
    }
}
