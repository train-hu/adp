package com.ops.deploy.deploy;

/**
 * shell 单引号转义工具（POSIX 兼容）。
 *
 * <p>deploy 包把 workdir / unitName / 路径等拼接进远端 shell 命令前统一转义，
 * 防命令注入。{@code ssh.BackgroundTaskCommands.shellQuote} 为 ssh 包私有，
 * deploy 包自建同款实现。规则：null/空 → {@code ''}；无特殊字符 → 原样；
 * 含 {@code '} → 以 {@code '\''} 转义后整体包裹单引号。
 */
public final class ShellQuoter {

    private ShellQuoter() {
    }

    public static String quote(String s) {
        if (s == null || s.isEmpty()) {
            return "''";
        }
        if (s.indexOf('\'') < 0 && s.chars().allMatch(c -> Character.isLetterOrDigit(c)
                || "/._-".indexOf(c) >= 0)) {
            return s;
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
