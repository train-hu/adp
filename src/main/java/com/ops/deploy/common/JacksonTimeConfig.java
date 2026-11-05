package com.ops.deploy.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全局 Jackson 时间序列化：LocalDateTime 原样输出为无时区后缀的 ISO 字符串。
 * <p>
 * 背景：服务端统一以<b>北京时间</b>写入时间戳（compose 设 {@code TZ: Asia/Shanghai}，
 * JVM 的 {@link LocalDateTime#now()} 与 PostgreSQL 的 {@code current_timestamp} 均为北京时间），
 * DB 各时间列为 {@code timestamp without time zone}。输出不带 Z 后缀的 ISO 串后，
 * 浏览器 {@code new Date(iso)} 按本地时区解析——对北京时区的用户显示即为记录的真实时刻。
 */
@Configuration
public class JacksonTimeConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeSerializer() {
        // 必须用 serializerByType 显式声明绑定类型：serializers(...) 依赖 Jackson 的
        // handledType() 反射推断泛型，在部分类加载/编译环境下推断为 null 会直接抛
        // "Unknown handled type" 导致应用启动失败
        return (Jackson2ObjectMapperBuilder builder) ->
                builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializerNoZone());
    }

    /** LocalDateTime → 无时区后缀的 ISO 字符串（值本身即北京时间，见类注释）。 */
    static final class LocalDateTimeSerializerNoZone extends JsonSerializer<LocalDateTime> {

        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
    }
}
