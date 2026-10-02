package ca.northline.shared.crypto;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** S-115: the job that re-wraps what an older key sealed, over the {@link SealedColumn}s the modules declare. */
@Configuration(proxyBeanMethods = false)
class KeyRewrapConfiguration {

    @Bean
    KeyRewrap keyRewrap(
            SecretSealer sealer,
            JdbcClient jdbc,
            List<SealedColumn> columns,
            MeterRegistry meters,
            @Value("${northline.crypto.rewrap-batch:200}") int batch) {
        return new KeyRewrap(sealer, jdbc, columns, meters, batch);
    }
}
