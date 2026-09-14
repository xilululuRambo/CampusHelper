package com.rambo.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rambo.BaseApiTest;
import com.rambo.infrastructure.messaging.RabbitmqConfig;
import com.rambo.infrastructure.messaging.RabbitmqProducer;
import com.rambo.module.notification.enums.NotificationType;
import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.server.consumer.NotificationMqConsumer;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.util.ReflectionTestUtils.setField;

/**
 * RabbitMQ <b>可靠性</b>端到端测试：打<b>真实 broker</b>（VM `192.168.100.128:5672`，RabbitMQ 3.13.7）。
 *
 * <p><b>为什么此前是空白</b>：{@link BaseApiTest} 把 {@link RabbitmqProducer} 整体 mock，
 * 且 {@code application-test.yml} 设了 {@code spring.rabbitmq.listener.simple.auto-startup=false}
 * 关闭消费者容器 —— 于是「发布确认、路由、持久化属性、消费 ack、死信」全部<b>从未与真实 broker 交互过一次</b>。
 * 此前 {@code ScheduledJobTest} 里那几条验证只证明了「Job 挑对了行」，而它调用的生产者是 mock：
 * 手工 {@code cb.accept(false, "模拟 broker 拒绝")} 注入的回调，与 broker 会不会真的 nack 无关。</p>
 *
 * <p><b>本类怎么绕开 mock</b>：嵌套 {@link TestConfiguration} 里用 {@code @Bean @Primary}
 * 注册<b>真实</b> {@link RabbitmqProducer}（其 {@code rabbitTemplate} 用反射注入容器中真实的
 * {@code RabbitTemplate} —— 因为它是字段注入而非构造器注入）。{@code @Primary} 显式 bean 定义
 * 优先级高于 {@code @MockBean} 的 mock 定义，因此这里能赢（与 {@code EsOutboxSyncEndToEndTest} 同一手法）。</p>
 *
 * <p><b>⚠️ 本类不启动生产消费者的容器（有意为之，不是遗漏）</b>：{@code notification.queue}
 * 在真实 broker 上积压着 <b>187 条历史测试消息</b>（生产者发出、消费者从未启动 → 无人消费）。
 * 一旦把 {@code auto-startup} 打开，这些积压会被一次性灌进测试库，既污染数据又让用例结果依赖积压量。
 * 因此本类改用<b>与生产完全相同的容器工厂</b>（同一 {@code SimpleRabbitListenerContainerFactory}：
 * 同一条消息转换器、同一个 MANUAL ack 模式、同一个 prefetch），把真实消费者
 * {@link NotificationMqConsumer#onMessage} 挂到<b>测试专用队列</b>上驱动 —— 于是
 * 「broker → 容器 → JSON 转换器 → 消费者方法 → {@code basicAck}」整条链是真实执行的，
 * <b>唯一未覆盖的是「@RabbitListener 注解把队列名绑成 notification.queue」这一步</b>；
 * 该步由 {@link #productionConsumerEndpoint_isRegisteredOnNotificationQueue()} 单独断言补偿。</p>
 *
 * <p><b>测试拓扑独立</b>：本类声明自己的 exchange/queue/DLX/DLQ（{@code test.mq.rel.*}），
 * 与生产拓扑零交叉；每个用例前后清空自己的测试队列，用例之间不残留。</p>
 *
 * <p><b>为什么需要 Awaitility 类的东西</b>：与 ES 侧不同 —— 这里每一个环节都是异步的
 * （broker 回确认、容器拉消息、消费者 ack 后才从队列消失）。故用<b>有界短轮询 / CountDownLatch</b>
 * 等待「终点条件」，而不是 {@code Thread.sleep} 猜时间，避免为几个用例引入新依赖。</p>
 */
class MqReliabilityEndToEndTest extends BaseApiTest {

    // ==================== 测试专用拓扑（与生产拓扑零交叉） ====================

    static final String TEST_EXCHANGE = "test.mq.rel.exchange";
    static final String TEST_QUEUE = "test.mq.rel.queue";
    static final String TEST_ROUTING_KEY = "test.mq.rel.key";
    static final String TEST_DLX = "test.mq.rel.dlx";
    static final String TEST_DLQ = "test.mq.rel.dlq";
    static final String TEST_DL_ROUTING_KEY = "test.mq.rel.dl.key";

    /** 一个在测试交换机上<b>没有任何绑定</b>的路由键，用于验证「不可路由」行为 */
    static final String UNROUTABLE_KEY = "test.mq.rel.no.such.binding";

    static final String EP_CONSUMER = "mqTestConsumerEndpoint";
    static final String EP_NACK = "mqTestNackEndpoint";
    static final String EP_DLQ_PROBE = "mqTestDlqProbeEndpoint";

    /** RabbitMQ 管理插件端口（用于读取 broker 真值：队列参数无法通过 AMQP 协议读回） */
    private static final int MGMT_PORT = 15672;
    private static final String VHOST_PATH = "%2F";

    /**
     * 真实 MQ 配置：用真实 {@link RabbitmqProducer} 覆盖 {@code BaseApiTest} 的 {@code @MockBean}，
     * 并声明测试专用拓扑与三个测试用消费者端点。
     */
    @TestConfiguration
    static class RealMqConfig {

        @Bean
        @Primary
        RabbitmqProducer realRabbitmqProducer(org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate) {
            RabbitmqProducer real = new RabbitmqProducer();
            // rabbitTemplate 是 @Resource 字段注入，构造后反射塞入容器里真实的 RabbitTemplate
            setField(real, "rabbitTemplate", rabbitTemplate);
            return real;
        }

        // ---------- 测试拓扑 ----------

        @Bean
        TopicExchange mqTestExchange() {
            return new TopicExchange(TEST_EXCHANGE, true, false);
        }

        @Bean
        Queue mqTestQueue() {
            // 与生产 notification.queue 同构：带死信交换机 + 死信路由键
            return QueueBuilder.durable(TEST_QUEUE)
                    .deadLetterExchange(TEST_DLX)
                    .deadLetterRoutingKey(TEST_DL_ROUTING_KEY)
                    .build();
        }

        @Bean
        Binding mqTestBinding(TopicExchange mqTestExchange, Queue mqTestQueue) {
            return BindingBuilder.bind(mqTestQueue).to(mqTestExchange).with(TEST_ROUTING_KEY);
        }

        @Bean
        TopicExchange mqTestDlx() {
            return new TopicExchange(TEST_DLX, true, false);
        }

        @Bean
        Queue mqTestDlq() {
            return QueueBuilder.durable(TEST_DLQ).build();
        }

        @Bean
        Binding mqTestDlBinding(TopicExchange mqTestDlx, Queue mqTestDlq) {
            return BindingBuilder.bind(mqTestDlq).to(mqTestDlx).with(TEST_DL_ROUTING_KEY);
        }

        // ---------- 测试用消费者端点（全部 @RabbitListener，故与生产同一套容器装配） ----------

        @Bean
        TestConsumer mqTestConsumer(NotificationMqConsumer delegate) {
            return new TestConsumer(delegate);
        }

        @Bean
        DlqProbe mqTestDlqProbe() {
            return new DlqProbe();
        }

        @Bean
        NackConsumer mqTestNackConsumer() {
            return new NackConsumer();
        }
    }

    /**
     * 测试用消费者：方法体<b>原样委托</b>给生产消费者 {@link NotificationMqConsumer#onMessage}，
     * 只把队列换成测试队列。同时把原始 {@link Message} 留档，供断言持久化属性。
     */
    static class TestConsumer {
        private final NotificationMqConsumer delegate;
        /** 收到的原始 AMQP 消息（用于断言 deliveryMode / messageId 等线级属性） */
        final BlockingQueue<Message> rawMessages = new LinkedBlockingQueue<>();
        /** 委托调用次数（用于断言「确实被消费了」而不是靠 DB 副作用间接推断） */
        final AtomicInteger invoked = new AtomicInteger();

        TestConsumer(NotificationMqConsumer delegate) {
            this.delegate = delegate;
        }

        @RabbitListener(id = EP_CONSUMER, queues = TEST_QUEUE)
        public void onMessage(Message amqpMessage, NotificationMessage msg, Channel channel) throws IOException {
            invoked.incrementAndGet();
            rawMessages.add(amqpMessage);
            delegate.onMessage(amqpMessage, msg, channel);
        }
    }

    /** 死信探测消费者：收到即留档并手动 ack，用于证明 DLX 路由真实生效 */
    static class DlqProbe {
        final BlockingQueue<String> bodies = new LinkedBlockingQueue<>();

        @RabbitListener(id = EP_DLQ_PROBE, queues = TEST_DLQ)
        public void onMessage(Message amqpMessage, Channel channel) throws IOException {
            bodies.add(new String(amqpMessage.getBody(), StandardCharsets.UTF_8));
            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
        }
    }

    /** 拒绝消费者：{@code basicNack(requeue=false)} → 触发 broker 的死信路由 */
    static class NackConsumer {
        final AtomicInteger nacked = new AtomicInteger();

        @RabbitListener(id = EP_NACK, queues = TEST_QUEUE)
        public void onMessage(Message amqpMessage, Channel channel) throws IOException {
            nacked.incrementAndGet();
            channel.basicNack(amqpMessage.getMessageProperties().getDeliveryTag(), false, false);
        }
    }

    // ==================== 依赖 ====================

    @Resource
    private RabbitmqProducer rabbitmqProducer;

    @Resource
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Resource
    private AmqpAdmin amqpAdmin;

    @Resource
    private DataSource dataSource;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Resource
    private TestConsumer testConsumer;

    @Resource
    private DlqProbe dlqProbe;

    @Resource
    private NackConsumer nackConsumer;

    @Value("${spring.rabbitmq.host:192.168.100.128}")
    private String mqHost;

    @Value("${spring.rabbitmq.username:admin}")
    private String mqUser;

    @Value("${spring.rabbitmq.password:}")
    private String mqPassword;

    private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 本用例创建过的 messageId，供 {@link #tearDown} 自清理。
     *
     * <p><b>为什么必须自清理（本次踩坑）</b>：本类用的是高位 messageId（{@code 9.1e15} 段），
     * 而 {@code NotificationProcessIdempotentTest} 的 {@code @BeforeEach} 会执行
     * {@code DELETE FROM t_notification WHERE message_id > 9e15} —— 它的清理区间
     * <b>无上界地覆盖了本类的 ID 段</b>。当前靠「类名排序 mq 在 notification 之前」侥幸无害，
     * 但依赖别的测试类的清理范围是坏习惯：一旦执行顺序变化，本类的落库断言就会莫名其妙地失败。
     * 自己写的行自己删，才不依赖外部执行顺序。</p>
     */
    private final java.util.List<Long> createdMessageIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        // ⚠️ 防空转护栏：若注入的生产者仍是 Mockito mock，整类测试会退化为「断言 mock 什么都没做」——
        //    这正是本项目历史上踩过的「假绿」陷阱（BaseApiTest 的 @MockBean 覆盖失败时不会报错，只会静默变绿）
        assertThat(mockingDetails(rabbitmqProducer).isMock())
                .as("注入的 RabbitmqProducer 必须是真实实例；若是 mock，本类全部断言都失去意义")
                .isFalse();

        jdbc = new JdbcTemplate(dataSource);
        // 测试拓扑由 Spring Boot 的 RabbitAdmin 在连接建立时自动声明；这里主动触发一次，
        // 保证「用例开始前队列确实存在」，避免首条消息与声明动作抢跑
        ((org.springframework.amqp.rabbit.core.RabbitAdmin) amqpAdmin).initialize();
        resetProbes();
        purgeTestQueues();
    }

    /**
     * 重置探针状态。
     *
     * <p><b>为什么必需（本次踩坑）</b>：消费者/探针是 Spring 单例 bean，其内部
     * {@code BlockingQueue} 与计数器会<b>跨用例存活</b>。若不重置，{@code poll()} 会把
     * <b>上一个用例捕获的旧消息</b>当成本次的返回，断言随即拿到别人的 messageId
     * （表现为「expected 9100000078759700 but was 9100000579520100」这类莫名其妙的比对失败）；
     * 计数器同理会把 {@code >= 2} 之类的断言提前满足成假绿。</p>
     */
    private void resetProbes() {
        testConsumer.rawMessages.clear();
        testConsumer.invoked.set(0);
        dlqProbe.bodies.clear();
        nackConsumer.nacked.set(0);
    }

    @AfterEach
    void tearDown() {
        stopAllTestEndpoints();
        purgeTestQueues();
        cleanNotificationRows();
    }

    /** 删掉本类写进 t_notification / t_notification_retry 的行（自己造的数据自己清） */
    private void cleanNotificationRows() {
        for (Long messageId : createdMessageIds) {
            jdbc.update("DELETE FROM t_notification WHERE message_id = ?", messageId);
            jdbc.update("DELETE FROM t_notification_retry WHERE message_id = ?", messageId);
        }
        createdMessageIds.clear();
    }

    private void purgeTestQueues() {
        amqpAdmin.purgeQueue(TEST_QUEUE);
        amqpAdmin.purgeQueue(TEST_DLQ);
    }

    private void stopAllTestEndpoints() {
        for (String id : List.of(EP_CONSUMER, EP_NACK, EP_DLQ_PROBE)) {
            MessageListenerContainer c = listenerRegistry.getListenerContainer(id);
            if (c != null && c.isRunning()) {
                c.stop();
            }
        }
    }

    private MessageListenerContainer startEndpoint(String id) {
        MessageListenerContainer c = listenerRegistry.getListenerContainer(id);
        assertThat(c).as("测试端点 %s 必须已注册到 RabbitListenerEndpointRegistry", id).isNotNull();
        c.start();
        // start() 是异步的：容器要建 channel、注册 consumer 到 broker 才算真正就绪
        waitUntil(() -> c.isRunning(), 5_000, "端点 " + id + " 未能进入运行态");
        return c;
    }

    // ==================== ① 发布确认 ====================

    @Test
    @DisplayName("真实 broker · 发布确认：可路由消息拿到 ack=true 回执（correlated confirm 真的生效）")
    void publishConfirm_routableMessage_ackedByBroker() throws Exception {
        NotificationMessage msg = newMsg();

        AtomicBoolean ack = new AtomicBoolean(false);
        AtomicReference<String> reason = new AtomicReference<>("<未回调>");
        CountDownLatch latch = new CountDownLatch(1);

        rabbitmqProducer.sendAfterCommit(TEST_EXCHANGE, TEST_ROUTING_KEY, msg, msg.getMessageId(),
                (ok, r) -> {
                    ack.set(ok);
                    reason.set(r);
                    latch.countDown();
                });

        assertThat(latch.await(10, TimeUnit.SECONDS))
                .as("确认回调必须在超时前触发——不触发说明 publisher-confirm-type 没生效，"
                        + "或 CorrelationData 没被 broker 关联回来")
                .isTrue();
        assertThat(ack.get())
                .as("broker 收到并接管消息后必须回 ack=true；此处为 false(reason=%s) 说明投递失败", reason.get())
                .isTrue();
    }

    // ==================== ② 全链路：投递 → 消费 → 落库 → ack ====================

    @Test
    @DisplayName("真实 broker · 全链路：publish → 容器 → 真实消费者 → 幂等落库 → basicAck → 队列清空")
    void endToEnd_publishConsumePersistAndAck() throws Exception {
        // 被测对象就是生产消费者 NotificationMqConsumer，仅在队列维度隔离
        startEndpoint(EP_CONSUMER);

        NotificationMessage msg = newMsg();
        try {
            rabbitmqProducer.sendAfterCommit(TEST_EXCHANGE, TEST_ROUTING_KEY, msg, msg.getMessageId(), null);

            // 终点条件①：真实消费者被调用
            waitUntil(() -> testConsumer.invoked.get() >= 1, 10_000,
                    "真实消费者方法未被调用——容器/转换器/队列绑定任一环没通");

            // 终点条件②：站内信落库（证明 NotificationProcessor 幂等落库链路真的跑通）
            waitUntil(() -> notificationRows(msg.getMessageId()) == 1, 10_000,
                    "t_notification 未落库，说明消费者到处理器的链路断了");

            // 终点条件③：队列清空（只统计「已就绪」消息；basicAck 之后消息才真正从队列消失）
            waitUntil(() -> readyMessages(TEST_QUEUE) == 0, 10_000,
                    "消费完成后队列必须为空——若仍滞留说明 basicAck 没生效（MANUAL ack 模式下漏 ack 会一直占位）");

            assertThat(queryContent(msg.getMessageId()))
                    .as("落库内容必须与协议对象一致，证明 JSON 转换器双向都对")
                    .isEqualTo(msg.getContent());
        } finally {
            stopAllTestEndpoints();
        }
    }

    @Test
    @DisplayName("真实 broker · 幂等：同一 messageId 投递两次，只落库一条（唯一索引 + 预检双保险）")
    void duplicateDelivery_persistsExactlyOnce() throws Exception {
        startEndpoint(EP_CONSUMER);

        NotificationMessage msg = newMsg();
        try {
            rabbitmqProducer.sendAfterCommit(TEST_EXCHANGE, TEST_ROUTING_KEY, msg, msg.getMessageId(), null);
            waitUntil(() -> notificationRows(msg.getMessageId()) == 1, 10_000, "首投未落库");

            // 同一条消息再投一次（模拟 broker 重投 / 补偿 Job 重发）
            rabbitmqProducer.sendAfterCommit(TEST_EXCHANGE, TEST_ROUTING_KEY, msg, msg.getMessageId(), null);
            waitUntil(() -> testConsumer.invoked.get() >= 2, 10_000, "第二次投递未被消费");

            // 给重复分支一点时间落定后再断言「仍然只有一条」
            Thread.sleep(300);
            assertThat(notificationRows(msg.getMessageId()))
                    .as("同一 messageId 重复消费必须被幂等拦住（预检 exists + t_notification.message_id 唯一索引兜底）")
                    .isEqualTo(1);
        } finally {
            stopAllTestEndpoints();
        }
    }

    // ==================== ③ 线级属性 ====================

    @Test
    @DisplayName("真实 broker · 消息属性：deliveryMode=PERSISTENT 且 messageId 透传（重试表/幂等的关联依据）")
    void messageProperties_arePersistentAndCarryMessageId() throws Exception {
        startEndpoint(EP_CONSUMER);
        try {
            NotificationMessage msg = newMsg();
            rabbitmqProducer.sendAfterCommit(TEST_EXCHANGE, TEST_ROUTING_KEY, msg, msg.getMessageId(), null);

            Message raw = testConsumer.rawMessages.poll(10, TimeUnit.SECONDS);
            assertThat(raw).as("未能从真实 broker 取到原始消息").isNotNull();

            // ⚠️ MessageProperties 有【两个】deliveryMode 载体（javap 实测，spring-amqp 3.1.4）：
            //    - getDeliveryMode()          —— 发送侧字段，消费端不填 → 取到 null（本次踩坑）
            //    - getReceivedDeliveryMode()  —— 接收侧字段，broker 传过来的真实值
            //    读错那个会得到「消息没持久化」的假结论，因此两个都取、以接收侧为准。
            MessageDeliveryMode mode = raw.getMessageProperties().getReceivedDeliveryMode() != null
                    ? raw.getMessageProperties().getReceivedDeliveryMode()
                    : raw.getMessageProperties().getDeliveryMode();
            assertThat(mode)
                    .as("必须持久化（deliveryMode=2）——否则 broker 重启会丢掉未消费的消息，"
                            + "而「消息不丢」正是选 RabbitMQ 的前提")
                    .isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(raw.getMessageProperties().getMessageId())
                    .as("messageId 必须原样落到 AMQP 属性的 message_id 上——消费者靠它做幂等关联")
                    .isEqualTo(String.valueOf(msg.getMessageId()));
        } finally {
            stopAllTestEndpoints();
        }
    }

    // ==================== ④ 事务语义：提交后发送 ====================

    @Test
    @DisplayName("真实 broker · sendAfterCommit：事务提交后才发出（提交前 broker 上不应有消息）")
    void sendAfterCommit_sendsOnlyAfterCommit() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        NotificationMessage msg = newMsg();

        tx.execute(status -> {
            rabbitmqProducer.sendAfterCommit(TEST_EXCHANGE, TEST_ROUTING_KEY, msg, msg.getMessageId(), null);
            assertThat(readyMessages(TEST_QUEUE))
                    .as("事务尚未提交，消息不能出现在 broker 上——否则回滚就会留下「假消息」")
                    .isZero();
            return null;
        });

        waitUntil(() -> readyMessages(TEST_QUEUE) >= 1, 10_000,
                "事务提交后消息仍未送达 broker：afterCommit 钩子没生效");
    }

    @Test
    @DisplayName("真实 broker · sendAfterCommit：事务回滚则不发送（回滚后 broker 上必须一条都没有）")
    void sendAfterCommit_rolledBackTransaction_sendsNothing() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.execute(status -> {
            rabbitmqProducer.sendAfterCommit(TEST_EXCHANGE, TEST_ROUTING_KEY, newMsg(), null, null);
            status.setRollbackOnly();
            return null;
        });

        // 没有一个「消息不该出现」的确切时刻可等，只能在合理窗口后确认它确实没出现：
        // 队列起始为空，若 afterCommit 被误当成「立即执行」，最多几十毫秒就该到位
        sleepQuietly(800);
        assertThat(readyMessages(TEST_QUEUE))
                .as("事务回滚后 broker 上不得留下消息——这是「业务没成功就不该发通知」的底线保证")
                .isZero();
    }

    // ==================== ⑤ 死信链路 ====================

    @Test
    @DisplayName("真实 broker · 死信：basicNack(requeue=false) 经 DLX 落入死信队列（人工处理入口可达）")
    void nackWithoutRequeue_routesToDeadLetterQueue() throws Exception {
        // 本用例刻意只启动「拒绝消费者」：若同时启动正常消费者，消息会被 ack 掉而走不到死信
        startEndpoint(EP_NACK);
        startEndpoint(EP_DLQ_PROBE);
        try {
            NotificationMessage msg = newMsg();
            rabbitmqProducer.sendAfterCommit(TEST_EXCHANGE, TEST_ROUTING_KEY, msg, msg.getMessageId(), null);

            waitUntil(() -> nackConsumer.nacked.get() >= 1, 10_000, "拒绝消费者未收到消息");

            String body = dlqProbe.bodies.poll(10, TimeUnit.SECONDS);
            assertThat(body)
                    .as("requeue=false 的 nack 必须经 x-dead-letter-exchange 落到死信队列；"
                            + "若为 null 说明 DLX/DL 路由键没配对，坏消息会被无痕丢弃")
                    .isNotNull();
            assertThat(body)
                    .as("死信里必须是原消息体，人工排障才能看到现场")
                    .contains(String.valueOf(msg.getMessageId()));
        } finally {
            stopAllTestEndpoints();
        }
    }

    // ==================== ⑥ 生产消费者端点接线 ====================

    @Test
    @DisplayName("真实 broker · 生产消费者端点：@RabbitListener 已注册且队列名正是 notification.queue")
    void productionConsumerEndpoint_isRegisteredOnNotificationQueue() {
        boolean found = listenerRegistry.getListenerContainers().stream()
                // getQueueNames() 在 AbstractMessageListenerContainer 上，不在 MessageListenerContainer 接口上
                .anyMatch(c -> c instanceof AbstractMessageListenerContainer c2
                        && List.of(c2.getQueueNames()).contains(RabbitmqConfig.NOTIFICATION_QUEUE));

        assertThat(found)
                .as("容器注册表里必须存在监听 %s 的端点——本类为避开历史积压没有启动它，"
                        + "故用这条断言单独钉住「@RabbitListener 的队列名常量没被改错」",
                        RabbitmqConfig.NOTIFICATION_QUEUE)
                .isTrue();
    }

    // ==================== ⑦ 生产拓扑参数（broker 真值） ====================

    @Test
    @DisplayName("真实 broker · 生产拓扑：notification.queue 的 DLX/TTL/长度上限与 dead 队列 TTL 与声明一致")
    void productionTopologyArgs_matchDeclaredConfig() {
        assumeTrue(managementApiAvailable(),
                "RabbitMQ 管理插件不可达（" + mqHost + ":" + MGMT_PORT + "），跳过 broker 真值断言。"
                        + "注意：队列参数无法经 AMQP 协议读回，管理 API 是唯一的观测手段");

        JsonNode queue = mgmtGet("/api/queues/" + VHOST_PATH + "/" + RabbitmqConfig.NOTIFICATION_QUEUE);
        assertThat(queue).as("notification.queue 必须已在 broker 上声明").isNotNull();
        assertThat(queue.get("durable").asBoolean())
                .as("主队列必须持久化，否则 broker 重启即丢队列定义").isTrue();
        assertThat(queue.at("/arguments/x-dead-letter-exchange").asText())
                .as("死信交换机必须指向 %s", RabbitmqConfig.NOTIFICATION_DEAD_EXCHANGE)
                .isEqualTo(RabbitmqConfig.NOTIFICATION_DEAD_EXCHANGE);
        assertThat(queue.at("/arguments/x-dead-letter-routing-key").asText())
                .as("死信路由键必须与死信交换机上的绑定一致，否则死信会「路由不进队列」而丢失")
                .isEqualTo(RabbitmqConfig.NOTIFICATION_DEAD_ROUTING_KEY);
        assertThat(queue.at("/arguments/x-message-ttl").asLong())
                .as("主队列消息 24h 未消费即过期进死信，防止队列被僵尸消息无限占用")
                .isEqualTo(24L * 60 * 60 * 1000);
        assertThat(queue.at("/arguments/x-max-length").asLong())
                .as("长度上限是过载保护：达到上限后新消息被拒，避免内存被拖垮")
                .isEqualTo(50_000L);

        JsonNode dead = mgmtGet("/api/queues/" + VHOST_PATH + "/" + RabbitmqConfig.NOTIFICATION_DEAD_QUEUE);
        assertThat(dead).as("死信队列必须已声明").isNotNull();
        assertThat(dead.get("durable").asBoolean()).isTrue();
        assertThat(dead.at("/arguments/x-message-ttl").asLong())
                .as("死信 7 天后丢弃——留人工介入窗口，但不无限堆积")
                .isEqualTo(7L * 24 * 60 * 60 * 1000);
    }

    // ==================== ⑧ ⚠️ 可靠性缺口：不可路由消息被静默丢弃 ====================

    @Test
    @DisplayName("真实 broker · ⚠️缺口：不可路由消息仍回 ack=true，随后被静默丢弃（mandatory/returns 未开启）")
    void unroutableMessage_ackTrueButSilentlyDropped() throws Exception {
        NotificationMessage msg = newMsg();
        AtomicBoolean ack = new AtomicBoolean(false);
        AtomicReference<String> reason = new AtomicReference<>("<未回调>");
        CountDownLatch latch = new CountDownLatch(1);

        // 必须带非空 messageId：RabbitmqProducer 仅在 messageId != null 时才创建 CorrelationData
        // 并注册确认回调（无 CorrelationData 就无从关联 broker 回执，回调永不触发）
        rabbitmqProducer.sendAfterCommit(TEST_EXCHANGE, UNROUTABLE_KEY, msg, msg.getMessageId(),
                (ok, r) -> {
                    ack.set(ok);
                    reason.set(r);
                    latch.countDown();
                });

        assertThat(latch.await(10, TimeUnit.SECONDS))
                .as("即使不可路由，`CorrelationData` 的 future 也应收到 broker 回执（本用例正是在钉住这个行为）")
                .isTrue();
        assertThat(ack.get())
                .as("【缺口】broker 对「无法路由」的消息仍回 ack=true（reason=%s）—— 因为 "
                        + "`spring.rabbitmq.publisher-returns` 未开启且 `template.mandatory` 默认 false。"
                        + "后果：路由键写错 / 绑定漏配时，消息既没被投递也没有任何异常，发送方只看到「成功」。"
                        + "修法见待修清单：开启 publisher-returns + mandatory 并注册 ReturnsCallback", reason.get())
                .isTrue();

        sleepQuietly(500);
        assertThat(readyMessages(TEST_QUEUE))
                .as("不可路由的消息确实没有进入任何队列（本测试队列绑的正是另一条路由键）")
                .isZero();
    }

    // ==================== 辅助 ====================

    /** 生成一个唯一 messageId 的通知消息（messageId 取高位区间，避免与真实业务 ID 撞车） */
    private NotificationMessage newMsg() {
        long messageId = 9_100_000_000_000_000L + (System.nanoTime() % 1_000_000_000L);
        createdMessageIds.add(messageId);
        return NotificationMessage.builder()
                .messageId(messageId)
                .userId(9_100_000_000_000_001L)
                .type(NotificationType.GOODS_ORDER)
                .content("MQ 可靠性测试通知")
                .refId(9_100_000_000_000_002L)
                .build();
    }

    private int notificationRows(Long messageId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE message_id = ?", Integer.class, messageId);
        return n == null ? 0 : n;
    }

    private String queryContent(Long messageId) {
        return jdbc.queryForObject(
                "SELECT content FROM t_notification WHERE message_id = ?", String.class, messageId);
    }

    /**
     * 队列中「已就绪」消息数（走 AMQP 的 {@code queueDeclarePassive}，不经管理 API）。
     * 只统计 ready，不含 unacked —— 因此它正好能反映「消息是否已被 ack 掉」。
     */
    private int readyMessages(String queue) {
        var info = amqpAdmin.getQueueInfo(queue);
        // QueueInformation.getMessageCount() 返回 int（AMQP queueDeclarePassive 的 messageCount）
        return info == null ? 0 : info.getMessageCount();
    }

    private void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs, String message) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepQuietly(50);
        }
        throw new AssertionError("等待超时（" + timeoutMs + "ms）：" + message);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- RabbitMQ 管理 API（仅用于读取 AMQP 协议读不到的队列参数） ----------

    private boolean managementApiAvailable() {
        try {
            return mgmtGet("/api/overview") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode mgmtGet(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(mqUser, mqPassword);
        // ⚠️ 必须用 URI.create 传「已编码好的 URI」：管理 API 的默认 vhost 路径段是 %2F，
        //    若走 RestTemplate 的 String-url 重载，默认 UriTemplateHandler 会把 '%' 再编码一次
        //    （%2F → %252F），broker 侧就查不到这个 vhost，直接 404。
        java.net.URI uri = java.net.URI.create("http://" + mqHost + ":" + MGMT_PORT + path);
        ResponseEntity<String> resp = new RestTemplate().exchange(
                uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        try {
            return json.readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("管理 API 响应不是合法 JSON：" + resp.getBody(), e);
        }
    }
}
