package de.zalando.zmon.scheduler.ng.alerts;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import de.zalando.zmon.scheduler.ng.TokenWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;

/**
 * Created by jmussler on 4/7/15.
 */
public class DefaultAlertSource extends AlertSource {

    private final Timer timer;

    private final String url;
    private final TokenWrapper tokens;
    private final ClientHttpRequestFactory clientFactory;
    private boolean isFirstLoad = true;

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAlertSource.class);

    private static final ObjectMapper mapper = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.setPropertyNamingStrategy(PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES);
        return m;
    }

    @Autowired
    public DefaultAlertSource(final String name, final String url, final MetricRegistry metrics, final TokenWrapper tokens, final ClientHttpRequestFactory clientFactory) {
        super(name);
        this.clientFactory = clientFactory;
        LOG.info("configuring alert source url={}", url);
        this.url = url;
        this.tokens = tokens;
        this.timer = metrics.timer("alert-adapter." + name);
    }

    private HttpHeaders getAuthenticationHeader() {

        final HttpHeaders headers = new HttpHeaders();

        if (tokens != null) {
            headers.add("Authorization", "Bearer " + tokens.get());
        }

        return headers;
    }

    @Override
    public Collection<AlertDefinition> getCollection() {
        RestTemplate rt = new RestTemplate(clientFactory);

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(mapper);
        rt.getMessageConverters().clear();
        rt.getMessageConverters().add(converter);

        AlertDefinitions defs = new AlertDefinitions();
        try {
            final String accessToken = tokens.get();
            LOG.info("Querying alert definitions with token " + accessToken.substring(0, Math.min(accessToken.length(), 3)) + "..");
            final HttpEntity<String> request = new HttpEntity<>(getAuthenticationHeader());
            ResponseEntity<AlertDefinitions> response;
            Timer.Context ct = timer.time();
            response = rt.exchange(url, HttpMethod.GET, request, AlertDefinitions.class);
            ct.stop();
            defs = response.getBody();

            LOG.info("Got {} alerts from {}", defs.getAlertDefinitions().size(), getName());
            isFirstLoad = false;
        } catch (Throwable t) {
            LOG.error("Failed to get alert definitions: {}", t.getMessage());
            if (!isFirstLoad) {
                // rethrow so that currently alerts are still used not not replaced by empty list
                throw t;
            }
        }

        return defs.getAlertDefinitions();
    }
}
