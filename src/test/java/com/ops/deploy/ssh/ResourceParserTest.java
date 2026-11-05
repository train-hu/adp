package com.ops.deploy.ssh;

import com.ops.deploy.server.ServerVO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResourceParser 纯逻辑单测：给定典型/残缺 probe stdout，断言各字段解析正确、缺省兜底合理。
 */
class ResourceParserTest {

    private static final String TYPICAL = String.join("\n",
            "CPU=8",
            "LOAD=0.10 0.05 0.01 1/321 12345",
            "MEM=MemTotal:       16384000 kB",
            "MEM=MemAvailable:    8192000 kB",
            "DISK=/dev/vda1 1000000 4096 500000 1% /",
            "PORTS=0.0.0.0:22",
            "PORTS=127.0.0.1:5432",
            "PORTS=10.0.0.5:8080",
            "JAVA=java version \"17.0.2\" 2022-01-18",
            "PYTHON3=Python 3.11.2",
            "NODE=v18.19.0",
            "DOCKER=absent",
            "KUBECTL=absent",
            "").strip();

    @Test
    void parseTypicalOutput_extractsAllFields() {
        ResourceProfile p = ResourceParser.parse(TYPICAL);

        assertThat(p.getCpuCores()).isEqualTo(8);
        assertThat(p.getLoadAvg()).isEqualTo("0.10");
        assertThat(p.getMemTotalKb()).isEqualTo(16_384_000L);
        assertThat(p.getMemAvailableKb()).isEqualTo(8_192_000L);
        assertThat(p.getDiskTotalKb()).isEqualTo(1_000_000L);
        assertThat(p.getDiskAvailableKb()).isEqualTo(500_000L);
        assertThat(p.getUsedPorts()).containsExactly("22", "5432", "8080");
        assertThat(p.getRuntimes())
                .containsEntry("java", "java version \"17.0.2\" 2022-01-18")
                .containsEntry("python3", "Python 3.11.2")
                .containsEntry("node", "v18.19.0")
                .containsEntry("docker", "absent")
                .containsEntry("kubectl", "absent");
    }

    @Test
    void parseEmptyOrGarbage_returnsSafeDefaults() {
        ResourceProfile p = ResourceParser.parse("");

        assertThat(p.getCpuCores()).isZero();
        assertThat(p.getLoadAvg()).isEmpty();
        assertThat(p.getMemTotalKb()).isZero();
        assertThat(p.getMemAvailableKb()).isZero();
        assertThat(p.getDiskTotalKb()).isZero();
        assertThat(p.getDiskAvailableKb()).isZero();
        assertThat(p.getUsedPorts()).isEmpty();
        assertThat(p.getRuntimes()).containsOnlyKeys("java", "python3", "node", "docker", "kubectl");
        assertThat(p.getRuntimes().values())
                .containsExactly("absent", "absent", "absent", "absent", "absent");
    }

    @Test
    void parsePartialOutput_onlyFillsWhatIsPresent() {
        String partial = "CPU=2\nPORTS=192.168.1.10:9090\nJAVA=absent";
        ResourceProfile p = ResourceParser.parse(partial);

        assertThat(p.getCpuCores()).isEqualTo(2);
        assertThat(p.getUsedPorts()).containsExactly("9090");
        assertThat(p.getLoadAvg()).isEmpty();
        assertThat(p.getMemTotalKb()).isZero();
        assertThat(p.getRuntimes())
                .containsEntry("java", "absent")
                .containsEntry("python3", "absent")
                .containsEntry("node", "absent")
                .containsEntry("docker", "absent")
                .containsEntry("kubectl", "absent");
    }

    @Test
    void parseHandlesCrlfLineEndings() {
        ResourceProfile p = ResourceParser.parse("CPU=4\r\nLOAD=1.00 0.50 0.25\r\nMEM=MemTotal: 1024 kB");

        assertThat(p.getCpuCores()).isEqualTo(4);
        assertThat(p.getLoadAvg()).isEqualTo("1.00");
        assertThat(p.getMemTotalKb()).isEqualTo(1024L);
    }

    @Test
    void parseNullInput_returnsSafeDefaults() {
        assertThat(ResourceParser.parse(null).getCpuCores()).isZero();
        assertThat(ResourceParser.parse(null).getUsedPorts()).isEmpty();
    }
}
