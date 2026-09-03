package com.rambo.infrastructure.database;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
public class TransactionExecutor {

    @Resource
    private TransactionTemplate transactionTemplate;

    public <T> T execute(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    public void execute(Runnable action) {
        transactionTemplate.execute(status -> {
            action.run();
            return null;
        });
    }
}