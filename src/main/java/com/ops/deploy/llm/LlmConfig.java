package com.ops.deploy.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LlmChatClient Bean 装配：连接/读超时从配置注入
 * （deploy.llm.connect-timeout-sec / deploy.llm.read-timeout-sec，默认 10s / 120s）。
 * 读超时给足是因为大模型生成响应可能较慢。
 */
@Configuration
public class LlmConfig {

    @Bean
    public LlmChatClient llmChatClient(
            @Value("${deploy.llm.connect-timeout-sec:10}") int connectTimeoutSec,
            @Value("${deploy.llm.read-timeout-sec:120}") int readTimeoutSec) {
        return new LlmChatClient(connectTimeoutSec, readTimeoutSec);
    }
}
