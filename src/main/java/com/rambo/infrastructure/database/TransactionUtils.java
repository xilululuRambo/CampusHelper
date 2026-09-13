package com.rambo.infrastructure.database;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.IntConsumer;

/**
 * 事务同步工具：
 * 将外部副作用（MQ 发送、MongoDB 写、Redis 写、OSS 删除等）与数据库事务解耦，
 * 避免事务回滚后出现「假通知 / 假会话 / 排行榜虚增 / 误删文件」等不一致。
 * 使用原则：
 * 1. 外部副作用应延后到 {@link #afterCommit} 执行——事务提交成功才执行，回滚则不执行；
 * 2. 事务内必须完成的外部写（如 OSS 上传后需把 objectName 存 DB），用 {@link #onRollback}
 *    注册回滚补偿（删除孤儿文件）；
 * 3. 无事务上下文时 afterCommit 立即执行（行为与直接调用一致），onRollback 不执行。
 */
@Slf4j
public final class TransactionUtils {

    private TransactionUtils() {
    }

    /**
     * 事务提交后执行；无事务上下文时立即执行。
     * 回调内的异常会被捕获并记录日志，不影响调用线程（afterCommit 阶段事务已提交，无法回滚）。
     *
     * @param action 提交后执行的动作
     */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runSafely(action);
                }
            });
        } else {
            runSafely(action);
        }
    }

    /**
     * 事务结束后执行（无论提交还是回滚）。
     * <p>典型场景：分布式锁释放。若在 {@code @Transactional} 方法的 finally 中直接 unlock，
     * 锁释放会早于事务提交——其他线程抢到锁后可能读到未提交数据；注册本回调可把解锁
     * 推迟到事务提交/回滚完成后，彻底消除该竞态窗口。</p>
     * <p>无事务上下文时立即执行（行为与直接调用一致）。回调内的异常被捕获记录，不影响主流程。</p>
     *
     * @param action 事务结束后执行的动作
     */
    public static void afterTransaction(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    runSafely(action);
                }
            });
        } else {
            runSafely(action);
        }
    }

    /**
     * 事务回滚时执行（补偿动作，如删除事务内上传的 OSS 孤儿文件）。
     * 无事务上下文时不执行——没有回滚语义，调用方应把补偿注册放在事务边界内。
     *
     * @param action 回滚时执行的动作
     */
    public static void onRollback(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("onRollback 在无事务上下文下被调用，补偿动作不会执行");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    runSafely(action);
                }
            }
        });
    }

    /**
     * 事务完成后执行，并把最终状态（{@link TransactionSynchronization#STATUS_COMMITTED} /
     * {@link TransactionSynchronization#STATUS_ROLLED_BACK}）告知回调。
     *
     * <p>与 {@link #afterTransaction} 的区别：后者只能延后执行时机，回调拿不到回滚信号，
     * 因此无法满足「必须先知道事务最终成败，才能决定写什么内容」的场景。典型代表是审计日志：
     * 内层方法在外层事务中执行时，其自身逻辑成功了，但如果外层事务随后回滚，业务变更并未生效，
     * 此时若照常落一条「成功」审计，就会留下与事实相反的假记录。本方法让调用方能据状态修正结论。</p>
     *
     * <p>无事务上下文时以 {@link TransactionSynchronization#STATUS_COMMITTED} 立即执行
     * （此时业务写已由自动提交生效，语义等价）。回调内异常被捕获记录，不影响调用线程。</p>
     *
     * @param action 接收事务最终状态的回调
     */
    public static void afterCompletion(IntConsumer action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    runSafely(() -> action.accept(status));
                }
            });
        } else {
            runSafely(() -> action.accept(TransactionSynchronization.STATUS_COMMITTED));
        }
    }

    // 线程安全地执行 action，忽略其返回值和异常，仅记录日志。用于 afterCommit 和 onRollback 中
    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("事务提交后执行动作失败：{}", e.getMessage(), e);
        }
    }
}
