///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//JAVA_OPTIONS --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//JAVA_OPTIONS --sun-misc-unsafe-memory-access=allow
//DEPS org.asciidoctor:asciidoctorj:3.0.1
//DEPS org.asciidoctor:asciidoctorj-diagram:3.2.1
//DEPS org.asciidoctor:asciidoctorj-diagram-batik:1.19
//DEPS org.asciidoctor:asciidoctorj-diagram-ditaamini:1.0.3
//DEPS org.asciidoctor:asciidoctorj-diagram-plantuml:1.2026.2
//DEPS org.asciidoctor:asciidoctorj-diagram-jsyntrax:1.38.2
//DEPS org.asciidoctor:asciidoctorj-pdf:2.3.23
//DEPS org.asciidoctor:asciidoctorj-revealjs:5.2.0
//DEPS org.asciidoctor:asciidoctorj-chart:1.0.0
//DEPS info.picocli:picocli:4.6.3
//DEPS dev.tamboui:tamboui-toolkit:LATEST
//DEPS dev.tamboui:tamboui-jline3-backend:LATEST

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.SimpleFileServer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.text.Emoji;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Attributes;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;
import org.asciidoctor.ast.PhraseNode;
import org.asciidoctor.ast.StructuralNode;
import org.asciidoctor.extension.InlineMacroProcessor;
import org.asciidoctor.extension.Name;
import org.asciidoctor.log.LogHandler;
import org.asciidoctor.log.LogRecord;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dock;
import static dev.tamboui.toolkit.Toolkit.markupText;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

@Command(
        name = "lazyslide",
        version = "0.1.0",
        mixinStandardHelpOptions = true,
        description = "lazyslide: turn AsciiDoc into reveal.js slides, with live reload and a comfy terminal dashboard.",
        subcommands = {
                lazyslide.Render.class,
                lazyslide.Serve.class,
                lazyslide.Watch.class,
                lazyslide.Init.class
        }
)
public class lazyslide implements Runnable {

    @Option(names = "--verbose", description = "Show extra watcher chatter too")
    boolean verbose;

    @Option(names = {"--revealjsdir"}, defaultValue = "https://cdn.jsdelivr.net/npm/reveal.js@5.2.0", description = "Custom reveal.js base URL or directory")
    String revealjsdir;

    @Option(names = {"-o", "--output-dir"}, defaultValue = "public", description = "Where lazyslide should write the rendered deck")
    String outputDirName;

    // --- internal state set by subcommands before execute() ---
    File file;
    /** When input is a directory, this holds the resolved source dir and the virtual index is generated. */
    Path inputDir;
    /** The generated virtual index.adoc content when input is a directory. */
    String virtualIndexContent;
    boolean open;
    int port = 8181;

    /** Default directory names to look for when no argument is given. */
    private static final List<String> DEFAULT_INPUT_DIRS = List.of("slides", "docs");

    /** Directories and files that are never mirrored into the output dir. */
    private static final List<String> MIRROR_EXCLUDED = List.of("slides");

    private volatile Asciidoctor asciidoctor;
    private volatile boolean asciidoctorReady;
    private WatchService watchService;
    private HttpServer httpServer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("lazyslide-scheduler", 0).factory());
    private final ConcurrentLinkedQueue<String> statusMessages = new ConcurrentLinkedQueue<>();
    private final LinkedHashSet<String> pendingChangedFiles = new LinkedHashSet<>();
    private final Object stateLock = new Object();

    private volatile ScheduledFuture<?> pendingRender;
    private volatile boolean running;
    private volatile boolean showHelp = true;
    volatile boolean watching;
    volatile boolean serving;
    private volatile int renderCount;
    private volatile long lastRenderNanos;
    private volatile long totalRenderNanos;
    private volatile Instant lastRenderAt;
    private volatile String lastRenderReason = "startup";
    private volatile String lastRenderSummary = "Asciidoctor loading...";
    private volatile List<String> lastChangedFiles = List.of();
    volatile boolean interactive;
    private volatile Thread watchThread;

    public static void main(String... args) {
        System.exit(new CommandLine(new lazyslide()).execute(args));
    }

    @Override
    public void run() {
        // bare "lazyslide" with no subcommand — print help
        new CommandLine(this).usage(System.out);
    }

    /**
     * Resolve the input — if it's a directory, scan for .adoc files and
     * generate a virtual index.adoc that includes them all.
     * If no argument was given, look for default dirs (slides/, docs/).
     */
    private void resolveInput() throws IOException {
        Path path = file.toPath().toAbsolutePath().normalize();

        // If the given path doesn't exist and matches the default, try fallbacks
        if (!Files.exists(path) && file.getName().equals("index.adoc")) {
            Path cwd = Path.of(".").toAbsolutePath().normalize();
            for (String dirName : DEFAULT_INPUT_DIRS) {
                Path candidate = cwd.resolve(dirName);
                if (Files.isDirectory(candidate)) {
                    path = candidate;
                    break;
                }
            }
            // If still no directory found, fall through — the original file-not-found
            // error will surface during render
        }

        if (Files.isDirectory(path)) {
            inputDir = path;
            List<Path> adocFiles = scanAdocFiles(path);
            if (adocFiles.isEmpty()) {
                throw new IOException("No .adoc files found in " + path);
            }
            virtualIndexContent = generateVirtualIndex(path, adocFiles);
        } else if (!Files.exists(path)) {
            throw new IOException("File not found: " + path
                    + "\nNo 'slides/' or 'docs/' directory found either."
                    + "\nProvide an .adoc file or a directory of .adoc files.");
        }
    }

    /**
     * Recursively scan a directory for .adoc files, excluding hidden dirs,
     * the output dir, and any file starting with '.' or '_'.
     * Returns files sorted by relative path for deterministic ordering.
     */
    private List<Path> scanAdocFiles(Path dir) throws IOException {
        Path outDir = outputDir();
        try (var walk = Files.walk(dir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".adoc"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.startsWith(".") && !name.startsWith("_")
                                && !ATTRIBUTE_FILES.contains(name);
                    })
                    .filter(p -> {
                        // Skip files inside hidden dirs or the output dir
                        Path abs = p.toAbsolutePath().normalize();
                        if (abs.startsWith(outDir)) return false;
                        for (Path component : dir.relativize(p)) {
                            if (component.toString().startsWith(".")) return false;
                        }
                        return true;
                    })
                    .sorted(Comparator.comparing(p -> dir.relativize(p).toString()))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Generate a virtual index.adoc that includes all discovered .adoc files
     * using AsciiDoc include directives with leveloffset.
     */
    private String generateVirtualIndex(Path dir, List<Path> adocFiles) {
        var sb = new StringBuilder();
        sb.append("= Slides\n");
        sb.append("\n");

        for (Path adocFile : adocFiles) {
            String relativePath = dir.relativize(adocFile).toString();
            sb.append("include::").append(relativePath).append("[leveloffset=+1]\n");
            sb.append("\n");
        }

        return sb.toString();
    }

    /** Default attributes for reveal.js slides — soft-set so files can override. */
    private static final Map<String, String> DEFAULT_ATTRIBUTES = Map.ofEntries(
            Map.entry("icons", "font"),
            Map.entry("source-highlighter", "highlightjs"),
            Map.entry("highlightjs-theme", "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/atom-one-dark.min.css"),
            Map.entry("revealjs_theme", "black"),
            Map.entry("revealjs_transition", "slide"),
            Map.entry("stem", "latexmath"),
            Map.entry("revealjs_hash", "true"),
            Map.entry("revealjs_history", "true"),
            Map.entry("revealjs_width", "1920"),
            Map.entry("revealjs_height", "1080")
    );

    /** Attribute files to look for, in order. First found wins. */
    private static final List<String> ATTRIBUTE_FILES = List.of(
            "_lazyslide_attributes.adoc",
            "_attributes.adoc",
            "attributes.adoc"
    );

    /**
     * Find and parse the first available attributes file for :key: value definitions.
     * Searches for _lazyslide_attributes.adoc, _attributes.adoc, attributes.adoc in order.
     * Returns a map of attribute name to value.
     */
    private Map<String, String> loadAttributesFile(Path dir) {
        for (String filename : ATTRIBUTE_FILES) {
            Path candidate = dir.resolve(filename);
            if (Files.exists(candidate)) {
                var attrs = parseAttributesFile(candidate);
                enqueueMessage("loaded " + attrs.size() + " attributes from " + filename);
                return attrs;
            }
        }
        return Map.of();
    }

    /**
     * Parse an AsciiDoc file for attribute definitions (:key: value).
     */
    private Map<String, String> parseAttributesFile(Path file) {
        var attrs = new java.util.LinkedHashMap<String, String>();
        try {
            for (String line : Files.readAllLines(file)) {
                line = line.strip();
                if (line.startsWith("//") || line.isEmpty()) continue;
                if (line.startsWith(":") && line.indexOf(':', 1) > 1) {
                    int end = line.indexOf(':', 1);
                    String key = line.substring(1, end).strip();
                    String value = line.substring(end + 1).strip();
                    attrs.put(key, value);
                }
            }
        } catch (IOException e) {
            enqueueMessage("failed to read " + file.getFileName() + ": " + e.getMessage());
        }
        return attrs;
    }

    /**
     * Build the Attributes for rendering. Layers:
     * 1. lazyslide defaults (soft-set with @, overridable by files)
     * 2. _attributes.adoc from project (soft-set with @, overridable by files)
     * 3. CLI/internal attributes (hard-set, not overridable)
     */
    private Attributes buildRenderAttributes(Path outDir) {
        var builder = Attributes.builder();

        // 1. Defaults — soft-set so documents can override
        for (var entry : DEFAULT_ATTRIBUTES.entrySet()) {
            builder.attribute(entry.getKey() + "@", entry.getValue());
        }

        // 2. Project _attributes.adoc — soft-set so per-file can still override
        Path attrDir = inputDir != null ? inputDir : rootDir();
        for (var entry : loadAttributesFile(attrDir).entrySet()) {
            builder.attribute(entry.getKey() + "@", entry.getValue());
        }

        // 3. Internal attributes — hard-set, not overridable
        builder.attribute("revealjsdir", revealjsdir);
        builder.attribute("outdir", outDir.toString());

        return builder.build();
    }

    /**
     * Shared entry point called by each workflow subcommand after setting
     * {@code file}, {@code open}, {@code port}, {@code interactive},
     * {@code watching}, and {@code serving}.
     */
    private void initAsciidoctor() {
        enqueueMessage("warming up Asciidoctor...");
        long start = System.nanoTime();
        asciidoctor = Asciidoctor.Factory.create();
        asciidoctor.registerLogHandler(new LogHandler() {
            @Override
            public void log(LogRecord logRecord) {
                enqueueMessage("asciidoctor: " + logRecord.getMessage());
            }
        });
        asciidoctor.requireLibrary("asciidoctor-revealjs");
        asciidoctor.requireLibrary("asciidoctor-diagram");
        asciidoctor.requireLibrary("asciidoctor-chart");
        asciidoctor.javaExtensionRegistry().inlineMacro(new EmojiMacro());
        asciidoctorReady = true;
        long took = System.nanoTime() - start;
        enqueueMessage(String.format("Asciidoctor ready in %s", formatNanos(took)));
        scheduleRender("startup");
    }

    int execute() throws Exception {
        running = true;

        // Resolve input: if it's a directory, generate virtual index
        resolveInput();

        if (!interactive) {
            // Non-interactive: init synchronously, render, exit
            System.out.println("lazyslide: warming up Asciidoctor...");
            if (inputDir != null) {
                System.out.println("lazyslide: scanning directory " + inputDir);
                System.out.println("lazyslide: generated virtual index with " + virtualIndexContent.lines().filter(l -> l.startsWith("include::")).count() + " includes");
            }
            initAsciidoctor();
            doRender("startup");
            if (open) {
                openPresentation();
            }
            return 0;
        }

        // Interactive: start TUI immediately, init Asciidoctor in background
        try {
            if (inputDir != null) {
                enqueueMessage("directory mode: " + inputDir);
                enqueueMessage("virtual index: " + virtualIndexContent.lines().filter(l -> l.startsWith("include::")).count() + " includes");
            }
            if (serving) {
                startServer();
            }
            if (watching) {
                startWatcher();
            }

            Thread.ofVirtual().name("lazyslide-init").start(this::initAsciidoctor);

            new DashboardApp().run();
            return 0;
        } finally {
            shutdown();
        }
    }

    // ── Subcommands ──────────────────────────────────────────────────────

    @Command(name = "render", mixinStandardHelpOptions = true, description = "Render the deck once and exit")
    static final class Render implements Callable<Integer> {
        @ParentCommand private lazyslide parent;

        @Parameters(index = "0", arity = "0..1", description = "Deck entry file or directory of .adoc files to render.", defaultValue = "index.adoc")
        private File file;

        @Option(names = {"--open"}, description = "Open the deck in your browser after rendering")
        private boolean open;

        @Override
        public Integer call() throws Exception {
            parent.file = file;
            parent.open = open;
            parent.interactive = false;
            parent.watching = false;
            parent.serving = false;
            return parent.execute();
        }
    }

    @Command(name = "serve", mixinStandardHelpOptions = true, description = "Render, watch for changes, serve over HTTP, and show the terminal dashboard")
    static final class Serve implements Callable<Integer> {
        @ParentCommand private lazyslide parent;

        @Parameters(index = "0", arity = "0..1", description = "Deck entry file or directory of .adoc files to render.", defaultValue = "index.adoc")
        private File file;

        @Option(names = {"--open"}, description = "Open the deck in your browser after rendering")
        private boolean open;

        @Option(names = {"--port"}, defaultValue = "8181", description = "Port for the HTTP server")
        private int port;

        @Override
        public Integer call() throws Exception {
            parent.file = file;
            parent.open = open;
            parent.port = port;
            parent.interactive = true;
            parent.watching = true;
            parent.serving = true;
            return parent.execute();
        }
    }

    @Command(name = "watch", mixinStandardHelpOptions = true, description = "Render, watch for changes, and show the terminal dashboard (no HTTP server)")
    static final class Watch implements Callable<Integer> {
        @ParentCommand private lazyslide parent;

        @Parameters(index = "0", arity = "0..1", description = "Deck entry file or directory of .adoc files to render.", defaultValue = "index.adoc")
        private File file;

        @Option(names = {"--open"}, description = "Open the deck in your browser after rendering")
        private boolean open;

        @Override
        public Integer call() throws Exception {
            parent.file = file;
            parent.open = open;
            parent.interactive = true;
            parent.watching = true;
            parent.serving = false;
            return parent.execute();
        }
    }

    private Path rootDir() {
        if (inputDir != null) {
            return inputDir;
        }
        return file.getAbsoluteFile().getParentFile().toPath();
    }

    private String outputName() {
        if (inputDir != null) {
            // Directory mode always produces index.html
            return "index.html";
        }
        return file.getName().replaceFirst("\\.adoc$", ".html");
    }

    private Path outputDir() {
        Path candidate = Path.of(outputDirName);
        if (!candidate.isAbsolute()) {
            candidate = rootDir().resolve(candidate);
        }
        return candidate.toAbsolutePath().normalize();
    }

    private Path outputFile() {
        return outputDir().resolve(outputName());
    }

    private String currentUrl() {
        return serving ? "http://localhost:" + port + "/" + outputName() : outputFile().toUri().toString();
    }

    private void enqueueMessage(String msg) {
        statusMessages.add(timestamp() + " " + msg);
        while (statusMessages.size() > 12) {
            statusMessages.poll();
        }
    }

    private static String timestamp() {
        return timestamp(Instant.now());
    }

    private static String timestamp(Instant instant) {
        return DateTimeFormatter.ofPattern("HH:mm:ss").format(instant.atZone(java.time.ZoneId.systemDefault()));
    }

    private void registerRecursive(final Path root) throws IOException {
        final Path outDir = outputDir();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (dir.toAbsolutePath().normalize().equals(outDir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void mirrorDir(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        Files.createDirectories(to);
        try (var walk = Files.walk(from)) {
            walk.forEach(src -> {
                try {
                    Path rel = from.relativize(src);
                    Path dest = to.resolve(rel.toString());
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private synchronized void doRender(String reason) {
        if (!asciidoctorReady) {
            enqueueMessage("render skipped: Asciidoctor still loading...");
            return;
        }
        long start = System.nanoTime();
        lastRenderReason = reason;
        List<String> changed = drainChangedFiles();
        lastChangedFiles = changed;

        Path outDir = outputDir();
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            enqueueMessage("failed to create output dir " + outDir + ": " + e.getMessage());
            return;
        }

        // Clear diagram cache so diagrams always re-render
        try {
            Path cacheDir = rootDir().resolve(".asciidoctor/diagram");
            if (Files.isDirectory(cacheDir)) {
                try (var entries = Files.list(cacheDir)) {
                    entries.filter(p -> p.toString().endsWith(".cache")).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (IOException ignored) {
        }

        // Mirror all non-hidden, non-excluded dirs from source root into output so relative URLs resolve
        try (var entries = Files.list(rootDir())) {
            entries.filter(Files::isDirectory)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.startsWith(".")
                                && !p.toAbsolutePath().normalize().equals(outDir)
                                && !MIRROR_EXCLUDED.contains(name);
                    })
                    .forEach(from -> {
                        String name = from.getFileName().toString();
                        Path to = outDir.resolve(name);
                        try {
                            mirrorDir(from, to);
                        } catch (IOException e) {
                            enqueueMessage("asset sync failed for " + name + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            enqueueMessage("failed to list source dirs: " + e.getMessage());
        }

        // In directory mode, regenerate the virtual index to pick up new/deleted files
        if (inputDir != null && !"startup".equals(reason)) {
            try {
                List<Path> adocFiles = scanAdocFiles(inputDir);
                if (!adocFiles.isEmpty()) {
                    String newContent = generateVirtualIndex(inputDir, adocFiles);
                    if (!newContent.equals(virtualIndexContent)) {
                        virtualIndexContent = newContent;
                        enqueueMessage("virtual index updated: " + adocFiles.size() + " files");
                    }
                }
            } catch (IOException e) {
                enqueueMessage("failed to rescan directory: " + e.getMessage());
            }
        }

        if (interactive) {
            enqueueMessage("rendering: " + reason + (changed.isEmpty() ? "" : " | changed: " + String.join(", ", changed)));
        } else {
            System.out.printf("lazyslide: rendering %s -> %s (%s)%n", inputDir != null ? inputDir : file, outDir, reason);
            if (!changed.isEmpty()) {
                System.out.println("Changed: " + String.join(", ", changed));
            }
        }

        Attributes renderAttributes = buildRenderAttributes(outDir);

        if (inputDir != null) {
            // Directory mode: convert the virtual index in-memory with baseDir
            // so includes resolve relative to the scanned directory
            String html = asciidoctor.convert(virtualIndexContent,
                    Options.builder()
                            .backend("revealjs")
                            .safe(SafeMode.UNSAFE)
                            .standalone(true)
                            .baseDir(inputDir.toFile())
                            .mkDirs(true)
                            .attributes(renderAttributes)
                            .build()
            );
            Path targetFile = outDir.resolve("index.html");
            try {
                Files.writeString(targetFile, html);
            } catch (IOException e) {
                enqueueMessage("failed to write index.html: " + e.getMessage());
                return;
            }
        } else {
            // Single file mode: convert from file as before
            asciidoctor.convertFile(file,
                    Options.builder()
                            .backend("revealjs")
                            .safe(SafeMode.UNSAFE)
                            .toDir(outDir.toFile())
                            .mkDirs(true)
                            .attributes(renderAttributes)
                            .build()
            );
        }

        long took = System.nanoTime() - start;
        renderCount++;
        lastRenderNanos = took;
        totalRenderNanos += took;
        lastRenderAt = Instant.now();
        lastRenderSummary = String.format("Rendered %s in %.2fs", outputName(), took / 1_000_000_000.0);

        if (interactive) {
            enqueueMessage(lastRenderSummary);
        } else {
            System.out.printf("lazyslide: done in %.2fs (%s)%n", took / 1_000_000_000.0, file);
        }
    }

    private List<String> drainChangedFiles() {
        synchronized (stateLock) {
            if (pendingChangedFiles.isEmpty()) {
                return List.of();
            }
            List<String> changed = new ArrayList<>(pendingChangedFiles);
            pendingChangedFiles.clear();
            return changed;
        }
    }

    private void noteChanged(String path) {
        synchronized (stateLock) {
            pendingChangedFiles.add(path);
        }
        scheduleRender("file change");
    }

    private void scheduleRender(String reason) {
        if (pendingRender != null) {
            pendingRender.cancel(false);
        }
        pendingRender = scheduler.schedule(() -> doRender(reason), 180, TimeUnit.MILLISECONDS);
    }

    private void startServer() throws IOException {
        if (httpServer != null) {
            return;
        }
        var address = new InetSocketAddress(port);
        try {
            Path outDir = outputDir();
            Files.createDirectories(outDir);
            httpServer = SimpleFileServer.createFileServer(
                    address,
                    outDir,
                    SimpleFileServer.OutputLevel.NONE);
            httpServer.start();
            enqueueMessage("serving at " + currentUrl());
        } catch (Exception e) {
            httpServer = null;
            throw new IOException(String.format("failed to start server on %s:%d — %s", address.getHostString(), port, e.getMessage()), e);
        }
    }

    private void stopServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            enqueueMessage("server stopped");
        }
    }

    private void startWatcher() throws IOException {
        if (watchService != null) {
            return;
        }
        watchService = FileSystems.getDefault().newWatchService();
        registerRecursive(rootDir());
        enqueueMessage("watching " + rootDir());
        watchThread = Thread.ofVirtual().name("lazyslide-watch", 0).start(() -> {
            try {
                while (running) {
                    WatchKey key = watchService.take();
                    Path watchedDir = (Path) key.watchable();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        String name = changed.getFileName().toString();
                        if (isIgnoredGeneratedOutput(watchedDir, name)) {
                            if (verbose) {
                                enqueueMessage("ignored generated output " + name + " [" + event.kind().name() + "]");
                            }
                            continue;
                        }
                        if (name.endsWith(".adoc") || name.endsWith(".svg") || name.endsWith(".css") || name.endsWith(".html")) {
                            Path absolute = watchedDir.resolve(changed);
                            String full = rootDir().relativize(absolute).toString();
                            enqueueMessage("changed " + full + " [" + event.kind().name() + "]");
                            noteChanged(full);
                        } else if (verbose) {
                            enqueueMessage("ignored " + name + " [" + event.kind().name() + "]");
                        }
                    }
                    key.reset();
                }
            } catch (Exception e) {
                if (running) {
                    enqueueMessage("watch stopped: " + e.getMessage());
                }
            }
        });
    }

    private void stopWatcher() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
            watchService = null;
        }
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
    }

    private void openEditor() {
        String editor = System.getenv("VISUAL");
        if (editor == null) {
            editor = System.getenv("EDITOR");
        }
        if (editor == null) {
            enqueueMessage("no editor found — set VISUAL or EDITOR env var");
            return;
        }
        try {
            Path dir = rootDir();
            Path target = dir.resolve(file.getName());
            new ProcessBuilder(editor, ".", target.toString())
                    .directory(dir.toFile())
                    .inheritIO()
                    .start();
            enqueueMessage("opened editor: " + editor + " . " + file.getName());
        } catch (Exception e) {
            enqueueMessage("editor launch failed: " + e.getMessage());
        }
    }

    private void openPresentation() {
        try {
            URI uri = URI.create(currentUrl());
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(uri);
                enqueueMessage("opened " + uri);
                return;
            }
        } catch (Exception e) {
            enqueueMessage("browser open failed: " + e.getMessage());
        }

        try {
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                new ProcessBuilder("open", currentUrl()).start();
            } else if (System.getProperty("os.name").toLowerCase().contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", currentUrl()).start();
            } else {
                new ProcessBuilder("xdg-open", currentUrl()).start();
            }
            enqueueMessage("opened " + currentUrl());
        } catch (Exception e) {
            enqueueMessage("could not open browser: " + e.getMessage());
        }
    }

    private void toggleWatch() {
        if (serving) {
            enqueueMessage("watch is locked while serve is on");
            return;
        }
        if (watchService == null) {
            try {
                watching = true;
                startWatcher();
                requestRender("watch enabled");
            } catch (IOException e) {
                enqueueMessage("watch error: " + e.getMessage());
            }
        } else {
            watching = false;
            stopWatcher();
        }
    }

    private void toggleServe() {
        if (httpServer == null) {
            serving = true;
            if (watchService == null) {
                try {
                    watching = true;
                    startWatcher();
                } catch (IOException e) {
                    enqueueMessage("watch error: " + e.getMessage());
                }
            }
            try {
                startServer();
                openPresentation();
            } catch (IOException e) {
                serving = false;
                enqueueMessage(e.getMessage());
            }
        } else {
            serving = false;
            stopServer();
        }
    }

    private boolean isIgnoredGeneratedOutput(Path watchedDir, String name) {
        // Output lives in outputDir() which is skipped by the watcher, but a stale
        // pre-split .html sitting in the source root should still be ignored.
        return name.endsWith(".html");
    }

    private void requestRender(String reason) {
        scheduleRender(reason);
    }

    private void shutdown() {
        running = false;
        if (pendingRender != null) {
            pendingRender.cancel(false);
        }
        stopWatcher();
        stopServer();
        scheduler.shutdownNow();
    }

    private final class DashboardApp extends ToolkitApp {
        @Override
        protected TuiConfig configure() {
            return TuiConfig.builder()
                    .tickRate(Duration.ofMillis(250))
                    .build();
        }

        @Override
        protected Element render() {
            var root = dock()
                    .top(statusPanel(), Constraint.length(11))
                    .left(changesPanel(), Constraint.percentage(34))
                    .bottom(shortcutsPanel(), Constraint.length(showHelp ? 3 : 0))
                    .center(eventsPanel())
                    .focusable()
                    .onKeyEvent(event -> {
                        if (event.isQuit() || event.isCharIgnoreCase('q')) {
                            quit();
                            return EventResult.HANDLED;
                        }
                        if (event.isCharIgnoreCase('r')) {
                            requestRender("manual refresh");
                            return EventResult.HANDLED;
                        }
                        if (event.isCharIgnoreCase('w')) {
                            toggleWatch();
                            return EventResult.HANDLED;
                        }
                        if (event.isCharIgnoreCase('s')) {
                            toggleServe();
                            return EventResult.HANDLED;
                        }
                        if (event.isCharIgnoreCase('o')) {
                            openPresentation();
                            return EventResult.HANDLED;
                        }
                        if (event.isCharIgnoreCase('h')) {
                            showHelp = !showHelp;
                            return EventResult.HANDLED;
                        }
                        if (event.isCharIgnoreCase('e')) {
                            openEditor();
                            return EventResult.HANDLED;
                        }
                        if (event.isCharIgnoreCase('c')) {
                            statusMessages.clear();
                            return EventResult.HANDLED;
                        }
                        return EventResult.UNHANDLED;
                    });

            return root;
        }
    }

    private Element statusPanel() {
        String renderInfo = renderCount == 0
                ? "Waiting for first render..."
                : String.format("#%d  %s  (avg %s)  %s", renderCount, formatNanos(lastRenderNanos),
                        formatNanos(totalRenderNanos / renderCount), lastRenderReason);
        return panel("Status",
                column(
                        row(text("File").gray(), spacer(), text(file.getName()).cyan().bold()),
                        row(text("Output").gray(), spacer(), text(outputName()).green().bold()),
                        row(text("Out dir").gray(), spacer(), text(rootDir().relativize(outputDir()).toString()).green()),
                        row(text("URL").gray(), spacer(), text(currentUrl()).yellow()),
                        row(text("Mode").gray(), spacer(), text(modeText()).magenta()),
                        row(text("Render").gray(), spacer(), text(renderInfo).white()),
                        row(text("Last").gray(), spacer(), text(lastRenderSummary).green())
                )
        ).rounded().borderColor(Color.CYAN).padding(1);
    }

    private Element shortcutsPanel() {
        Element body = showHelp
                ? row(
                        markupText("[bold white]r[/] [cyan]render[/]"),
                        markupText("[bold white]w[/] [cyan]watch[/]"),
                        markupText("[bold white]s[/] [cyan]serve[/]"),
                        markupText("[bold white]o[/] [cyan]open[/]"),
                        markupText("[bold white]e[/] [cyan]editor[/]"),
                        markupText("[bold white]c[/] [cyan]clear log[/]"),
                        markupText("[bold white]h[/] [cyan]help[/]"),
                        markupText("[bold white]q[/] [cyan]quit[/]")
                ).spacing(1)
                : text("Press h to show shortcuts").gray();

        return panel("Shortcuts", body)
                .rounded().borderColor(Color.MAGENTA).padding(0).fit();
    }

    private Element changesPanel() {
        List<String> changes = lastChangedFiles;
        List<Element> lines = new ArrayList<>();
        if (changes.isEmpty()) {
            lines.add(text("No slide changes yet.").gray());
        } else {
            for (String change : changes) {
                lines.add(text("• " + change).yellow());
            }
        }
        return panel("Last changed files", column(lines.toArray(Element[]::new)))
                .rounded().borderColor(Color.GREEN).padding(1);
    }

    private Element eventsPanel() {
        var snapshot = new ArrayList<>(statusMessages);
        List<Element> lines = new ArrayList<>();
        if (snapshot.isEmpty()) {
            lines.add(text("Waiting for slide drama...").gray());
        } else {
            int start = Math.max(0, snapshot.size() - 8);
            for (int i = start; i < snapshot.size(); i++) {
                lines.add(text(snapshot.get(i)).white());
            }
        }
        return panel("Recent events", column(lines.toArray(Element[]::new)))
                .rounded().borderColor(Color.YELLOW).padding(1).fill();
    }


    private String modeText() {
        return (watching ? "[ON]" : "[OFF]") + " watch   " + (serving ? "[ON]" : "[OFF]") + " serve   " + (interactive ? "[ON]" : "[OFF]") + " tui";
    }

    private static String formatNanos(long nanos) {
        if (nanos <= 0) {
            return "—";
        }
        if (nanos < 1_000_000) {
            return String.format("%.0fµs", nanos / 1_000.0);
        }
        if (nanos < 1_000_000_000) {
            return String.format("%.1fms", nanos / 1_000_000.0);
        }
        return String.format("%.2fs", nanos / 1_000_000_000.0);
    }

    @Command(name = "init", mixinStandardHelpOptions = true, description = "Spin up a starter lazyslide deck")
    static final class Init implements Callable<Integer> {

        @ParentCommand
        private lazyslide parent;

        @Parameters(index = "0", arity = "0..1", defaultValue = ".", description = "Where the starter deck should be created")
        private Path targetDir;

        @Option(names = "--title", defaultValue = "My Presentation", description = "Title for the starter deck")
        private String title;

        @Option(names = "--author", description = "Author name for the starter deck (defaults to the current user)")
        private String author;

        @Option(names = "--force", description = "Overwrite starter files if they already exist")
        private boolean force;

        @Override
        public Integer call() throws Exception {
            if (author == null || author.isBlank()) {
                author = System.getProperty("user.name", "Your Name");
            }

            Path root = targetDir.toAbsolutePath().normalize();
            List<TemplateFile> files = List.of(
                    new TemplateFile("README.adoc", readme(title, author)),
                    new TemplateFile(".gitignore", gitignore()),
                    new TemplateFile("index.adoc", indexAdoc(title, author)),
                    new TemplateFile("docinfo-footer.html", docinfoFooter()),
                    new TemplateFile("css/presentation.css", presentationCss()),
                    new TemplateFile("slides/title.adoc", titleSlide()),
                    new TemplateFile("slides/story.adoc", storySlide()),
                    new TemplateFile("slides/closing.adoc", closingSlide()),
                    new TemplateFile("images/.gitkeep", "")
            );

            for (TemplateFile template : files) {
                Path file = root.resolve(template.relativePath());
                if (Files.exists(file) && !force) {
                    throw new IOException("Won't overwrite existing file: " + file + " (use --force if you really want to replace starter files)");
                }
            }

            for (TemplateFile template : files) {
                Path file = root.resolve(template.relativePath());
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, template.contents());
            }

            System.out.println("lazyslide: starter deck created in " + root);
            System.out.println();
            System.out.println("Next steps:");
            System.out.println("  cd " + root);
            System.out.println("  lazyslide serve");
            System.out.println();
            System.out.println("If `lazyslide` is not installed globally yet, run the same command through the JBang source path instead.");
            System.out.println("For example from this repo: ./jbang lazyslide/lazyslide.java serve");
            if (parent != null) {
                parent.enqueueMessage("starter deck created at " + root);
            }
            return 0;
        }

        private static String readme(String title, String author) {
            return "= " + title + "\n"
                    + ":author: " + author + "\n"
                    + ":icons: font\n"
                    + ":source-highlighter: highlightjs\n"
                    + ":highlightjs-theme: https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/atom-one-dark.min.css\n"
                    + ":highlightjs-languages: bash\n"
                    + ":revealjs_theme: black\n"
                    + ":revealjs_transition: slide\n"
                    + ":revealjs_hash: true\n"
                    + ":revealjs_history: true\n\n"
                    + "[%notitle]\n"
                    + "== {doctitle}\n\n"
                    + "[.lead]\n"
                    + "A tiny slide deck and quickstart for your new lazyslide project.\n\n"
                    + "== Render once\n\n"
                    + "[source,bash]\n"
                    + "----\n"
                    + "lazyslide\n"
                    + "----\n\n"
                    + "This renders `index.adoc` to `public/index.html`.\n\n"
                    + "== Live preview\n\n"
                    + "[source,bash]\n"
                    + "----\n"
                    + "lazyslide serve\n"
                    + "----\n\n"
                    + "That enables rendering, watching, serving, and the terminal dashboard.\n\n"
                    + "== Project layout\n\n"
                    + "- `index.adoc` — deck entrypoint\n"
                    + "- `slides/` — slide fragments\n"
                    + "- `css/presentation.css` — reveal.js custom styling\n"
                    + "- `images/` — local images and diagrams\n"
                    + "- `docinfo-footer.html` — browser auto-reload helper\n\n"
                    + "== No global `lazyslide` yet?\n\n"
                    + "No problem — just run the same command through the JBang source path for `lazyslide.java`.\n\n"
                    + "== Next steps\n\n"
                    + "- Replace the starter slides\n"
                    + "- Add your own styling and assets\n"
                    + "- Publish the generated `public/` output\n";
        }

        private static String gitignore() {
            return "public/\n"
                    + ".asciidoctor/\n";
        }

        private static String indexAdoc(String title, String author) {
            return "= " + title + "\n"
                    + ":author: " + author + "\n"
                    + ":icons: font\n"
                    + ":imagesdir: images\n"
                    + ":source-highlighter: highlightjs\n"
                    + ":highlightjs-theme: https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/atom-one-dark.min.css\n"
                    + ":highlightjs-languages: java, css, bash\n"
                    + ":revealjs_theme: black\n"
                    + ":customcss: css/presentation.css\n"
                    + ":revealjs_width: 1280\n"
                    + ":revealjs_height: 720\n"
                    + ":revealjs_slideNumber: c/t\n"
                    + ":revealjs_transition: slide\n"
                    + ":revealjs_transitionSpeed: fast\n"
                    + ":revealjs_hash: true\n"
                    + ":revealjs_history: true\n"
                    + ":docinfo: shared-footer\n"
                    + "// :revealjs_showNotes: true\n\n"
                    + "include::slides/title.adoc[]\n\n"
                    + "include::slides/story.adoc[]\n\n"
                    + "include::slides/closing.adoc[]\n";
        }

        private static String docinfoFooter() {
            return "<script>\n"
                    + "(function() {\n"
                    + "  var lastModified = null;\n"
                    + "  var interval = 1000;\n"
                    + "  function check() {\n"
                    + "    fetch(window.location.href, { method: 'HEAD', cache: 'no-store' })\n"
                    + "      .then(function(r) {\n"
                    + "        var lm = r.headers.get('last-modified');\n"
                    + "        if (lastModified && lm !== lastModified) {\n"
                    + "          window.location.reload();\n"
                    + "        }\n"
                    + "        lastModified = lm;\n"
                    + "      })\n"
                    + "      .catch(function() {});\n"
                    + "  }\n"
                    + "  setInterval(check, interval);\n"
                    + "})();\n"
                    + "</script>\n";
        }

        private static String presentationCss() {
            return ".reveal .slides section .lead {\n"
                    + "  font-size: 1.6rem;\n"
                    + "  color: #8bd5ff;\n"
                    + "}\n\n"
                    + ".reveal .slides section .small {\n"
                    + "  font-size: 1.0rem;\n"
                    + "  opacity: 0.8;\n"
                    + "}\n\n"
                    + ".reveal pre code {\n"
                    + "  max-height: 28em;\n"
                    + "}\n\n"
                    + ".reveal .slides section.left {\n"
                    + "  text-align: left;\n"
                    + "}\n";
        }

        private static String titleSlide() {
            return "[%notitle]\n"
                    + "== {doctitle}\n\n"
                    + "[.lead]\n"
                    + "{author}\n\n"
                    + "[.small]\n"
                    + "AsciiDoc slides, reveal.js output, and a relaxed little `lazyslide` live-preview loop.\n";
        }

        private static String storySlide() {
            return "[.left]\n"
                    + "== Why lazyslide?\n\n"
                    + "- Write slides in AsciiDoc\n"
                    + "- Render straight to reveal.js\n"
                    + "- Preview locally with auto-refresh\n"
                    + "- Keep slides next to your code snippets and images\n\n"
                    + "=== Live loop\n\n"
                    + "[source,bash]\n"
                    + "----\n"
                    + "lazyslide serve\n"
                    + "----\n\n"
                    + "=== Speaker notes\n\n"
                    + "[NOTE.speaker]\n"
                    + "--\n"
                    + "Use this starter deck as your baseline: split slides into files, keep assets local, and iterate in watch mode.\n"
                    + "--\n";
        }

        private static String closingSlide() {
            return "[%notitle, background-color=\"#102a43\"]\n"
                    + "== Thanks\n\n"
                    + "[.lead]\n"
                    + "Now replace this slide with your own closing CTA.\n\n"
                    + "- Share the repo\n"
                    + "- Publish the slides\n"
                    + "- Invite questions\n";
        }
    }

    @Name("emoji")
    public static class EmojiMacro extends InlineMacroProcessor {
        @Override
        public PhraseNode process(StructuralNode parent, String target, Map<String, Object> attributes) {
            String shortcode = target.replace('-', '_').replace(' ', '_').toLowerCase();
            String emoji = Emoji.emojis().get(shortcode);
            if (emoji == null) {
                emoji = ":" + target + ":";
            }
            return createPhraseNode(parent, "quoted", emoji);
        }
    }

    record TemplateFile(String relativePath, String contents) {
    }
}
