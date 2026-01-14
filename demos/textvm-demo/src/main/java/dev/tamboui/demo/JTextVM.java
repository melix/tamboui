//DEPS dev.tamboui:tamboui-toolkit:LATEST
//DEPS dev.tamboui:tamboui-panama-backend:LATEST
//DEPS dev.tamboui:tamboui-jline:LATEST
//DEPS dev.tamboui:tamboui-widgets:LATEST
//DEPS dev.tamboui:tamboui-css:LATEST
//SOURCES JvmProcessCollector.java
//FILES themes/textvm.tcss=../../../../resources/themes/textvm.tcss

/*
 * Copyright (c) 2025 TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.demo;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.gauge;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dev.tamboui.css.engine.StyleEngine;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.list.ListItem;
import dev.tamboui.widgets.list.ListState;

/**
 * TextVM - A text-based JVM process monitor inspired by VisualVM.
 * <p>
 * Features:
 * <ul>
 *   <li>List of Java processes on the left</li>
 *   <li>Detailed process information on the right (memory, threads, CPU, system properties)</li>
 *   <li>CSS-styled interface</li>
 *   <li>Auto-refresh of process list</li>
 * </ul>
 * <p>
 * Controls:
 * <ul>
 *   <li>Up/Down or j/k - Navigate process list</li>
 *   <li>r - Refresh process list</li>
 *   <li>q or Ctrl+C - Quit</li>
 * </ul>
 */
public class JTextVM implements Element {

    private final StyleEngine styleEngine;
    private final ListState processListState = new ListState();
    private final JvmProcessCollector processCollector;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private List<JvmProcessInfo> processes = new ArrayList<>();
    private JvmProcessInfo selectedProcess = null;
    private ProcessDetails selectedDetails = null;
    
    // FPS tracking
    private int frameCount = 0;
    private long lastFpsTime = System.currentTimeMillis();
    private Float currentFps = null;

    public JTextVM() {
        styleEngine = StyleEngine.create();
        try {
            styleEngine.loadStylesheet("default", "/themes/textvm.tcss");
            styleEngine.setActiveStylesheet("default");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load CSS theme", e);
        }
        processCollector = new JvmProcessCollector();
        refreshProcessList();
        if (!processes.isEmpty()) {
            processListState.selectFirst();
            updateSelectedProcess();
        }
        // Schedule periodic refresh
        scheduler.scheduleAtFixedRate(this::refreshProcessList, 2, 2, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws Exception {
        var app = new JTextVM();
        app.run();
    }

    public void run() throws Exception {
        var config = TuiConfig.builder()
            .mouseCapture(true)
                .tickRate(Duration.ofMillis(5))
          //  .tickRate(Duration.ofMillis(500))
          //.noTick()
          .build();

        try (var runner = ToolkitRunner.builder().config(config).styleEngine(styleEngine).build()) {
            runner.run(() -> this);
        } finally {
            scheduler.shutdown();
        }
    }

    @Override
    public void render(Frame frame, Rect area, RenderContext context) {
        updateFps();
        
        column(
            // Header
            panel(() -> row(
                text(" TextVM ").addClass("header"),
                spacer(),
                text(" [r] Refresh ").addClass("dim"),
                text(" [q] Quit ").addClass("dim"),
                currentFps != null ? text(String.format(" %5.1f fps ", currentFps)).addClass("info") : text("")
            )).rounded().length(3).addClass("status"),

            // Main content - split left/right
            row(
                // Left panel - Process list
                list()
                    .data(processes, proc -> ListItem.from(
                        String.format("%-6d %s", proc.pid(), proc.displayName())
                    ))
                    .id("process-list")
                    .state(processListState)
                    .title("Java Processes")
                    .rounded()
                    .autoScroll()
                    .percent(35),

                // Right panel - Process details
                panel(() -> renderProcessDetails())
                    .id("details-panel")
                    .title("Process Details")
                    .rounded()
                    .fill()
            ).fill(),

            // Footer
            panel(() -> row(
                text(" Processes: ").addClass("dim"),
                text(String.valueOf(processes.size())).addClass("info"),
                spacer(),
                text(" Selected: ").addClass("dim"),
                text(selectedProcess != null ? String.valueOf(selectedProcess.pid()) : "None").addClass("info")
            )).rounded().length(3).addClass("status")
        ).render(frame, area, context);
    }

    private Element renderProcessDetails() {
        if (selectedProcess == null || selectedDetails == null) {
            return column(
                text("No process selected").addClass("dim"),
                spacer(1),
                text("Use Up/Down to navigate the process list")
            );
        }

        var details = selectedDetails;
        var rows = new ArrayList<Element>();

        // Process Info
        rows.add(text("Process Information").addClass("section-header"));
        rows.add(text(String.format("PID: %d", selectedProcess.pid())).addClass("info"));
        rows.add(text(String.format("Main Class: %s", selectedProcess.mainClass())).addClass("info"));
        rows.add(text(String.format("Arguments: %s", selectedProcess.arguments())).addClass("dim"));
        rows.add(spacer(1));

        // Memory Info
        rows.add(text("Memory").addClass("section-header"));
        rows.add(text(String.format("Heap Used: %s", formatBytes(details.heapUsed()))).addClass("info"));
        rows.add(text(String.format("Heap Max: %s", formatBytes(details.heapMax()))).addClass("info"));
        rows.add(text(String.format("Heap Committed: %s", formatBytes(details.heapCommitted()))).addClass("info"));
        if (details.heapMax() > 0) {
            double heapPercent = (double) details.heapUsed() / details.heapMax() * 100;
            rows.add(gauge(heapPercent / 100.0).addClass("memory-gauge"));
            rows.add(text(String.format("Heap Usage: %.1f%%", heapPercent)).addClass("info"));
        }
        rows.add(text(String.format("Non-Heap Used: %s", formatBytes(details.nonHeapUsed()))).addClass("dim"));
        rows.add(spacer(1));

        // Thread Info
        rows.add(text("Threads").addClass("section-header"));
        rows.add(text(String.format("Live Threads: %d", details.threadCount())).addClass("info"));
        rows.add(text(String.format("Peak Threads: %d", details.peakThreadCount())).addClass("dim"));
        rows.add(text(String.format("Daemon Threads: %d", details.daemonThreadCount())).addClass("dim"));
        rows.add(spacer(1));

        // Runtime Info
        rows.add(text("Runtime").addClass("section-header"));
        rows.add(text(String.format("Uptime: %s", formatDuration(details.uptime()))).addClass("info"));
        rows.add(text(String.format("VM Name: %s", details.vmName())).addClass("dim"));
        rows.add(text(String.format("VM Version: %s", details.vmVersion())).addClass("dim"));
        rows.add(text(String.format("VM Vendor: %s", details.vmVendor())).addClass("dim"));
        rows.add(spacer(1));

        // System Properties (limited)
        if (!details.systemProperties().isEmpty()) {
            rows.add(text("System Properties").addClass("section-header"));
            int count = 0;
            for (var entry : details.systemProperties().entrySet()) {
                if (count++ >= 5) {
                    rows.add(text("... (more properties available)").addClass("dim"));
                    break;
                }
                rows.add(text(String.format("%s = %s", entry.getKey(), 
                    truncate(String.valueOf(entry.getValue()), 50))).addClass("dim"));
            }
        }

        return column(rows.toArray(Element[]::new));
    }

    @Override
    public Constraint constraint() {
        return Constraint.fill();
    }

    @Override
    public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        if (event.isCharIgnoreCase('r')) {
            refreshProcessList();
            return EventResult.HANDLED;
        }
        if (event.isUp() || event.isCharIgnoreCase('k')) {
            processListState.selectPrevious();
            updateSelectedProcess();
            return EventResult.HANDLED;
        }
        if (event.isDown() || event.isCharIgnoreCase('j')) {
            processListState.selectNext(processes.size());
            updateSelectedProcess();
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    private void refreshProcessList() {
        try {
            processes = processCollector.collectProcesses();
            // Maintain selection if possible
            if (selectedProcess != null) {
                var currentPid = selectedProcess.pid();
                for (int i = 0; i < processes.size(); i++) {
                    if (processes.get(i).pid() == currentPid) {
                        processListState.select(i);
                        updateSelectedProcess();
                        return;
                    }
                }
                // Process no longer exists
                if (!processes.isEmpty()) {
                    processListState.selectFirst();
                } else {
                    processListState.select(null);
                }
            }
            updateSelectedProcess();
        } catch (Exception e) {
            // Silently handle errors - process might have exited
        }
    }

    private void updateSelectedProcess() {
        var selected = processListState.selected();
        if (selected != null && selected >= 0 && selected < processes.size()) {
            selectedProcess = processes.get(selected);
            try {
                selectedDetails = processCollector.collectDetails(selectedProcess);
            } catch (Exception e) {
                selectedDetails = null;
            }
        } else {
            selectedProcess = null;
            selectedDetails = null;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours % 24, minutes % 60);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen - 3) + "...";
    }

    private void updateFps() {
        frameCount++;
        long now = System.currentTimeMillis();
        long elapsed = now - lastFpsTime;
        if (elapsed >= 1000 && frameCount > 2) {
            currentFps = (float) frameCount / (elapsed / 1000.0f);
            frameCount = 0;
            lastFpsTime = now;
        }
    }

    /**
     * Information about a JVM process.
     */
    record JvmProcessInfo(
        int pid,
        String mainClass,
        String arguments,
        String displayName
    ) {}

    /**
     * Detailed information about a JVM process.
     */
    record ProcessDetails(
        long heapUsed,
        long heapMax,
        long heapCommitted,
        long nonHeapUsed,
        int threadCount,
        int peakThreadCount,
        int daemonThreadCount,
        long uptime,
        String vmName,
        String vmVersion,
        String vmVendor,
        Properties systemProperties
    ) {}
}

