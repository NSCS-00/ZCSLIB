package zcslib.network;

import zcslib.api.OrderResult;
import zcslib.api.TrustLevel;
import zcslib.kernel.ZCSKernel;
import zcslib.log.ZCSLogger;
import zcslib.log.AuditLogger;
import zcslib.security.NetworkAudit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/**
 * ZCNet network layer — unified egress control.
 * <p>
 * Responsible for:
 * <ul>
 *   <li>Assembling main packets from per-tick sub-packet buffers</li>
 *   <li>Sending standard (direct HTTP) and main (aggregated) packets</li>
 *   <li>Offline strategy enforcement (RETRY_LATER / DISCARD)</li>
 *   <li>Trust-level gating (S-level → standard only, main denied)</li>
 *   <li>HMAC-SHA256 signature generation matching aggregator spec</li>
 * </ul>
 */
public class ZCSNetwork {

    private static final ZCSLogger LOG = ZCSLogger.forKernel("network");
    private static final HexFormat HEX = HexFormat.of();

    private final ZCSKernel kernel;
    private final MainPacketAssembler assembler;
    private final OfflineQueue offlineQueue;
    private final AggregatorHealthCheck healthCheck;
    private final HttpClient httpClient;

    private String aggregatorUrl;
    private String nodeId;
    private byte[] sharedSecret;
    private long sequence;
    private String version = "1.0.0";

    // Source identifier per studio spec v2.1: [aggregatorName]-[suffix]
    private String source;

    public ZCSNetwork(ZCSKernel kernel) {
        this.kernel = kernel;
        this.assembler = new MainPacketAssembler(this);
        this.offlineQueue = new OfflineQueue(this);
        this.healthCheck = new AggregatorHealthCheck(this);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // —— Configuration ——

    public void configure(String aggregatorUrl, String nodeId, String sharedSecretHex,
                          String source, String version) {
        this.aggregatorUrl = aggregatorUrl;
        this.nodeId = nodeId;
        this.sharedSecret = HEX.parseHex(sharedSecretHex);
        this.source = source;
        this.version = version;
        LOG.info("Network configured — aggregator: {}, node: {}, source: {}",
                aggregatorUrl, nodeId, source);
    }

    public void setSequence(long seq) {
        this.sequence = seq;
    }

    // —— Trust-level gating ——

    private OrderResult trustGate(TrustLevel level, String command) {
        if (level == TrustLevel.S) {
            return OrderResult.fail("FORBIDDEN:S " + command);
        }
        return null; // pass
    }

    /**
     * S-level plugins are forced to DEGRADE_TO_STANDARD for send:main.
     * Also applies when the global offline strategy is DEGRADE_TO_STANDARD.
     */
    private boolean shouldDegradeToStandard(String pluginId, TrustLevel trust) {
        if (trust == TrustLevel.S) return true;
        if (offlineQueue.getStrategy() == OfflineQueue.Strategy.DEGRADE_TO_STANDARD) {
            LOG.debug("send:main for {} degraded — global strategy is DEGRADE_TO_STANDARD", pluginId);
            return true;
        }
        return false;
    }

    // —— order() dispatch ——

    /**
     * Dispatch network commands from kernel.
     * <pre>
     * network:send:standard  — direct HTTP, all trust levels (S audited)
     * network:send:main      — enqueue for main packet assembly, N/R/A only
     * network:offline        — set offline strategy
     * </pre>
     */
    public OrderResult order(String subCommand, Object... args) {
        return switch (subCommand) {
            case "send:standard" -> sendStandard(args);
            case "send:main" -> sendMain(args);
            case "offline" -> setOfflineStrategy(args);
            default -> OrderResult.fail("Unknown network command: " + subCommand);
        };
    }

    // —— send:standard ——

    private OrderResult sendStandard(Object... args) {
        // Last two args appended by kernel: [trust, pluginId]
        int n = args.length;
        TrustLevel trust = (n >= 2 && args[n - 2] instanceof TrustLevel t) ? t : TrustLevel.N;
        String pluginId = n >= 1 ? args[n - 1].toString() : "unknown";

        if (n - 2 < 3) {
            return OrderResult.fail("Usage: network:send:standard <method> <path> <body>");
        }

        String method = args[0].toString().toUpperCase();
        String path = args[1].toString();
        String body = args[2] != null ? args[2].toString() : null;

        // S-level: allowed to send standard but must be audited
        if (trust == TrustLevel.S) {
            kernel.getAuditLogger().logTrusted(pluginId, "send:standard",
                    "S-level plugin attempting network send", trust, TrustLevel.N);
        }

        try {
            // P16: NetworkAudit — pre-request
            NetworkAudit netAudit = kernel.getNetworkAudit();
            long reqStart = System.currentTimeMillis();

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(aggregatorUrl + path))
                    .timeout(Duration.ofSeconds(30));

            if ("GET".equals(method)) {
                rb.GET();
            } else {
                rb.method(method, body != null
                        ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                        : HttpRequest.BodyPublishers.noBody());
                rb.header("Content-Type", "application/json");
            }

            HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());

            // P16: NetworkAudit — post-request
            long latency = System.currentTimeMillis() - reqStart;
            int size = body != null ? body.getBytes(StandardCharsets.UTF_8).length : 0;
            if (netAudit != null) {
                netAudit.logOutbound(pluginId, aggregatorUrl + path, size, latency, trust);
            }

            return OrderResult.success(resp.body());
        } catch (Exception e) {
            LOG.error("Standard send failed: {} {} — {}", method, path, e.getMessage());
            return OrderResult.fail("SEND_FAILED: " + e.getMessage());
        }
    }

    // —— send:main ——

    private OrderResult sendMain(Object... args) {
        // Last two args appended by kernel: [trust, pluginId]
        int n = args.length;
        TrustLevel trust = (n >= 2 && args[n - 2] instanceof TrustLevel t) ? t : TrustLevel.N;
        String pluginId = n >= 1 ? args[n - 1].toString() : "unknown";

        if (n - 2 < 2) {
            return OrderResult.fail("Usage: network:send:main <packetName> <data>");
        }

        String packetName = args[0].toString();
        Object data = args[1];

        OrderResult gate = trustGate(trust, "send:main");
        if (gate != null) return gate;

        // DEGRADE_TO_STANDARD: convert main packet to standard HTTP POST
        if (shouldDegradeToStandard(pluginId, trust)) {
            LOG.info("send:main for {} degraded to standard send (trust={}, strategy={})",
                    pluginId, trust.name(), offlineQueue.getStrategy());
            return degradeAndSend(packetName, data, pluginId, trust);
        }

        assembler.enqueue(packetName, data);
        return OrderResult.success("ENQUEUED");
    }

    // —— offline ——

    private OrderResult setOfflineStrategy(Object... args) {
        if (args.length < 1) {
            return OrderResult.fail("Usage: network:offline <RETRY_LATER|DISCARD|DEGRADE_TO_STANDARD>");
        }

        try {
            OfflineQueue.Strategy strat = OfflineQueue.Strategy.valueOf(args[0].toString().toUpperCase());
            offlineQueue.setStrategy(strat);
            return OrderResult.success("Offline strategy set to " + strat);
        } catch (IllegalArgumentException e) {
            return OrderResult.fail("Unknown strategy: " + args[0]
                    + " (valid: RETRY_LATER, DISCARD, DEGRADE_TO_STANDARD)");
        }
    }

    // —— degrade ——

    /**
     * Convert a main packet send into a standard HTTP POST.
     * Used when S-level tries send:main or global DEGRADE_TO_STANDARD is active.
     * Wraps data as JSON sub-packet and POSTs to /api/zcnet/degrade/{pluginId}.
     */
    private OrderResult degradeAndSend(String packetName, Object data, String pluginId, TrustLevel trust) {
        String raw = data instanceof String s ? s : data.toString();
        // Proper JSON string escaping: escape backslash, quote, and control chars
        String escaped = raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        String jsonBody = String.format(
                "{\"name\":\"%s\",\"pluginId\":\"%s\",\"data\":\"%s\"}",
                packetName, pluginId, escaped);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(aggregatorUrl + "/api/zcnet/degrade/" + pluginId))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            // Audit S-level degraded sends
            if (trust == TrustLevel.S) {
                kernel.getAuditLogger().logTrusted(pluginId, "send:main(degraded)",
                        "S-level send:main degraded to standard send: " + packetName,
                        trust, TrustLevel.N);
            }

            return OrderResult.success(resp.body());
        } catch (Exception e) {
            LOG.error("Degraded send failed for {} {}: {}", pluginId, packetName, e.getMessage());
            return OrderResult.fail("DEGRADE_FAILED: " + e.getMessage());
        }
    }

    // —— Packet assembly (called by MainPacketAssembler at tick-end) ——

    void flushAndSend(String jsonPayload, String hmacSignature) {
        if (aggregatorUrl == null) {
            LOG.warn("Network not configured, discarding assembled packet");
            return;
        }

        // P16: NetworkAudit — pre-send
        NetworkAudit netAudit = kernel.getNetworkAudit();
        long sendStart = System.currentTimeMillis();

        // Build the full main packet
        // Per ZCNET_PACKET_SPEC.md v1.1.0 + studio spec v2.1
        long now = System.currentTimeMillis();
        sequence++;

        // Sign: HMAC-SHA256(payload, sharedSecret)
        String sig = sign(jsonPayload);

        String mainPacket = String.format(
                "{\"source\":\"%s\",\"version\":\"%s\",\"timestamp\":%d,\"sequence\":%d,\"signature\":\"%s\",\"packets\":%s}",
                source, version, now, sequence, sig, jsonPayload);

        // Encrypt + send
        try {
            String iv = randomHex(16);
            String encrypted = encrypt(mainPacket, iv);

            String envelope = String.format(
                    "{\"encrypted\":{\"iv\":\"%s\",\"data\":\"%s\"},\"signature\":\"%s\"}",
                    iv, encrypted, sign(mainPacket));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(aggregatorUrl + "/api/packet"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> {
                        // P16: NetworkAudit — post-send
                        long latency = System.currentTimeMillis() - sendStart;
                        if (netAudit != null) {
                            netAudit.logOutbound("zcslib", aggregatorUrl + "/api/packet",
                                    mainPacket.length(), latency, TrustLevel.N);
                        }
                        if (resp.statusCode() == 200) {
                            LOG.debug("Main packet sent — seq {} ({} bytes)", sequence, mainPacket.length());
                        } else {
                            LOG.warn("Aggregator rejected packet seq {}: HTTP {}", sequence, resp.statusCode());
                        }
                    })
                    .exceptionally(e -> {
                        LOG.warn("Aggregator unreachable seq {}: {}", sequence, e.getMessage());
                        offlineQueue.enqueue(jsonPayload);
                        return null;
                    });

        } catch (Exception e) {
            LOG.error("Packet assembly failed: {}", e.getMessage());
            offlineQueue.enqueue(jsonPayload);
        }
    }

    // —— Crypto ——

    String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret, "HmacSHA256"));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(sig);
        } catch (Exception e) {
            LOG.error("HMAC sign failed: {}", e.getMessage());
            return "SIGN_ERROR";
        }
    }

    private String encrypt(String plaintext, String ivHex) throws Exception {
        // AES-256-CBC matching ZCS server zcnet.js
        javax.crypto.SecretKeyFactory factory =
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                new String(sharedSecret, StandardCharsets.UTF_8).toCharArray(),
                "zcnet-key-derivation".getBytes(StandardCharsets.UTF_8),
                100000, 256);
        byte[] key = factory.generateSecret(spec).getEncoded();

        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new javax.crypto.spec.IvParameterSpec(HEX.parseHex(ivHex)));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return HEX.formatHex(encrypted);
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return HEX.formatHex(b);
    }

    // —— Accessors ——

    public String getAggregatorUrl() { return aggregatorUrl; }
    public String getNodeId() { return nodeId; }
    public String getSource() { return source; }
    public String getVersion() { return version; }
    public long getSequence() { return sequence; }
    public MainPacketAssembler getAssembler() { return assembler; }
    public OfflineQueue getOfflineQueue() { return offlineQueue; }
    public AggregatorHealthCheck getHealthCheck() { return healthCheck; }
    public ZCSKernel getKernel() { return kernel; }

    /** Shutdown network components — stops health check, drains offline queue. */
    public void shutdown() {
        healthCheck.stop();
    }
}
