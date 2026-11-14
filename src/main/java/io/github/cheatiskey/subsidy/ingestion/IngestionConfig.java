package io.github.cheatiskey.subsidy.ingestion;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(Gov24Properties.class)
class IngestionConfig {
}
