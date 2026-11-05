package com.ops.deploy.ssh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StreamLineSplitter} 纯逻辑单测：按 \n 拆行、\r 剥离、无结尾换行需 flush、
 * 空行、UTF-8 多字节。
 */
class StreamLineSplitterTest {

    @Test
    @DisplayName("单行 + 结尾换行 → 一行")
    void singleLine() {
        List<String> lines = new ArrayList<>();
        StreamLineSplitter splitter = new StreamLineSplitter(lines::add);
        splitter.write("hello".getBytes(StandardCharsets.UTF_8), 0, "hello".length());
        splitter.write('\n');

        assertThat(lines).containsExactly("hello");
    }

    @Test
    @DisplayName("多行 + 结尾换行 → 三行")
    void multipleLines() {
        List<String> lines = new ArrayList<>();
        StreamLineSplitter splitter = new StreamLineSplitter(lines::add);
        splitter.write("a\nb\nc\n".getBytes(StandardCharsets.UTF_8), 0, 6);

        assertThat(lines).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("CRLF：\\r 被剥离，行内容无 \\r")
    void crlfStripsCarriageReturn() {
        List<String> lines = new ArrayList<>();
        StreamLineSplitter splitter = new StreamLineSplitter(lines::add);
        splitter.write("line1\r\nline2\r\n".getBytes(StandardCharsets.UTF_8), 0, 14);

        assertThat(lines).containsExactly("line1", "line2");
    }

    @Test
    @DisplayName("无结尾换行需显式 flush 才输出")
    void trailingNoNewlineRequiresFlush() {
        List<String> lines = new ArrayList<>();
        StreamLineSplitter splitter = new StreamLineSplitter(lines::add);
        splitter.write("partial".getBytes(StandardCharsets.UTF_8), 0, 7);
        assertThat(lines).isEmpty();

        splitter.flush();
        assertThat(lines).containsExactly("partial");
    }

    @Test
    @DisplayName("空行：连续 \\n 产生空字符串行")
    void emptyLines() {
        List<String> lines = new ArrayList<>();
        StreamLineSplitter splitter = new StreamLineSplitter(lines::add);
        splitter.write("a\n\nb\n".getBytes(StandardCharsets.UTF_8), 0, 5);

        assertThat(lines).containsExactly("a", "", "b");
    }

    @Test
    @DisplayName("UTF-8 多字节字符按字节喂入，中文行原样输出")
    void utf8Multibyte() {
        List<String> lines = new ArrayList<>();
        StreamLineSplitter splitter = new StreamLineSplitter(lines::add);
        String line = "你好，世界";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        // 逐字节喂入（模拟 MINA 分片到达）
        for (byte b : bytes) {
            splitter.write(b & 0xFF);
        }
        splitter.write('\n');

        assertThat(lines).containsExactly(line);
    }
}
