package zcslib.network;

import zcslib.log.ZCSLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodic health-check probe to the aggregator.
 * <p>
 * Sends HTTP HEAD to the aggregator base URL at a configurable interval.
 * On state transition (offline→online), triggers the offline queue drain.
 * <p>
 * <b>Thread model:</b> Single-threaded scheduled executor, non-blocking.
 */
public class AggregatorHealthCheck {

    private static final ZCSLogger LOG = ZCSLogger.forKernel("health-check");
    private static final int DEFAULT_INTERVAL_SEC = 30;
    private static final int PROBE_TIMEOUT_SEC = 5;

    private final ZCSNetwork network;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean online = new AtomicBoolean(false);

    private int intervalSec = DEFAULT_INTERVAL_SEC;

    AggregatorHealthCheck(ZCSNetwork network) {
        this.network = network;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(PROBE_TIMEOUT_SEC))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ZCSLIB-HealthCheck");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start periodic probing.
     */
    public void start() {
        LOG.info("Health check started — probing every {}s", intervalSec);
        scheduler.scheduleAtFixedRate(this::probe, 0, intervalSec, TimeUnit.SECONDS);
    }

    /**
     * Stop probing.
     */
    public void stop() {
        scheduler.shutdown();
        LOG.info("Health check stopped");
    }

    /**
     * Set probe interval.
     */
    public void setInterval(int seconds) {
        this.intervalSec = Math.max(5, seconds); // minimum 5s
    }

    /**
     * Check if aggregator is currently reachable.
     */
    public boolean isOnline() {
        return online.get();
    }

    // —— Probe ——

    private void probe() {
        String url = network.getAggregatorUrl();
        if (url == null || url.isEmpty()) return;

        boolean wasOnline = online.get();

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/packet"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SEC))
                    .build();

            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            boolean nowOnline = resp.statusCode() >= 200 && resp.statusCode() < 500;
            online.set(nowOnline);

            if (!wasOnline && nowOnline) {
                LOG.info("Aggregator ONLINE — triggering offline queue drain");
                network.getOfflineQueue().drain();
            }

        } catch (Exception e) {
            online.set(false);
            if (wasOnline) {
                LOG.warn("Aggregator OFFLINE — {}", e.getMessage());
            }
            // On transition to offline, log once; subsequent failures are silent
        }
    }
}
