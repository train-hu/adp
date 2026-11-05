package com.ops.deploy.ssh;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link SshExecutor#probe} 采集到的只读命令 stdout（带 CPU=/LOAD=/MEM=/DISK=/PORTS=/运行时 前缀行）
 * 解析成 {@link ResourceProfile}。纯函数、可单测，缺失字段兜底 0/空/"absent"。
 */
public final class ResourceParser {

    /** 探测的运行时及其探测前缀（顺序即 runtimes 输出顺序）。 */
    static final String[][] RUNTIME_DEFS = {
            {"java", "JAVA="},
            {"python3", "PYTHON3="},
            {"node", "NODE="},
            {"docker", "DOCKER="},
            {"kubectl", "KUBECTL="},
    };

    private ResourceParser() {
    }

    public static ResourceProfile parse(String probeOutput) {
        ResourceProfile.Builder b = ResourceProfile.builder();
        List<String> ports = new ArrayList<>();
        Map<String, String> runtimeValues = new LinkedHashMap<>();
        for (String[] def : RUNTIME_DEFS) {
            runtimeValues.put(def[0], "absent");
        }
        if (probeOutput == null || probeOutput.isBlank()) {
            b.runtimes(runtimeValues);
            return b.build();
        }

        for (String rawLine : probeOutput.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("CPU=")) {
                b.cpuCores(parseInt(line.substring(4)));
            } else if (line.startsWith("LOAD=")) {
                String load = line.substring(5).strip();
                // 取 /proc/loadavg 第一列（1 分钟负载）
                int sp = load.indexOf(' ');
                b.loadAvg(sp > 0 ? load.substring(0, sp).strip() : load);
            } else if (line.startsWith("MEM=MemTotal:")) {
                b.memTotalKb(parseKb(line));
            } else if (line.startsWith("MEM=MemAvailable:")) {
                b.memAvailableKb(parseKb(line));
            } else if (line.startsWith("DISK=")) {
                parseDisk(line.substring(5), b);
            } else if (line.startsWith("PORTS=")) {
                String p = parsePort(line.substring(6));
                if (p != null && !ports.contains(p)) {
                    ports.add(p);
                }
            } else {
                for (String[] def : RUNTIME_DEFS) {
                    if (line.startsWith(def[1])) {
                        String value = line.substring(def[1].length()).strip();
                        runtimeValues.put(def[0], value.isEmpty() ? "absent" : value);
                        break;
                    }
                }
            }
        }

        b.usedPorts(ports).runtimes(runtimeValues);
        return b.build();
    }

    /** 解析 "MemTotal:       16384000 kB" 中的数字（取第一个数字 token）。 */
    static long parseKb(String line) {
        for (String tok : line.split("[^0-9]+")) {
            if (!tok.isEmpty()) {
                return parseLongSafe(tok);
            }
        }
        return 0L;
    }

    /**
     * 解析 {@code df -kP /} 的数据行："fs 1000000 4096 500000 1% /"
     * → total=第 2 列, available=第 4 列。解析失败兜底 0。
     */
    static void parseDisk(String dfLine, ResourceProfile.Builder b) {
        String[] cols = dfLine.trim().split("\\s+");
        if (cols.length >= 4) {
            b.diskTotalKb(parseLongSafe(cols[1]))
                    .diskAvailableKb(parseLongSafe(cols[3]));
        }
    }

    /** 从 "0.0.0.0:22" / "127.0.0.1:5432" / "22" 提取端口号。 */
    static String parsePort(String value) {
        String v = value.strip();
        int colon = v.lastIndexOf(':');
        String candidate = colon >= 0 ? v.substring(colon + 1) : v;
        candidate = candidate.strip();
        if (candidate.isEmpty()) {
            return null;
        }
        for (char c : candidate.toCharArray()) {
            if (!Character.isDigit(c)) {
                return null;
            }
        }
        int port = Integer.parseInt(candidate);
        if (port < 0 || port > 65535) {
            return null;
        }
        return candidate;
    }

    static int parseInt(String s) {
        return (int) parseLongSafe(s.strip());
    }

    static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
