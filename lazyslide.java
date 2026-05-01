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
//DEPS dev.tamboui:tamboui-toolkit:0.2.0
//DEPS dev.tamboui:tamboui-jline3-backend:0.2.0
//DEPS io.vertx:vertx-web:4.5.13
//FILES favicon.png

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;

import dev.tamboui.toolkit.app.InlineApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.InlineTuiConfig;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.markupText;
import static dev.tamboui.toolkit.Toolkit.row;
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

    @Option(names = "-D", description = "Set an AsciiDoc attribute (e.g. -Dicons=font)", mapFallbackValue = "")
    Map<String, String> cliAttributes = new java.util.LinkedHashMap<>();

    @Option(names = "-R", description = "Set a reveal.js attribute (e.g. -Rtheme=white sets revealjs_theme)", mapFallbackValue = "")
    Map<String, String> cliRevealAttributes = new java.util.LinkedHashMap<>();

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
    boolean pdfExport;

    /** Default directory names to look for when no argument is given. */
    private static final List<String> DEFAULT_INPUT_DIRS = List.of("slides", "docs");

    /** Directories and files that are never mirrored into the output dir. */
    private static final List<String> MIRROR_EXCLUDED = List.of("slides");

    /** File extensions that are mirrored as loose assets from the source root. */
    private static final Set<String> MIRRORED_FILE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico",
            "css", "js", "json",
            "woff", "woff2", "ttf", "otf", "eot",
            "mp4", "webm", "ogg", "mp3",
            "pdf"
    );

    static String fileExtension(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private volatile Asciidoctor asciidoctor;
    private WatchService watchService;
    private Vertx vertx;
    private HttpServer httpServer;
    private final Set<ServerWebSocket> wsClients = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("lazyslide-scheduler", 0).factory());
    private volatile DashboardApp tuiApp;
    private volatile String[] pendingEditorCmd = null;
    private final ConcurrentLinkedQueue<String> pendingMarkup = new ConcurrentLinkedQueue<>();
    private final LinkedHashSet<String> pendingChangedFiles = new LinkedHashSet<>();
    /** Guards {@code pendingChangedFiles} and {@code pendingRender}.
     *  Lock ordering: never hold {@code this} (doRender) when acquiring stateLock. */
    private final Object stateLock = new Object();

    private ScheduledFuture<?> pendingRender; // guarded by stateLock
    private volatile boolean running;

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
    void resolveInput() throws IOException {
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
            // If the directory contains an index.adoc, use it directly (user is in control)
            Path indexFile = path.resolve("index.adoc");
            if (Files.isRegularFile(indexFile)) {
                file = indexFile.toFile();
                // Stay in single-file mode — inputDir remains null
            } else {
                inputDir = path;
                List<Path> adocFiles = scanAdocFiles(path);
                if (adocFiles.isEmpty()) {
                    throw new IOException("No .adoc files found in " + path);
                }
                virtualIndexContent = generateVirtualIndex(path, adocFiles);
            }
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
    List<Path> scanAdocFiles(Path dir) throws IOException {
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
    String generateVirtualIndex(Path dir, List<Path> adocFiles) {
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
                enqueueMarkup("loaded [bold cyan]" + attrs.size() + "[/] attributes from [yellow]" + filename + "[/]");
                return attrs;
            }
        }
        return Map.of();
    }

    /**
     * Parse an AsciiDoc file for attribute definitions (:key: value).
     */
    Map<String, String> parseAttributesFile(Path file) {
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
            enqueueMarkup("[red]failed to read[/] [yellow]" + file.getFileName() + "[/]: " + e.getMessage());
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

        // 3. CLI attributes — hard-set, override everything
        for (var entry : cliAttributes.entrySet()) {
            builder.attribute(entry.getKey(), entry.getValue());
        }
        for (var entry : cliRevealAttributes.entrySet()) {
            builder.attribute("revealjs_" + entry.getKey(), entry.getValue());
        }

        // 4. Internal attributes — hard-set, not overridable
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
        enqueueMarkup("[yellow]⏳[/] warming up Asciidoctor...");
        long start = System.nanoTime();
        asciidoctor = Asciidoctor.Factory.create();
        asciidoctor.registerLogHandler(new LogHandler() {
            @Override
            public void log(LogRecord logRecord) {
                enqueueMarkup("[gray]asciidoctor:[/] " + logRecord.getMessage());
            }
        });
        asciidoctor.requireLibrary("asciidoctor-revealjs");
        asciidoctor.requireLibrary("asciidoctor-diagram");
        asciidoctor.requireLibrary("asciidoctor-chart");
        asciidoctor.javaExtensionRegistry().inlineMacro(new EmojiMacro());
        // Single volatile write publishes the fully initialized Asciidoctor instance
        long took = System.nanoTime() - start;
        enqueueMarkup(String.format("[green]✔[/] Asciidoctor ready in [bold]%s[/]", formatNanos(took)));
        scheduleRender("startup");
    }

    int execute() throws Exception {
        running = true;

        // Route uncaught exceptions to the message log instead of stderr (corrupts TUI)
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> enqueueMarkup("[red]error[/] [gray][" + t.getName() + "][/]: " + e.getMessage()));

        // Resolve input: if it's a directory, generate virtual index
        resolveInput();

        // Serve mode: use a temp directory for output (don't pollute source tree)
        if (serving) {
            serveTempDir = Files.createTempDirectory("lazyslide_");
            serveTempDir.toFile().deleteOnExit();
            enqueueMarkup("output: [yellow]" + serveTempDir + "[/]");
        }

        if (!interactive) {
            // Non-interactive: init synchronously, render
            System.out.println("lazyslide: warming up Asciidoctor...");
            if (inputDir != null) {
                System.out.println("lazyslide: scanning directory " + inputDir);
                System.out.println("lazyslide: generated virtual index with " + virtualIndexContent.lines().filter(l -> l.startsWith("include::")).count() + " includes");
            }
            initAsciidoctor();
            doRender("startup");
            if (pdfExport) {
                exportPdfSync();
            }
            if (open) {
                openPresentation();
            }
            if (serving || watching) {
                // Headless mode: start server/watcher and block until interrupted
                try {
                    if (serving) startServer();
                    if (watching) startWatcher();
                    Thread.currentThread().join();
                } catch (InterruptedException ignored) {
                } finally {
                    shutdown();
                }
            }
            return 0;
        }

        // Interactive: start TUI immediately, init Asciidoctor in background
        try {
            if (inputDir != null) {
                enqueueMarkup("directory mode: [yellow]" + inputDir + "[/]");
                enqueueMarkup("virtual index: [bold cyan]" + virtualIndexContent.lines().filter(l -> l.startsWith("include::")).count() + "[/] includes");
            }
            if (serving) {
                startServer();
            }
            if (watching) {
                startWatcher();
            }

            Thread.ofVirtual().name("lazyslide-init").start(this::initAsciidoctor);

            while (true) {
                var app = new DashboardApp();
                tuiApp = app;
                app.run();
                if (pendingEditorCmd == null) break;
                // TUI exited for editor launch — run editor then restart TUI
                String[] cmd = pendingEditorCmd;
                pendingEditorCmd = null;
                try {
                    new ProcessBuilder(cmd)
                            .directory(rootDir().toFile())
                            .inheritIO()
                            .start()
                            .waitFor();
                } catch (Exception e) {
                    pendingMarkup.add("[red]editor launch failed:[/] " + e.getMessage());
                }
            }
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

        @Option(names = {"--pdf"}, description = "Also export a PDF via headless Chrome")
        private boolean pdf;

        @Override
        public Integer call() throws Exception {
            parent.file = file;
            parent.open = open;
            parent.interactive = false;
            parent.watching = false;
            parent.serving = false;
            parent.pdfExport = pdf;
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
            parent.interactive = System.console() != null;
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
            parent.interactive = System.console() != null;
            parent.watching = true;
            parent.serving = false;
            return parent.execute();
        }
    }

    Path rootDir() {
        if (inputDir != null) {
            return inputDir;
        }
        return file.getAbsoluteFile().getParentFile().toPath();
    }

    String outputName() {
        if (inputDir != null) {
            // Directory mode always produces index.html
            return "index.html";
        }
        return file.getName().replaceFirst("\\.adoc$", ".html");
    }

    private Path serveTempDir;

    Path outputDir() {
        if (serving && serveTempDir != null) {
            return serveTempDir;
        }
        Path candidate = Path.of(outputDirName);
        // Resolve relative to CWD, not to the input directory
        return candidate.toAbsolutePath().normalize();
    }

    private Path outputFile() {
        return outputDir().resolve(outputName());
    }

    private String currentUrl() {
        return serving ? "http://localhost:" + port + "/" + outputName() : outputFile().toUri().toString();
    }

    private void enqueueMessage(String msg) {
        String line = timestamp() + " " + msg;
        if (tuiApp != null && tuiApp.isReady()) {
            tuiApp.log(line);
        } else if (interactive) {
            pendingMarkup.add("[white]" + line + "[/]");
        } else {
            System.out.println(line);
        }
    }

    private void enqueueMarkup(String markup) {
        String line = "[gray]" + timestamp() + "[/] " + markup;
        if (tuiApp != null && tuiApp.isReady()) {
            tuiApp.logMarkup(line);
        } else if (interactive) {
            pendingMarkup.add(line);
        } else {
            // strip markup for non-interactive
            System.out.println(timestamp() + " " + markup.replaceAll("\\[/?[^]]*]", ""));
        }
    }

    private void flushPendingMarkup() {
        String line;
        while ((line = pendingMarkup.poll()) != null) {
            if (tuiApp != null) {
                tuiApp.logMarkup(line);
            }
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
        if (asciidoctor == null) {
            enqueueMarkup("[yellow]⏳[/] render skipped: Asciidoctor still loading...");
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
            enqueueMarkup("[red]failed to create output dir[/] [yellow]" + outDir + "[/]: " + e.getMessage());
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
                            enqueueMarkup("[red]asset sync failed[/] [yellow]" + name + "[/]: " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            enqueueMarkup("[red]failed to list source dirs:[/] " + e.getMessage());
        }

        // Mirror loose asset files (images, fonts, css, js, etc.) from source root into output
        try (var entries = Files.list(rootDir())) {
            entries.filter(Files::isRegularFile)
                    .filter(p -> MIRRORED_FILE_EXTENSIONS.contains(fileExtension(p)))
                    .forEach(src -> {
                        Path dest = outDir.resolve(src.getFileName().toString());
                        try {
                            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        } catch (IOException e) {
                            enqueueMarkup("[red]asset copy failed[/] [yellow]" + src.getFileName() + "[/]: " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            enqueueMarkup("[red]failed to list source files:[/] " + e.getMessage());
        }

        // In directory mode, regenerate the virtual index to pick up new/deleted files
        if (inputDir != null && !"startup".equals(reason)) {
            try {
                List<Path> adocFiles = scanAdocFiles(inputDir);
                if (!adocFiles.isEmpty()) {
                    String newContent = generateVirtualIndex(inputDir, adocFiles);
                    if (!newContent.equals(virtualIndexContent)) {
                        virtualIndexContent = newContent;
                        enqueueMarkup("virtual index updated: [bold cyan]" + adocFiles.size() + "[/] files");
                    }
                }
            } catch (IOException e) {
                enqueueMarkup("[red]failed to rescan directory:[/] " + e.getMessage());
            }
        }

        if (interactive) {
            enqueueMarkup("[yellow]↻[/] rendering: [white]" + reason + "[/]" + (changed.isEmpty() ? "" : " [gray]|[/] [cyan]" + String.join(", ", changed) + "[/]"));
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
                enqueueMarkup("[red]failed to write[/] [yellow]index.html[/]: " + e.getMessage());
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
            enqueueMarkup("[green]✔[/] [bold]" + lastRenderSummary + "[/]");
        } else {
            System.out.printf("lazyslide: done in %.2fs (%s)%n", took / 1_000_000_000.0, file);
        }

        // Push reload to connected browsers
        broadcastReload();
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
        synchronized (stateLock) {
            if (pendingRender != null) {
                pendingRender.cancel(false);
            }
            pendingRender = scheduler.schedule(() -> doRender(reason), 180, TimeUnit.MILLISECONDS);
        }
    }

    private static final String LIVE_RELOAD_SCRIPT = """
            <script>
            (function() {
              function connect() {
                var ws = new WebSocket('ws://' + location.host + '/livereload');
                ws.onmessage = function(e) {
                  var msg = JSON.parse(e.data);
                  if (msg.command === 'reload') location.reload();
                };
                ws.onclose = function() { setTimeout(connect, 2000); };
                ws.onerror = function() { ws.close(); };
              }
              connect();
            })();
            </script>
            """;



    static String injectLiveReload(String html) {
        int i = html.lastIndexOf("</body>");
        if (i >= 0) {
            return html.substring(0, i) + LIVE_RELOAD_SCRIPT + html.substring(i);
        }
        return html + LIVE_RELOAD_SCRIPT;
    }

    private void broadcastReload() {
        if (wsClients.isEmpty()) return;
        enqueueMarkup("[magenta]↻[/] live reload: notifying [bold]" + wsClients.size() + "[/] browser" + (wsClients.size() == 1 ? "" : "s"));
        for (ServerWebSocket ws : wsClients) {
            try {
                ws.writeTextMessage("{\"command\":\"reload\"}");
            } catch (Exception e) {
                wsClients.remove(ws);
            }
        }
    }

    private void startServer() throws IOException {
        if (httpServer != null) {
            return;
        }
        try {
            Files.createDirectories(outputDir());
            Path outDir = outputDir().toRealPath();
            // Suppress noisy Netty DNS warning on macOS
            java.util.logging.Logger.getLogger("io.netty").setLevel(java.util.logging.Level.SEVERE);
            vertx = Vertx.vertx();

            httpServer = vertx.createHttpServer();
            httpServer.webSocketHandler(ws -> {
                if ("/livereload".equals(ws.path())) {
                    ws.accept();
                    wsClients.add(ws);
                    // Connection count shown in status bar, no per-connect log spam
                    ws.closeHandler(v -> {
                        wsClients.remove(ws);
                    });
                    ws.exceptionHandler(err -> {
                        wsClients.remove(ws);
                    });
                } else {
                    ws.reject();
                }
            });

            httpServer.requestHandler(req -> {
                String path = req.path();
                if ("/".equals(path)) path = "/" + outputName();

                // Serve favicon from classpath
                if ("/favicon.ico".equals(path) || "/favicon.png".equals(path)) {
                    try (var in = lazyslide.class.getResourceAsStream("/favicon.png")) {
                        if (in != null) {
                            byte[] bytes = in.readAllBytes();
                            req.response()
                                    .putHeader("Content-Type", "image/png")
                                    .putHeader("Cache-Control", "max-age=86400")
                                    .end(io.vertx.core.buffer.Buffer.buffer(bytes));
                            return;
                        }
                    } catch (IOException ignored) {}
                    req.response().setStatusCode(404).end("Not found");
                    return;
                }

                Path filePath;
                try {
                    // toRealPath() resolves symlinks and canonicalizes — safe against traversal
                    filePath = outDir.resolve(path.substring(1)).toRealPath();
                } catch (IOException ignored) {
                    enqueueMarkup("[red]404[/] [gray]" + req.method() + "[/] " + req.path());
                    req.response().setStatusCode(404).end("Not found");
                    return;
                }
                if (!filePath.startsWith(outDir)) {
                    enqueueMarkup("[red]403[/] [gray]" + req.method() + "[/] " + req.path() + " [red]path traversal blocked[/]");
                    req.response().setStatusCode(403).end("Forbidden");
                    return;
                }
                try {
                    String mime = Files.probeContentType(filePath);
                    if (mime == null) mime = "application/octet-stream";

                    HttpServerResponse resp = req.response();
                    resp.putHeader("Cache-Control", "no-store");

                    if (mime.startsWith("text/html")) {
                        String html = injectLiveReload(Files.readString(filePath));
                        byte[] body = html.getBytes(StandardCharsets.UTF_8);
                        resp.putHeader("Content-Type", "text/html; charset=utf-8");
                        resp.end(io.vertx.core.buffer.Buffer.buffer(body));
                        enqueueMarkup("[green]200[/] [gray]" + req.method() + "[/] " + req.path() + " [cyan](" + body.length + "b)[/]");
                    } else {
                        long size = Files.size(filePath);
                        resp.putHeader("Content-Type", mime);
                        resp.sendFile(filePath.toString());
                        enqueueMarkup("[green]200[/] [gray]" + req.method() + "[/] " + req.path() + " [dim]" + mime + "[/] [cyan](" + size + "b)[/]");
                    }
                } catch (IOException e) {
                    enqueueMarkup("[red]500[/] [gray]" + req.method() + "[/] " + req.path() + " [red]" + e.getMessage() + "[/]");
                    req.response().setStatusCode(500).end(e.getMessage());
                }
            });

            httpServer.listen(port).toCompletionStage().toCompletableFuture().get();
            port = httpServer.actualPort();
            enqueueMarkup("[green]▶[/] serving at [bold yellow]" + currentUrl() + "[/]");

        } catch (Exception e) {
            stopServer();
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof java.net.BindException) {
                    throw new IOException(String.format(
                        "Port %d is already in use. Stop it or use `--port <port>` to specify a different port.",
                        port));
                }
                cause = cause.getCause();
            }
            throw new IOException(String.format("failed to start server on port %d — %s", port, e.getMessage()), e);
        }
    }

    private void stopServer() {
        wsClients.clear();
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
        if (vertx != null) {
            vertx.close();
            vertx = null;
        }
        enqueueMarkup("[gray]server stopped[/]");
    }

    private void startWatcher() throws IOException {
        if (watchService != null) {
            return;
        }
        watchService = FileSystems.getDefault().newWatchService();
        registerRecursive(rootDir());
        enqueueMarkup("watching [yellow]" + rootDir() + "[/]");
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
                                enqueueMarkup("[dim]ignored[/] [gray]" + name + " [" + event.kind().name() + "][/]");
                            }
                            continue;
                        }
                        // Register newly created directories so their contents are watched
                        Path absolute = watchedDir.resolve(changed);
                        if (event.kind() == ENTRY_CREATE && Files.isDirectory(absolute)) {
                            try {
                                registerRecursive(absolute);
                                String full = rootDir().relativize(absolute).toString();
                                enqueueMarkup("[yellow]+[/] watching new dir [cyan]" + full + "[/]");
                                noteChanged(full);
                            } catch (IOException e) {
                                enqueueMarkup("[red]failed to watch new dir:[/] " + e.getMessage());
                            }
                        } else if (name.endsWith(".adoc") || name.endsWith(".svg") || name.endsWith(".css") || name.endsWith(".html")) {
                            String full = rootDir().relativize(absolute).toString();
                            enqueueMarkup("[yellow]•[/] changed [cyan]" + full + "[/] [gray][" + event.kind().name() + "][/]");
                            noteChanged(full);
                        } else if (verbose) {
                            enqueueMarkup("[dim]ignored[/] [gray]" + name + " [" + event.kind().name() + "][/]");
                        }
                    }
                    key.reset();
                }
            } catch (Exception e) {
                if (running) {
                    enqueueMarkup("[red]watch stopped:[/] " + e.getMessage());
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

    /** GUI editors to auto-detect (return immediately, don't need terminal). */
    private static final List<String[]> KNOWN_EDITORS = List.of(
            new String[]{"code",    "Visual Studio Code"},
            new String[]{"cursor",  "Cursor"},
            new String[]{"idea",    "IntelliJ IDEA"},
            new String[]{"zed",     "Zed"},
            new String[]{"subl",    "Sublime Text"},
            new String[]{"atom",    "Atom"},
            new String[]{"mate",    "TextMate"},
            new String[]{"nova",    "Nova"}
    );

    private static boolean isInPath(String cmd) {
        try {
            String which = System.getProperty("os.name", "").toLowerCase().contains("win") ? "where" : "which";
            return new ProcessBuilder(which, cmd)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Set<String> runningProcessNames() {
        try {
            return ProcessHandle.allProcesses()
                    .map(p -> p.info().command().orElse(""))
                    .map(c -> Path.of(c).getFileName().toString().toLowerCase())
                    .collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            return Set.of();
        }
    }

    private void showSource() {
        if (tuiApp == null) return;
        tuiApp.logMarkup("[dim]─── source " + "─".repeat(200) + "[/]");
        String source;
        if (inputDir != null && virtualIndexContent != null) {
            tuiApp.logMarkup("Virtual index [gray](directory mode:[/] [yellow]" + inputDir + "[/][gray])[/]");
            source = virtualIndexContent;
        } else {
            tuiApp.logMarkup("File: [yellow]" + file.getAbsolutePath() + "[/]");
            try {
                source = Files.readString(file.toPath());
            } catch (IOException e) {
                tuiApp.logMarkup("[red]failed to read:[/] " + e.getMessage());
                return;
            }
        }
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            tuiApp.logMarkup(String.format("[dim]%4d │[/] %s", i + 1, lines[i]));
        }
        tuiApp.logMarkup("[dim]" + "─".repeat(200) + "[/]");
    }

    private void openEditor() {
        String editor = System.getenv("VISUAL");
        String source = "VISUAL";
        boolean terminal = false;

        if (editor == null) {
            editor = System.getenv("EDITOR");
            source = "EDITOR";
            terminal = editor != null; // EDITOR implies terminal editor
        }

        // Auto-detect: only GUI editors, prefer running ones
        if (editor == null) {
            Set<String> running = runningProcessNames();
            for (String[] entry : KNOWN_EDITORS) {
                if (running.contains(entry[0]) && isInPath(entry[0])) {
                    editor = entry[0];
                    source = "found " + entry[1] + " running";
                    break;
                }
            }
            if (editor == null) {
                for (String[] entry : KNOWN_EDITORS) {
                    if (isInPath(entry[0])) {
                        editor = entry[0];
                        source = "found " + entry[1] + " in PATH";
                        break;
                    }
                }
            }
        }

        if (editor == null) {
            var found = new ArrayList<String>();
            for (String[] entry : KNOWN_EDITORS) found.add(entry[0]);
            enqueueMarkup("[red]no GUI editor found[/] — set [bold]VISUAL[/] or [bold]EDITOR[/], or install one of: [cyan]" + String.join(", ", found) + "[/]");
            return;
        }

        Path dir = rootDir();
        Path target = dir.resolve(file.getName());

        if (terminal) {
            // Terminal editor (EDITOR=vim etc.) — quit TUI, run editor, restart TUI
            pendingMarkup.add("opened editor: [bold cyan]" + editor + "[/] [gray](" + source + ")[/]");
            pendingEditorCmd = new String[]{editor, target.toString()};
            if (tuiApp != null) tuiApp.stop();
        } else {
            // GUI editor — launch and continue
            try {
                new ProcessBuilder(editor, ".", target.toString())
                        .directory(dir.toFile())
                        .start();
                enqueueMarkup("opened editor: [bold cyan]" + editor + "[/] [gray](" + source + ")[/]");
                if (!source.equals("VISUAL")) {
                    enqueueMarkup("[dim]tip: set [bold]VISUAL[/][dim] env var to pick your preferred editor[/]");
                }
            } catch (Exception e) {
                enqueueMarkup("[red]editor launch failed:[/] " + e.getMessage());
            }
        }
    }

    private void openPresentation() {
        try {
            URI uri = URI.create(currentUrl());
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(uri);
                enqueueMarkup("opened [yellow]" + uri + "[/]");
                return;
            }
        } catch (Exception e) {
            enqueueMarkup("[red]browser open failed:[/] " + e.getMessage());
        }

        try {
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                new ProcessBuilder("open", currentUrl()).start();
            } else if (System.getProperty("os.name").toLowerCase().contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", currentUrl()).start();
            } else {
                new ProcessBuilder("xdg-open", currentUrl()).start();
            }
            enqueueMarkup("opened [yellow]" + currentUrl() + "[/]");
        } catch (Exception e) {
            enqueueMarkup("[red]could not open browser:[/] " + e.getMessage());
        }
    }

    /**
     * Find a Chrome/Chromium binary on this system. Returns null if none found.
     */
    private static String findChrome() {
        // Well-known paths in order of preference
        List<String> candidates = List.of(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
                "google-chrome",
                "google-chrome-stable",
                "chromium",
                "chromium-browser"
        );
        for (String c : candidates) {
            Path p = Path.of(c);
            if (p.isAbsolute() && Files.isExecutable(p)) return c;
            if (!p.isAbsolute() && isInPath(c)) return c;
        }
        return null;
    }

    /**
     * Export the currently rendered deck to PDF using headless Chrome.
     * Requires the HTTP server to be running so Chrome can fetch the ?print-pdf URL.
     */
    private void exportPdf() {
        if (!serving) {
            enqueueMarkup("[yellow]pdf export requires serve mode[/]");
            return;
        }
        String chrome = findChrome();
        if (chrome == null) {
            enqueueMarkup("[red]no Chrome/Chromium found[/] — install Google Chrome or Chromium for PDF export");
            return;
        }
        String pdfName = outputName().replaceFirst("\\.html$", ".pdf");
        Path pdfFile = rootDir().resolve(pdfName);
        String url = "http://localhost:" + port + "/" + outputName() + "?print-pdf";
        enqueueMarkup("[yellow]⏳[/] exporting PDF via headless Chrome...");
        Thread.ofVirtual().name("lazyslide-pdf-export").start(() -> {
            try {
                Process proc = new ProcessBuilder(
                        chrome,
                        "--headless=new",
                        "--no-pdf-header-footer",
                        "--print-to-pdf=" + pdfFile.toAbsolutePath(),
                        "--disable-gpu",
                        "--no-sandbox",
                        "--run-all-compositor-stages-before-draw",
                        url
                ).redirectErrorStream(true).start();
                String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                int exit = proc.waitFor();
                if (exit == 0 && Files.exists(pdfFile)) {
                    long size = Files.size(pdfFile);
                    enqueueMarkup("[green]✔[/] PDF exported: [bold yellow]" + pdfFile + "[/] [cyan](" + formatBytes(size) + ")[/]");
                } else {
                    enqueueMarkup("[red]PDF export failed[/] (exit " + exit + ")");
                    if (!output.isEmpty()) enqueueMarkup("[gray]" + output + "[/]");
                }
            } catch (Exception e) {
                enqueueMarkup("[red]PDF export failed:[/] " + e.getMessage());
            }
        });
    }

    /**
     * Export PDF non-interactively: start a temp server, run headless Chrome, stop server.
     */
    private void exportPdfSync() throws IOException {
        String chrome = findChrome();
        if (chrome == null) {
            throw new IOException("No Chrome/Chromium found — install Google Chrome or Chromium for PDF export");
        }
        // Start a temporary server on a random free port
        boolean wasServing = serving;
        int savedPort = port;
        boolean startedServer = false;
        try {
            if (!wasServing) {
                port = 0; // let the OS pick a free port
                if (serveTempDir == null) {
                    serveTempDir = Files.createTempDirectory("lazyslide_");
                    serveTempDir.toFile().deleteOnExit();
                }
                serving = true;
                doRender("pdf export");
                startServer();
                startedServer = true;
            }
            int actualPort = httpServer.actualPort();
            String pdfName = outputName().replaceFirst("\\.html$", ".pdf");
            Path pdfFile = rootDir().resolve(pdfName);
            String url = "http://localhost:" + actualPort + "/" + outputName() + "?print-pdf";
            System.out.println("lazyslide: exporting PDF via headless Chrome...");
            Process proc = new ProcessBuilder(
                    chrome,
                    "--headless=new",
                    "--no-pdf-header-footer",
                    "--print-to-pdf=" + pdfFile.toAbsolutePath(),
                    "--disable-gpu",
                    "--no-sandbox",
                    "--run-all-compositor-stages-before-draw",
                    url
            ).redirectErrorStream(true).start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            int exit = proc.waitFor();
            if (exit == 0 && Files.exists(pdfFile)) {
                long size = Files.size(pdfFile);
                System.out.printf("lazyslide: PDF exported to %s (%s)%n", pdfFile, formatBytes(size));
            } else {
                System.err.println("lazyslide: PDF export failed (exit " + exit + ")");
                if (!output.isEmpty()) System.err.println(output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("PDF export interrupted", e);
        } finally {
            if (!wasServing) {
                if (startedServer) stopServer();
                serving = false;
                port = savedPort;
            }
        }
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private void toggleWatch() {
        if (serving) {
            enqueueMarkup("[yellow]watch is locked while serve is on[/]");
            return;
        }
        if (watchService == null) {
            try {
                watching = true;
                startWatcher();
                requestRender("watch enabled");
            } catch (IOException e) {
                enqueueMarkup("[red]watch error:[/] " + e.getMessage());
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
                    enqueueMarkup("[red]watch error:[/] " + e.getMessage());
                }
            }
            try {
                startServer();
                openPresentation();
            } catch (IOException e) {
                serving = false;
                enqueueMarkup("[red]" + e.getMessage() + "[/]");
            }
        } else {
            serving = false;
            stopServer();
        }
    }

    boolean isIgnoredGeneratedOutput(Path watchedDir, String name) {
        // Output lives in outputDir() which is skipped by the watcher, but a stale
        // pre-split .html sitting in the source root should still be ignored.
        return name.endsWith(".html");
    }

    private void requestRender(String reason) {
        scheduleRender(reason);
    }

    private void shutdown() {
        running = false;
        synchronized (stateLock) {
            if (pendingRender != null) {
                pendingRender.cancel(false);
            }
        }
        stopWatcher();
        stopServer();
        scheduler.shutdownNow();
        // Clean up temp directory
        if (serveTempDir != null) {
            try (var walk = Files.walk(serveTempDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}
        }
    }

    private final class DashboardApp extends InlineApp {
        public boolean isReady() {
            return runner() != null;
        }

        public void stop() {
            quit();
        }

        public void log(String message) {
            runner().runOnRenderThread(() -> super.println(message));
        }

        public void logMarkup(String markup) {
            runner().runOnRenderThread(() -> super.println(markupText(markup)));
        }

        @Override
        protected void onStart() {
            flushPendingMarkup();
        }

        protected int height() {
            return 2;
        }

        @Override
        protected InlineTuiConfig configure(int height) {
            return InlineTuiConfig.builder(height)
                    .tickRate(Duration.ofMillis(250))
                    .build();
        }

        @Override
        protected Element render() {
            String renderInfo = renderCount == 0
                    ? "waiting..."
                    : String.format("#%d %s (avg %s)", renderCount, formatNanos(lastRenderNanos),
                            formatNanos(totalRenderNanos / renderCount));

            return column(
                    row(
                            markupText("[cyan]▶[/] [bold white]" + currentUrl() + "[/]"),
                            text("  "),
                            text(renderInfo).gray(),
                            text("  "),
                            markupText(wsClients.isEmpty() ? "" : "[magenta]" + wsClients.size() + " client" + (wsClients.size() == 1 ? "" : "s") + "[/]")
                    ),
                    row(
                            markupText("[bold white]r[/][gray]ender[/]"),
                            text(" "),
                            markupText("[bold white]w[/][gray]atch[/]"),
                            text(" "),
                            markupText("[bold white]s[/][gray]erve[/]"),
                            text(" "),
                            markupText("[bold white]o[/][gray]pen[/]"),
                            text(" "),
                            markupText("[bold white]e[/][gray]dit[/]"),
                            text(" "),
                            markupText(serving ? "[bold white]p[/][gray]df[/]" : ""),
                            text(serving ? " " : ""),
                            markupText("[bold white]i[/][gray]nfo[/]"),
                            text(" "),
                            markupText("[bold white]q[/][gray]uit[/]")
                    )
            ).onKeyEvent(event -> {
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
                if (event.isCharIgnoreCase('e')) {
                    openEditor();
                    return EventResult.HANDLED;
                }
                if (event.isCharIgnoreCase('p')) {
                    exportPdf();
                    return EventResult.HANDLED;
                }
                if (event.isCharIgnoreCase('i')) {
                    showSource();
                    return EventResult.HANDLED;
                }
                return EventResult.UNHANDLED;
            }).focusable();
        }
    }

    static String formatNanos(long nanos) {
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
                    new TemplateFile("slides/index.adoc", indexAdoc(title, author)),
                    new TemplateFile("slides/css/presentation.css", presentationCss()),
                    new TemplateFile("slides/01-title.adoc", titleSlide()),
                    new TemplateFile("slides/02-story.adoc", storySlide()),
                    new TemplateFile("slides/03-closing.adoc", closingSlide()),
                    new TemplateFile("slides/img/.gitkeep", "")
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
            System.out.println("  lazyslide serve slides/");
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
                    + ":author: " + author + "\n\n"
                    + "== Render once\n\n"
                    + "[source,bash]\n"
                    + "----\n"
                    + "lazyslide render slides/\n"
                    + "----\n\n"
                    + "== Live preview\n\n"
                    + "[source,bash]\n"
                    + "----\n"
                    + "lazyslide serve slides/\n"
                    + "----\n\n"
                    + "== Project layout\n\n"
                    + "- `slides/` — slide `.adoc` files (scanned in lexicographic order)\n"
                    + "- `slides/_attributes.adoc` — shared reveal.js and theme settings\n"
                    + "- `slides/css/` — custom stylesheets\n"
                    + "- `slides/img/` — images and diagrams\n"
                    + "- `slides/public/` — rendered output (git-ignored)\n";
        }

        private static String gitignore() {
            return "public/\n"
                    + ".asciidoctor/\n";
        }

        private static String indexAdoc(String title, String author) {
            return "= " + title + "\n"
                    + ":author: " + author + "\n"
                    + ":icons: font\n"
                    + ":imagesdir: img\n"
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
                    + "// :revealjs_showNotes: true\n\n"
                    + "include::01-title.adoc[]\n\n"
                    + "include::02-story.adoc[]\n\n"
                    + "include::03-closing.adoc[]\n";
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
