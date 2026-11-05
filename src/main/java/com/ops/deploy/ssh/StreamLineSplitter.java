package com.ops.deploy.ssh;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 流式行分割器：把字节流按 \n 拆成行（去除 \r），每行按 UTF-8 解码后回调 lineConsumer。
 * 线程安全（synchronized），供 MINA IO 线程与读线程共用。
 *
 * <p>UTF-8 的换行符 \n 是单字节 0x0A，不会出现在多字节序列内部，故按 \n 字节拆行安全；
 * 缓冲按字节累积，emit 时整体按 UTF-8 解码，避免多字节字符被逐字节拆散成乱码。
 */
public final class StreamLineSplitter {

    private final Consumer<String> lineConsumer;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    public StreamLineSplitter(Consumer<String> lineConsumer) {
        this.lineConsumer = lineConsumer;
    }

    /** 写入单个字节（0-255）。'\n' 输出一行（含空行），'\r' 丢弃，其余追加缓冲。 */
    public synchronized void write(int b) {
        if (b == '\n') {
            emitLine();
        } else if (b != '\r') {
            buf.write(b);
        }
    }

    /** 写入字节数组。Java byte 有符号，&amp; 0xFF 转无符号后逐字节写入。 */
    public synchronized void write(byte[] b, int off, int len) {
        for (int i = 0; i < len; i++) {
            write(b[off + i] & 0xFF);
        }
    }

    /** 流收尾 flush：仅当末尾存在无 \n 的半行（缓冲非空）时输出一行。 */
    public synchronized void flush() {
        if (buf.size() > 0) {
            emitLine();
        }
    }

    /** 输出当前缓冲为一行（UTF-8 解码）并清空（'\n' 触发，即使为空行也输出）。 */
    private void emitLine() {
        lineConsumer.accept(new String(buf.toByteArray(), StandardCharsets.UTF_8));
        buf.reset();
    }
}
