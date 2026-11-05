package com.ops.deploy.ssh;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 远端服务器资源画像（由 {@link SshExecutor#probe} 采集）。
 * 所有字段只读；解析缺省时为 0 / 空串 / 空集合 / "absent"。
 * runtimes 键=运行时名（java/python3/node/docker/kubectl），值=版本或 "absent"。
 */
public final class ResourceProfile {

    private final int cpuCores;
    private final String loadAvg;
    private final long memTotalKb;
    private final long memAvailableKb;
    private final long diskTotalKb;
    private final long diskAvailableKb;
    private final List<String> usedPorts;
    private final Map<String, String> runtimes;

    private ResourceProfile(Builder b) {
        this.cpuCores = b.cpuCores;
        this.loadAvg = b.loadAvg;
        this.memTotalKb = b.memTotalKb;
        this.memAvailableKb = b.memAvailableKb;
        this.diskTotalKb = b.diskTotalKb;
        this.diskAvailableKb = b.diskAvailableKb;
        this.usedPorts = b.usedPorts;
        this.runtimes = b.runtimes;
    }

    public static ResourceProfile empty() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getCpuCores() {
        return cpuCores;
    }

    public String getLoadAvg() {
        return loadAvg;
    }

    public long getMemTotalKb() {
        return memTotalKb;
    }

    public long getMemAvailableKb() {
        return memAvailableKb;
    }

    public long getDiskTotalKb() {
        return diskTotalKb;
    }

    public long getDiskAvailableKb() {
        return diskAvailableKb;
    }

    public List<String> getUsedPorts() {
        return usedPorts;
    }

    public Map<String, String> getRuntimes() {
        return runtimes;
    }

    @Override
    public String toString() {
        return "ResourceProfile{cpuCores=" + cpuCores + ", loadAvg='" + loadAvg
                + "', memTotalKb=" + memTotalKb + ", memAvailableKb=" + memAvailableKb
                + ", diskTotalKb=" + diskTotalKb + ", diskAvailableKb=" + diskAvailableKb
                + ", usedPorts=" + usedPorts + ", runtimes=" + runtimes + "}";
    }

    public static final class Builder {
        private int cpuCores;
        private String loadAvg = "";
        private long memTotalKb;
        private long memAvailableKb;
        private long diskTotalKb;
        private long diskAvailableKb;
        private List<String> usedPorts = Collections.emptyList();
        private Map<String, String> runtimes = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder cpuCores(int v) {
            this.cpuCores = v;
            return this;
        }

        public Builder loadAvg(String v) {
            this.loadAvg = v;
            return this;
        }

        public Builder memTotalKb(long v) {
            this.memTotalKb = v;
            return this;
        }

        public Builder memAvailableKb(long v) {
            this.memAvailableKb = v;
            return this;
        }

        public Builder diskTotalKb(long v) {
            this.diskTotalKb = v;
            return this;
        }

        public Builder diskAvailableKb(long v) {
            this.diskAvailableKb = v;
            return this;
        }

        public Builder usedPorts(List<String> v) {
            this.usedPorts = v == null ? Collections.emptyList() : List.copyOf(v);
            return this;
        }

        public Builder runtimes(Map<String, String> v) {
            this.runtimes = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
            return this;
        }

        public Builder runtime(String name, String version) {
            this.runtimes.put(name, version);
            return this;
        }

        public ResourceProfile build() {
            return new ResourceProfile(this);
        }
    }
}
