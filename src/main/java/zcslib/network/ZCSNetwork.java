package zcslib.network;

import zcslib.api.OrderResult;
import zcslib.api.TrustLevel;
import zcslib.kernel.ZCSKernel;
import zcslib.log.ZCSLogger;
import zcslib.log.AuditLogger;

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
        if (args.length < 3) {
            return OrderResult.fail("Usage: network:send:standard <method> <path> <body>");
        }

        String method = args[0].toString().toUpperCase();
        String path = args[1].toString();
        String body = args.length > 2 && args[2] != null ? args[2].toString() : null;
        TrustLevel trust = (args.length > 3 && args[3] instanceof TrustLevel t) ? t : TrustLevel.N;

        OrderResult gate = trustGate(trust, "send:standard");
        if (gate == null) gate = trustGate(trust, "send:standard"); // S passes but audited below

        try {
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

            if (trust == TrustLevel.S) {
                AuditLogger.log(TrustLevel.S, "kernel", "send:standard",
                        method + " " + path + " → " + resp.statusCode());
            }

            return OrderResult.success(resp.body());
        } catch (Exception e) {
            LOG.error("Standard send failed: {} {} — {}", method, path, e.getMessage());
            return OrderResult.fail("SEND_FAILED: " + e.getMessage());
        }
    }

    // —— send:main ——

    private OrderResult sendMain(Object... args) {
        if (args.length < 2) {
            return OrderResult.fail("Usage: network:send:main <packetName> <data>");
        }

        String packetName = args[0].toString();
        Object data = args[1];
        // Trust level extracted from caller context, default N
        TrustLevel trust = (args.length > 2 && args[2] instanceof TrustLevel t) ? t : TrustLevel.N;

        OrderResult gate = trustGate(trust, "send:main");
        if (gate != null) return gate;

        assembler.enqueue(packetName, data);
        return OrderResult.success("ENQUEUED");
    }

    // —— offline ——

    private OrderResult setOfflineStrategy(Object... args) {
        if (args.length < 1) {
            return OrderResult.fail("Usage: network:offline <RETRY_LATER|DISCARD>");
        }

        try {
            OfflineQueue.Strategy strat = OfflineQueue.Strategy.valueOf(args[0].toString().toUpperCase());
            offlineQueue.setStrategy(strat);
            return OrderResult.success("Offline strategy set to " + strat);
        } catch (IllegalArgumentException e) {
            return OrderResult.fail("Unknown strategy: " + args[0]);
        }
    }

    // —— Packet assembly (called by MainPacketAssembler at tick-end) ——

    void flushAndSend(String jsonPayload, String hmacSignature) {
        if (aggregatorUrl == null) {
            LOG.warn("Network not configured, discarding assembled packet");
            return;
        }

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
}
