package com.gacfox.proarc.agentic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * LLM专用HttpClient连接池配置
 */
@Data
@ConfigurationProperties(prefix = "proarc.agentic.http")
public class LlmHttpClientProperties {
    /**
     * 最大连接数
     */
    private int maxConnections = 500;

    /**
     * 获取连接最大等待时间
     */
    private Duration pendingAcquireTimeout = Duration.ofSeconds(30);

    /**
     * 最大等待获取连接数，-1表示无限制
     */
    private int pendingAcquireMaxCount = -1;

    /**
     * 连接最大空闲时间
     */
    private Duration maxIdleTime = Duration.ofSeconds(20);

    /**
     * 连接最大存活时间
     */
    private Duration maxLifeTime = Duration.ofMinutes(5);

    /**
     * 连接超时
     */
    private Duration connectTimeout = Duration.ofSeconds(15);

    /**
     * 响应超时
     */
    private Duration responseTimeout = Duration.ofSeconds(180);

    /**
     * 读超时
     */
    private Duration readTimeout = Duration.ofSeconds(180);

    /**
     * 写超时
     */
    private Duration writeTimeout = Duration.ofSeconds(180);
}
