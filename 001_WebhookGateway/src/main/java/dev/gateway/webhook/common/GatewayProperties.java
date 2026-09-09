package dev.gateway.webhook.common;

import dev.gateway.webhook.dispatch.JitterStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private final Admin admin = new Admin();
    private final Receive receive = new Receive();
    private final Dispatch dispatch = new Dispatch();
    private final Zombie zombie = new Zombie();

    public Admin getAdmin() { return admin; }
    public Receive getReceive() { return receive; }
    public Dispatch getDispatch() { return dispatch; }
    public Zombie getZombie() { return zombie; }

    public static class Admin {
        /** 비어 있으면 관리 API 인증을 걸지 않는다. */
        private String token = "";

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class Receive {
        private int maxBodyBytes = 1024 * 1024;
        @DurationUnit(ChronoUnit.MINUTES)
        private Duration timestampTolerance = Duration.ofMinutes(5);
        @DurationUnit(ChronoUnit.HOURS)
        private Duration secretRotationGrace = Duration.ofHours(24);

        public int getMaxBodyBytes() { return maxBodyBytes; }
        public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
        public Duration getTimestampTolerance() { return timestampTolerance; }
        public void setTimestampTolerance(Duration timestampTolerance) { this.timestampTolerance = timestampTolerance; }
        public Duration getSecretRotationGrace() { return secretRotationGrace; }
        public void setSecretRotationGrace(Duration secretRotationGrace) { this.secretRotationGrace = secretRotationGrace; }
    }

    public static class Dispatch {
        private boolean enabled = true;
        private Duration pollInterval = Duration.ofMillis(500);
        private int batchSize = 50;
        private int workerThreads = 8;
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration connectTimeout = Duration.ofSeconds(3);
        private final Backoff backoff = new Backoff();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
        public Duration getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Backoff getBackoff() { return backoff; }
    }

    public static class Backoff {
        private Duration initial = Duration.ofSeconds(1);
        private Duration max = Duration.ofHours(1);
        private JitterStrategy jitter = JitterStrategy.FULL;

        public Duration getInitial() { return initial; }
        public void setInitial(Duration initial) { this.initial = initial; }
        public Duration getMax() { return max; }
        public void setMax(Duration max) { this.max = max; }
        public JitterStrategy getJitter() { return jitter; }
        public void setJitter(JitterStrategy jitter) { this.jitter = jitter; }
    }

    public static class Zombie {
        private Duration interval = Duration.ofMinutes(1);
        private Duration reclaimAfter = Duration.ofMinutes(5);

        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }
        public Duration getReclaimAfter() { return reclaimAfter; }
        public void setReclaimAfter(Duration reclaimAfter) { this.reclaimAfter = reclaimAfter; }
    }
}
