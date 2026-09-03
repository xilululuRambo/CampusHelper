package com.rambo.infrastructure.database;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Slf4j
@Component // 关键注解：将处理器注册为Spring Bean，让MP能发现它
public class MyMetaObjectHandler implements MetaObjectHandler {

    // 插入操作时，会执行这个方法
    @Override
    public void insertFill(MetaObject metaObject) {
        log.info("开始执行插入填充...");
        // 注意：传入的值类型必须与实体类字段类型严格一致！
        // 这里统一使用 LocalDateTime.now()
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }

    // 更新操作时，会执行这个方法
    @Override
    public void updateFill(MetaObject metaObject) {
        log.info("开始执行更新填充...");
        // 只为配置了 INSERT_UPDATE 或 UPDATE 策略的字段填充值
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}