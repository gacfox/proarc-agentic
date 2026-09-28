package com.gacfox.proarc.agentic.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import javax.net.ssl.SSLException;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(LlmHttpClientProperties.class)
public class LlmHttpClientAutoConfiguration {
    @Bean("llmHttpClient")
    @ConditionalOnMissingBean(name = "llmHttpClient")
    public HttpClient llmHttpClient(LlmHttpClientProperties props) {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("proarc-agentic-pool")
                .maxConnections(props.getMaxConnections())
                .pendingAcquireTimeout(props.getPendingAcquireTimeout())
                .pendingAcquireMaxCount(props.getPendingAcquireMaxCount())
                .maxIdleTime(props.getMaxIdleTime())
                .maxLifeTime(props.getMaxLifeTime())
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) props.getConnectTimeout().toMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .responseTimeout(props.getResponseTimeout())
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(props.getReadTimeout().toSeconds(), TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(props.getWriteTimeout().toSeconds(), TimeUnit.SECONDS))
                );

        return configureSsl(httpClient);
    }

    private HttpClient configureSsl(HttpClient httpClient) {
        return httpClient.secure(sslContextSpec -> {
            try {
                sslContextSpec.sslContext(
                        SslContextBuilder
                                .forClient()
                                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                .build()
                );
            } catch (SSLException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
