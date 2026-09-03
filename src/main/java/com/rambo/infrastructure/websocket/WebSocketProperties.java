package com.rambo.infrastructure.websocket;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * WebSocket 配置（Origin 白名单 + STOMP 外部 broker relay 参数）。
 * <p>
 * 配置前缀 {@code app.websocket}，从 application.yml 注入；
 * 改 origin 白名单或 broker 地址无需改代码、无需重新发布。
 * </p>
 *
 * <p><b>字段语义：</b>
 * <ul>
 *   <li><b>allowedOrigins</b>：允许建连的来源（CSWSH 防护）。dev 给 {@code *} 方便本地多端口调试；
 *       prod 必须配置为前端真实域名列表（如 {@code https://campus.xxx.com}），空列表 = 拒绝所有跨域建连</li>
 *   <li><b>relayHost/relayPort</b>：STOMP 外部 broker 地址（RabbitMQ STOMP 插件默认 61613），
 *       切换为外部 broker 后消息可跨实例路由</li>
 *   <li><b>clientLogin/clientPasscode</b>：应用作为 STOMP 客户端连 broker 的账号</li>
 *   <li><b>systemLogin/systemPasscode</b>：broker 用于「系统消息」（如心跳、连接/订阅事件），
 *       必须与 client 不同的 RabbitMQ 用户以区分业务与控制平面</li>
 * </ul>
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "app.websocket")
public class WebSocketProperties {

    /**
     * 消息代理类型：{@code simple} = 单实例内存 broker；{@code relay} = 外部 STOMP broker（RabbitMQ）。
     * <p>多实例部署必须 relay（内存 broker 的用户订阅不跨实例共享）；
     * 测试/单机环境用 simple 可避免依赖 RabbitMQ STOMP 插件与 Reactor Netty。</p>
     */
    private String brokerType = "relay";

    /**
     * 允许建连的来源列表。dev 用 {@code *}；prod 必须显式列出前端域名。
     */
    private List<String> allowedOrigins = new ArrayList<>();

    /**
     * STOMP 外部 broker 主机（启用 RabbitMQ STOMP 插件后的连接入口）
     */
    private String relayHost;

    /**
     * STOMP 外部 broker 端口（RabbitMQ STOMP 插件默认 61613）
     */
    private Integer relayPort;

    /**
     * 应用作为 STOMP 客户端连 broker 的用户名
     */
    private String clientLogin;

    /**
     * 应用作为 STOMP 客户端连 broker 的密码
     */
    private String clientPasscode;

    /**
     * broker 内部系统用户（用于发送心跳/订阅事件等系统消息）
     */
    private String systemLogin;

    /**
     * broker 内部系统用户密码
     */
    private String systemPasscode;
}
