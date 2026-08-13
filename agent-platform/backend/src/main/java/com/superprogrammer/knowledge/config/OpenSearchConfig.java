package com.superprogrammer.knowledge.config;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "rag.opensearch", name = "enabled", havingValue = "true")
public class OpenSearchConfig {

    @Bean(destroyMethod = "close")
    RestClient openSearchRestClient(OpenSearchProperties properties) {
        BasicCredentialsProvider credentials = new BasicCredentialsProvider();
        if (hasText(properties.getUsername())) {
            credentials.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(properties.getUsername(), properties.getPassword()));
        }
        return RestClient.builder(HttpHost.create(properties.getUrl()))
                .setRequestConfigCallback(builder -> builder
                        .setConnectTimeout((int) properties.getConnectTimeout().toMillis())
                        .setSocketTimeout((int) properties.getRequestTimeout().toMillis()))
                .setHttpClientConfigCallback(builder -> hasText(properties.getUsername())
                        ? builder.setDefaultCredentialsProvider(credentials) : builder)
                .build();
    }

    @Bean(destroyMethod = "close")
    OpenSearchTransport openSearchTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    OpenSearchClient openSearchClient(OpenSearchTransport transport) {
        return new OpenSearchClient(transport);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
