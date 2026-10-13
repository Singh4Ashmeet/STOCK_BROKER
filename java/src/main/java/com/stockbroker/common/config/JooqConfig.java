package com.stockbroker.common.config;

import org.jooq.conf.RenderNameStyle;
import org.jooq.conf.Settings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * jOOQ configuration.
 */
@Configuration
public class JooqConfig {

    @Bean
    @SuppressWarnings("deprecation")
    public Settings jooqSettings() {
        return new Settings()
                .withRenderNameStyle(RenderNameStyle.AS_IS)
                .withExecuteLogging(false)
                .withRenderSchema(false);
    }
}