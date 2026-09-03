package com.rambo.infrastructure.database;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.lang.reflect.Field;
import java.util.Arrays;

@Configuration
@Slf4j
public class MyBatisEnumConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        // 全局枚举转换器工厂：自动识别所有带 @EnumValue 的枚举
        registry.addConverterFactory(new EnumConverterFactory());
    }

    // 全局枚举转换工厂（万能通用）
    public static class EnumConverterFactory implements ConverterFactory<String, Enum<?>> {
        @Override
        public <T extends Enum<?>> Converter<String, T> getConverter(Class<T> targetType) {
            return source -> {
                try {
                    // 找到标了 @EnumValue 的字段
                    Field enumField = Arrays.stream(targetType.getDeclaredFields())
                            .filter(f -> f.isAnnotationPresent(EnumValue.class))
                            .findFirst()
                            .orElseThrow(() -> new RuntimeException("未找到@EnumValue枚举字段"));

                    enumField.setAccessible(true);

                    // 遍历枚举匹配 value
                    for (T enumConstant : targetType.getEnumConstants()) {
                        Object fieldValue = enumField.get(enumConstant);
                        if (String.valueOf(fieldValue).equals(source)) {
                            return enumConstant;
                        }
                    }
                } catch (Exception e) {
                    log.error("枚举转换失败，枚举类型：{}，值：{}", targetType, source, e);
                }
                return null;
            };
        }
    }
}