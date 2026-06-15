// ZCSLIB Evolution — Training UI
// Pure Java SE (java.base + java.desktop) — no Minecraft/NeoForge imports
// Swing UI: black background, green monospaced text, terminal aesthetic
package zcslib.daemon.ui;

import zcslib.evolution.params.GlobalParams;
import zcslib.evolution.params.LocalParams;
import zcslib.evolution.params.BilateralParams;
import zcslib.evolution.params.AttentionParams;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Terminal-style Swing UI for the ZCSLIB Daemon.
 * <p>
 * Displays personality parameters, plugin topology, training state,
 * and provides manual overrides (sliders + freeze button).
 * <p>
 * Invoked via {@code java -jar ZCSLIB.jar --daemon ui}.
 */
public class TrainingUI {

    // ── Color palette ──────────────────────────────────────
    private static final Color BG  = new Color(0x0C, 0x0C, 0x0C);
    private static final Color FG  = new Color(0x00, 0xFF, 0x41);
    private static final Color DIM = new Color(0x00, 0x7A, 0x1F);
    private static final Color WARN = new Color(0xFF, 0x99, 0x00);
    private static final Color ERR  = new Color(0xFF, 0x33, 0x33);
    private static final Font  MONO = new Font("Consolas", Font.PLAIN, 13);
    private static final Font  TITLE_FONT = new Font("Consolas", Font.BOLD, 18);
    private static final Font  HEAD_FONT = new Font("Consolas", Font.BOLD, 14);

    private final JFrame frame;
    private final JTextArea logArea;
    private final Map<String, JSlider> sliderMap = new LinkedHashMap<>();
    private final JButton freezeBtn;
    private Consumer<GlobalParams> freezeCallback;

    public TrainingUI() {
        frame = new JFrame("ZCSLIB :: Taming Console v0.2.0");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(960, 680);
        frame.setLayout(new BorderLayout(4, 4));
        frame.getContentPane().setBackground(BG);

        // ── Title bar ──────────────────────────────────────
        JLabel title = mkLabel(" ZCSLIB Taming Console", TITLE_FONT, FG);
        title.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        frame.add(title, BorderLayout.NORTH);

        // ── Center: log output ─────────────────────────────
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(BG);
        logArea.setForeground(DIM);
        logArea.setFont(MONO);
        logArea.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(DIM, 1));
        frame.add(scroll, BorderLayout.CENTER);

        // ── West: personality sliders ──────────────────────
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

        panel.add(mkLabel("Global Parameters", HEAD_FONT, FG));
        panel.add(Box.createVerticalStrut(8));

        addSlider(panel, "entropy_tolerance",  "Entropy Tolerance",  0.5, 0.0, 1.0);
        addSlider(panel, "self_healing_urgency","Self-Healing Urgency",0.5, 0.0, 1.0);
        addSlider(panel, "resource_hunger",     "Resource Hunger",   0.5, 0.0, 1.0);
        addSlider(panel, "scan_sensitivity",    "Scan Sensitivity",  0.5, 0.0, 1.0);

        panel.add(Box.createVerticalStrut(16));
        panel.add(mkLabel("Local Parameters", HEAD_FONT, FG));
        panel.add(Box.createVerticalStrut(8));

        addSlider(panel, "suppression_bias",  "Suppression Bias",  0.5, 0.0, 1.0);
        addSlider(panel, "privilege_bias",    "Privilege Bias",    0.5, 0.0, 1.0);
        addSlider(panel, "resource_weight",   "Resource Weight",   1.0, 0.0, 2.0);

        panel.add(Box.createVerticalStrut(16));

        // ── Freeze button ─────────────────────────────────
        freezeBtn = new JButton("[ FREEZE PERSONALITY ]");
        freezeBtn.setFont(new Font("Consolas", Font.BOLD, 13));
        freezeBtn.setForeground(BG);
        freezeBtn.setBackground(WARN);
        freezeBtn.setFocusPainted(false);
        freezeBtn.setBorder(BorderFactory.createLineBorder(FG, 1));
        freezeBtn.addActionListener(e -> onFreeze());
        freezeBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(freezeBtn);

        frame.add(panel, BorderLayout.WEST);

        // ── Close → confirm ───────────────────────────────
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                appendLog("[UI] Taming Console closed.", WARN);
            }
        });
    }

    // ── Slider helper ──────────────────────────────────────

    private void addSlider(JPanel panel, String id, String label,
                           double initial, double min, double max) {
        JLabel lbl = mkLabel("  " + label, MONO, DIM);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(lbl);

        JSlider slider = new JSlider(0, 100, (int)((initial - min) / (max - min) * 100));
        slider.setBackground(BG);
        slider.setForeground(FG);
        slider.setAlignmentX(Component.LEFT_ALIGNMENT);
        slider.setMaximumSize(new Dimension(220, 30));
        slider.setPaintTicks(false);
        slider.addChangeListener(e -> {
            double val = min + (slider.getValue() / 100.0) * (max - min);
            slider.setToolTipText(String.format("%s = %.2f", label, val));
        });
        sliderMap.put(id, slider);
        panel.add(slider);
        panel.add(Box.createVerticalStrut(4));
    }

    // ── Freeze callback ────────────────────────────────────

    public void onFreeze(Consumer<GlobalParams> callback) {
        this.freezeCallback = callback;
    }

    private void onFreeze() {
        double entropy = readSlider("entropy_tolerance", 0.0, 1.0);
        double healing = readSlider("self_healing_urgency", 0.0, 1.0);
        double hunger  = readSlider("resource_hunger", 0.0, 1.0);
        double scan    = readSlider("scan_sensitivity", 0.0, 1.0);

        GlobalParams gp = new GlobalParams();
        gp.set("entropy_tolerance", entropy);
        gp.set("self_healing_urgency", healing);
        gp.set("resource_hunger", hunger);
        gp.set("scan_sensitivity", scan);
        appendLog("[FREEZE] Locking personality:", FG);
        appendLog(String.format("  entropy=%.2f healing=%.2f hunger=%.2f scan=%.2f",
                entropy, healing, hunger, scan), FG);

        if (freezeCallback != null) {
            freezeCallback.accept(gp);
        }
        freezeBtn.setEnabled(false);
        freezeBtn.setText("[ FROZEN ]");
        freezeBtn.setBackground(DIM);
    }

    // ── Public API ─────────────────────────────────────────

    public void show() {
        SwingUtilities.invokeLater(() -> {
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            appendLog("[UI] Taming Console ready.", FG);
        });
    }

    public void hide() {
        SwingUtilities.invokeLater(() -> frame.dispose());
    }

    public void appendLog(String line, Color color) {
        SwingUtilities.invokeLater(() -> {
            // Color isn't directly supported in JTextArea, so we prefix with timestamp
            logArea.append(String.format("[%tT] %s%n", System.currentTimeMillis(), line));
        });
    }

    /** Hook into Daemon lifecycle — wait for window to close. */
    public void waitForClose() throws InterruptedException {
        while (frame.isVisible()) {
            Thread.sleep(200);
        }
    }

    // ── Helpers ────────────────────────────────────────────

    private JLabel mkLabel(String text, Font font, Color color) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(font);
        lbl.setForeground(color);
        return lbl;
    }

    private double readSlider(String id, double min, double max) {
        JSlider s = sliderMap.get(id);
        if (s == null) return (min + max) / 2;
        return min + (s.getValue() / 100.0) * (max - min);
    }

    // ── Entry point ────────────────────────────────────────

    public static void main(String[] args) {
        // Ensure Swing L&F is system-native
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        TrainingUI ui = new TrainingUI();
        ui.show();
    }
}
