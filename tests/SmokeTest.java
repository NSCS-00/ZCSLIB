// ZCSLIB Smoke Test — standalone (no MC required)
// Compile: javac --class-path "build/libs/ZCSLIB-*-BUILD.*_windows_amd64.jar" -d out SmokeTest.java
// Run:     java  --class-path "build/libs/ZCSLIB-*-BUILD.*_windows_amd64.jar;out" SmokeTest
// Network test requires: python tests/server.py running on :19998

import zcslib.api.TrustLevel;
import zcslib.log.AuditLogger;
import zcslib.log.CrashHandler;
import zcslib.daemon.DreamWorker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class SmokeTest {

    private static int pass = 0, fail = 0, skip = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== ZCSLIB Smoke Tests ===");
        System.out.println("CWD: " + Path.of("").toAbsolutePath());
        System.out.println();

        testAuditLogger();
        testCrashHandler();
        testNetworkEcho();
        testDreamWorker();

        System.out.println();
        System.out.println("=== Results: " + pass + " PASS, " + fail + " FAIL, " + skip + " SKIP ===");
        System.exit(fail > 0 ? 1 : 0);
    }

    // ── Test 1: AuditLogger ─────────────────────────────────

    static void testAuditLogger() throws Exception {
        System.out.println("── Test 1: AuditLogger ──");

        Path root = Path.of("logs/zcslib/audit");
        AuditLogger al = new AuditLogger(root);

        al.log(TrustLevel.N, "test-plugin", "TEST", "normal operation");
        al.log(TrustLevel.R, "test-plugin", "TEST", "restricted operation");
        al.log(TrustLevel.A, "test-plugin", "TEST", "audited operation");
        al.log(TrustLevel.S, "test-plugin", "S_SAFE", "system operation");
        al.logTrusted("dangerous-plugin", "CROSS_CALL", "cross-trust access detected",
                TrustLevel.S, TrustLevel.N);
        al.flushAll();

        String date = java.time.LocalDate.now().toString();
        checkFile(root.resolve("N/test-plugin_" + date + ".log"), "N level audit log");
        checkFile(root.resolve("R/test-plugin_" + date + ".log"), "R level audit log");
        checkFile(root.resolve("A/test-plugin_" + date + ".log"), "A level audit log");
        checkFile(root.resolve("S/test-plugin_" + date + ".log"), "S level audit log");
        checkFile(root.resolve("S/dangerous-plugin_" + date + ".log"), "S cross-trust log");

        String sTest = Files.readString(root.resolve("S/test-plugin_" + date + ".log"));
        assertContains(sTest, "S_SAFE", "S-level entry has S_SAFE category");

        String sCross = Files.readString(root.resolve("S/dangerous-plugin_" + date + ".log"));
        assertContains(sCross, "[WARN]", "cross-trust entry uses WARN severity");
        assertContains(sCross, "CROSS_CALL", "cross-trust log has CROSS_CALL category");
        assertContains(sCross, "caller=S", "audit log records caller trust level");
        assertContains(sCross, "callee=N", "audit log records callee trust level");

        al.closeAll();
        System.out.println();
    }

    // ── Test 2: CrashHandler ────────────────────────────────

    static void testCrashHandler() throws Exception {
        System.out.println("── Test 2: CrashHandler ──");

        CrashHandler.setKernel(null);

        RuntimeException ex1 = new RuntimeException("smoke crash #1");
        boolean ok = CrashHandler.handlePluginCrash("broken-plugin", ex1);
        check(ok, "handlePluginCrash returns true");

        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        IOException cause = new IOException("disk full");
        RuntimeException ex2 = new RuntimeException("wrapped crash", cause);
        CrashHandler.handlePluginCrash("broken-plugin", ex2);

        Path crashDir = Path.of("logs/zcslib/crashes/plugins/broken-plugin");
        List<Path> files;
        try (var s = Files.list(crashDir)) {
            files = s.sorted().toList();
        }
        check(files.size() >= 2, "crash report files created (" + files.size() + " found)");

        String c1 = Files.readString(files.get(0));
        assertContains(c1, "ZCSLIB CRASH REPORT", "crash report has header");
        assertContains(c1, "PLUGIN:broken-plugin", "crash report has plugin category");
        assertContains(c1, "RuntimeException", "crash report contains exception type");

        String c2 = Files.readString(files.get(1));
        assertContains(c2, "Caused by", "crash report traces nested cause chain");
        assertContains(c2, "disk full", "crash report includes cause message");

        System.out.println();
    }

    // ── Test 3: Network (plain HttpClient) ──────────────────

    static void testNetworkEcho() throws Exception {
        System.out.println("── Test 3: Network Echo ──");

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:19998/health"))
                    .GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            check(resp.statusCode() == 200, "GET returns 200");
            assertContains(resp.body(), "/health", "GET echo contains requested path");
        } catch (java.net.ConnectException e) {
            skip("echo server not running — start with: python tests/server.py");
        }

        try {
            String json = "{\"test\":\"hello\",\"value\":42}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:19998/api/test"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            check(resp.statusCode() == 200, "POST returns 200");
            assertContains(resp.body(), "\"test\":", "POST echo contains JSON body");
        } catch (java.net.ConnectException e) {
            if (skip == 0) skip("echo server not running");
        }

        System.out.println();
    }

    // ── Test 4: DreamWorker (L2→L3 pipeline, no MC) ─────────

    static void testDreamWorker() throws Exception {
        System.out.println("── Test 4: DreamWorker (no MC) ──");

        // Create L2 journal files under plugins/{id}/memory/l2/
        // Format: [T=12345][pluginId][subsystem:action] -> RESULT (123ms)

        // Plugin "faulty-mod": 10 events → 2 windows, triggers frequent_timeout (HIGH)
        Path l2Dir = Path.of("plugins/faulty-mod/memory/l2");
        Files.createDirectories(l2Dir);
        String l2Content = String.join("\n",
                // Window 1: mixed OK + TIMEOUT — timeoutRate = 2/5 = 0.4 → HIGH
                "[T=1000][faulty-mod][scheduler:compute] -> OK (12ms)",
                "[T=1100][faulty-mod][scheduler:compute] -> OK (18ms)",
                "[T=1200][faulty-mod][network:send] -> TIMEOUT (48ms)",
                "[T=1300][faulty-mod][network:send] -> OK (15ms)",
                "[T=1400][faulty-mod][network:send] -> TIMEOUT (52ms)",
                // Window 2: rising latency + ERROR → perfDeg + avalanche precursor
                "[T=1500][faulty-mod][scheduler:io] -> OK (5ms)",
                "[T=1600][faulty-mod][scheduler:io] -> OK (22ms)",
                "[T=1700][faulty-mod][scheduler:io] -> OK (38ms)",
                "[T=1800][faulty-mod][scheduler:io] -> OK (56ms)",
                "[T=1900][faulty-mod][scheduler:io] -> ERROR (120ms)",
                // Also add some BLOCKED events
                "[T=2000][faulty-mod][event:post] -> BLOCKED (0ms)",
                "[T=2100][faulty-mod][event:post] -> REJECTED (1ms)"
        ) + "\n";
        Files.writeString(l2Dir.resolve("test-001.zcslog"), l2Content);

        // Plugin "stable-mod": all OK, should match nothing meaningful
        Path l2Dir2 = Path.of("plugins/stable-mod/memory/l2");
        Files.createDirectories(l2Dir2);
        StringBuilder stableLog = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            stableLog.append(String.format("[T=%d][stable-mod][scheduler:compute] -> OK (%dms)%n",
                    1000 + i * 100, 3 + i));
        }
        Files.writeString(l2Dir2.resolve("test-002.zcslog"), stableLog.toString());

        // Create empty l4 dir (no instinct file → uses default empty instinct)
        Path l4Dir = Path.of("plugins/.zcslib/l4");
        Files.createDirectories(l4Dir);

        // Empty mcRoot (no MC installed → RestartEngine will fail gracefully)
        // Do NOT create config/ subdir — RestartEngine checks for it as MC install signal
        Path mcRoot = Path.of("no-mc-here");
        Files.createDirectories(mcRoot);

        // Run dream
        System.out.println("       Running DreamWorker...");
        System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        DreamWorker dw = new DreamWorker(Path.of("plugins"), l4Dir, mcRoot);
        try {
            dw.run();
            check(true, "DreamWorker.run() completed without exception");
        } catch (Exception e) {
            check(false, "DreamWorker.run() threw: " + e.getMessage());
            e.printStackTrace();
        }
        System.setErr(System.err);

        // Verify: faulty-mod L2 consumed (deleted) — it generated rules + attempted validation
        check(!Files.exists(l2Dir.resolve("test-001.zcslog")),
                "faulty-mod L2 journal consumed (deleted)");
        // stable-mod: all OK events → no rules generated → L2 preserved (by design)
        check(Files.exists(l2Dir2.resolve("test-002.zcslog")),
                "stable-mod L2 preserved (no rules to act on)");

        // Verify: no L3 produced (MC validation failed, stable=false)
        Path l3Dir1 = Path.of("plugins/faulty-mod/memory/l3");
        if (!Files.exists(l3Dir1)) {
            check(true, "no L3 written for faulty-mod (MC validation failed as expected)");
        } else {
            try (var s = Files.list(l3Dir1)) {
                long count = s.filter(f -> f.getFileName().toString().endsWith(".zcsmem")).count();
                skip("L3 files found for faulty-mod (" + count + ") — MC might be installed?");
            }
        }

        System.out.println();
    }

    // ── Helpers ─────────────────────────────────────────────

    static void checkFile(Path file, String label) throws IOException {
        if (Files.exists(file) && Files.size(file) > 0) {
            System.out.println("  PASS  " + label + " (" + Files.size(file) + " bytes)");
            pass++;
        } else {
            System.out.println("  FAIL  " + label + " — file missing or empty: " + file);
            fail++;
        }
    }

    static void check(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("  PASS  " + msg); }
        else      { fail++; System.out.println("  FAIL  " + msg); }
    }

    static void skip(String msg) {
        skip++;
        System.out.println("  SKIP  " + msg);
    }

    static void assertContains(String haystack, String needle, String msg) {
        if (haystack.contains(needle)) {
            pass++;
            System.out.println("  PASS  " + msg);
        } else {
            fail++;
            System.out.println("  FAIL  " + msg);
            System.out.println("        expected '" + needle + "' — not found");
            int cut = Math.min(200, haystack.length());
            System.out.println("        excerpt: " + haystack.substring(0, cut));
        }
    }
}
