///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 25+
//DEPS org.junit.jupiter:junit-jupiter-engine:5.12.2
//DEPS org.junit.platform:junit-platform-console:1.12.2
//DEPS org.cli-assured:cli-assured:0.0.1
//DEPS org.slf4j:slf4j-simple:2.0.16

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import org.cliassured.CliAssured;
import org.cliassured.CommandProcess;
import org.cliassured.Await;
import org.cliassured.Await.LineAwait;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.console.ConsoleLauncher;

/**
 * Integration tests for lazyslide CLI workflows.
 * Uses cli-assured to test the actual jbang-launched process.
 */
public class lazyslideITest {

    static final String LAZYSLIDE = Path.of("lazyslide.java").toAbsolutePath().toString();

    // ── init ───────────────────────────────────────────────────────────

    @Test
    void init_createsStarterDeck(@TempDir Path dir) {
        CliAssured
            .command("jbang", LAZYSLIDE, "init", "--title", "Test Deck", dir.toString())
            .stderrToStdout()
            .then()
                .stdout()
                    .hasLinesContaining("starter deck created")
                .exitCodeIs(0)
            .execute()
            .assertSuccess();

        assertTrue(Files.exists(dir.resolve("slides/01-title.adoc")));
        assertTrue(Files.exists(dir.resolve("slides/02-story.adoc")));
        assertTrue(Files.exists(dir.resolve("slides/03-closing.adoc")));
        assertTrue(Files.exists(dir.resolve("slides/css/presentation.css")));
        assertTrue(Files.exists(dir.resolve("slides/index.adoc")));
        assertTrue(Files.exists(dir.resolve("README.adoc")));
        assertTrue(Files.exists(dir.resolve(".gitignore")));
    }

    @Test
    void init_refusesOverwriteWithoutForce(@TempDir Path dir) {
        // First init
        CliAssured
            .command("jbang", LAZYSLIDE, "init", dir.toString())
            .stderrToStdout()
            .then()
                .exitCodeIs(0)
            .execute()
            .assertSuccess();

        // Second init should fail
        CliAssured
            .command("jbang", LAZYSLIDE, "init", dir.toString())
            .stderrToStdout()
            .then()
                .stdout()
                    .hasLinesContaining("--force")
                .exitCodeIs(1)
            .execute()
            .assertSuccess();
    }

    @Test
    void init_forceOverwrites(@TempDir Path dir) {
        CliAssured
            .command("jbang", LAZYSLIDE, "init", dir.toString())
            .stderrToStdout()
            .then().exitCodeIs(0)
            .execute()
            .assertSuccess();

        CliAssured
            .command("jbang", LAZYSLIDE, "init", "--force", dir.toString())
            .stderrToStdout()
            .then()
                .stdout()
                    .hasLinesContaining("starter deck created")
                .exitCodeIs(0)
            .execute()
            .assertSuccess();
    }

    // ── render ─────────────────────────────────────────────────────────

    @Test
    void render_singleFile(@TempDir Path dir) throws Exception {
        Path adoc = dir.resolve("deck.adoc");
        Path outDir = dir.resolve("output");
        Files.writeString(adoc, """
                = Test Deck
                :revealjsdir: https://cdn.jsdelivr.net/npm/reveal.js@5.2.0

                == Slide One

                Hello World
                """);

        CliAssured
            .command("jbang", LAZYSLIDE, "-o", outDir.toString(), "render", adoc.toString())
            .stderrToStdout()
            .then()
                .stdout()
                    .hasLinesContaining("done in")
                .exitCodeIs(0)
            .execute()
            .assertSuccess();

        Path html = outDir.resolve("deck.html");
        assertTrue(Files.exists(html), "Should produce deck.html in output dir");
        String content = Files.readString(html);
        assertTrue(content.contains("reveal"), "Output should be a reveal.js deck");
        assertTrue(content.contains("Hello World"), "Output should contain slide content");
    }

    @Test
    void render_directoryMode(@TempDir Path dir) throws Exception {
        Path slides = dir.resolve("slides");
        Path outDir = dir.resolve("output");
        Files.createDirectories(slides);
        Files.writeString(slides.resolve("01-intro.adoc"), """
                = Introduction

                Welcome!
                """);
        Files.writeString(slides.resolve("02-content.adoc"), """
                = Content

                Some content here.
                """);

        CliAssured
            .command("jbang", LAZYSLIDE, "-o", outDir.toString(), "render", slides.toString())
            .stderrToStdout()
            .then()
                .stdout()
                    .hasLinesContaining("done in")
                .exitCodeIs(0)
            .execute()
            .assertSuccess();

        Path html = outDir.resolve("index.html");
        assertTrue(Files.exists(html), "Directory mode should produce index.html");
        String content = Files.readString(html);
        assertTrue(content.contains("Welcome!"));
        assertTrue(content.contains("Some content here."));
    }

    @Test
    void render_directoryWithIndex(@TempDir Path dir) throws Exception {
        Path slides = dir.resolve("slides");
        Path outDir = dir.resolve("output");
        Files.createDirectories(slides);
        Files.writeString(slides.resolve("index.adoc"), """
                = My Custom Index
                :revealjsdir: https://cdn.jsdelivr.net/npm/reveal.js@5.2.0

                == Custom Slide

                Custom content
                """);

        CliAssured
            .command("jbang", LAZYSLIDE, "-o", outDir.toString(), "render", slides.toString())
            .stderrToStdout()
            .then()
                .stdout()
                    .hasLinesContaining("done in")
                .exitCodeIs(0)
            .execute()
            .assertSuccess();

        Path html = outDir.resolve("index.html");
        assertTrue(Files.exists(html));
        assertTrue(Files.readString(html).contains("Custom content"));
    }

    @Test
    void render_customOutputDir(@TempDir Path dir) throws Exception {
        Path adoc = dir.resolve("deck.adoc");
        Path outDir = dir.resolve("dist");
        Files.writeString(adoc, """
                = Deck
                :revealjsdir: https://cdn.jsdelivr.net/npm/reveal.js@5.2.0

                == Slide
                Content
                """);

        CliAssured
            .command("jbang", LAZYSLIDE, "-o", outDir.toString(), "render", adoc.toString())
            .stderrToStdout()
            .then()
                .exitCodeIs(0)
            .execute()
            .assertSuccess();

        assertTrue(Files.exists(outDir.resolve("deck.html")));
    }

    @Test
    void render_missingFile() {
        CliAssured
            .command("jbang", LAZYSLIDE, "render", "/nonexistent/missing.adoc")
            .stderrToStdout()
            .then()
                .exitCodeIs(1)
            .execute()
            .assertSuccess();
    }

    @Test
    void render_mirrorsAssets(@TempDir Path dir) throws Exception {
        Path slides = dir.resolve("slides");
        Path img = slides.resolve("img");
        Path outDir = dir.resolve("output");
        Files.createDirectories(img);
        Files.writeString(slides.resolve("index.adoc"), """
                = Deck
                :revealjsdir: https://cdn.jsdelivr.net/npm/reveal.js@5.2.0
                :imagesdir: img

                == Slide
                image::test.svg[]
                """);
        // Create a dummy SVG
        Files.writeString(img.resolve("test.svg"), "<svg></svg>");

        CliAssured
            .command("jbang", LAZYSLIDE, "-o", outDir.toString(), "render", slides.toString())
            .stderrToStdout()
            .then()
                .exitCodeIs(0)
            .execute()
            .assertSuccess();

        assertTrue(Files.exists(outDir.resolve("img/test.svg")),
                "Asset directories should be mirrored to output");
    }

    // ── serve ──────────────────────────────────────────────────────────

    @Test
    void serve_startsAndServesContent(@TempDir Path dir) throws Exception {
        Path adoc = dir.resolve("deck.adoc");
        Files.writeString(adoc, """
                = Serve Test
                :revealjsdir: https://cdn.jsdelivr.net/npm/reveal.js@5.2.0

                == Slide One

                Served!
                """);

        // When piped (no TTY), serve runs in headless mode and prints plain text to stdout
        final LineAwait<String> awaitServing = Await
                .lineMatching(".*http://localhost:(\\d+)/.*")
                .map(port -> port);

        try (CommandProcess proc = CliAssured
                .command("jbang", LAZYSLIDE, "serve", "--port", "0", adoc.toString())
                .stderrToStdout()
                .autoCloseForcibly()
                .then()
                    .stdout()
                        .await(awaitServing)
                .start()) {

            String port = awaitServing.await(Duration.ofSeconds(120));
            assertNotNull(port, "Should have captured the serving port");

            // Give a moment for the first render to complete
            Thread.sleep(5000);

            // Fetch the served page
            String url = "http://localhost:" + port + "/deck.html";
            var client = HttpClient.newHttpClient();
            var response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Served!"));
            assertTrue(response.body().contains("livereload"),
                    "Served HTML should include live-reload script");
        }
    }

    // ── help ───────────────────────────────────────────────────────────

    @Test
    void help_showsUsage() {
        CliAssured
            .command("jbang", LAZYSLIDE, "--help")
            .stderrToStdout()
            .then()
                .stdout()
                    .hasLinesContaining("lazyslide")
                    .hasLinesContaining("render")
                    .hasLinesContaining("serve")
                    .hasLinesContaining("watch")
                    .hasLinesContaining("init")
                .exitCodeIs(0)
            .execute()
            .assertSuccess();
    }

    @Test
    void render_help() {
        CliAssured
            .command("jbang", LAZYSLIDE, "render", "--help")
            .stderrToStdout()
            .then()
                .stdout()
                    .hasLinesContaining("Render the deck once")
                    .hasLinesContaining("--open")
                    .hasLinesContaining("--pdf")
                .exitCodeIs(0)
            .execute()
            .assertSuccess();
    }

    // ── version ────────────────────────────────────────────────────────

    @Test
    void version_showsVersion() {
        CliAssured
            .command("jbang", LAZYSLIDE, "--version")
            .stderrToStdout()
            .then()
                .stdout()
                    .hasLinesContaining("0.1.0")
                .exitCodeIs(0)
            .execute()
            .assertSuccess();
    }

    // ── main ───────────────────────────────────────────────────────────

    public static void main(final String... args) {
        String jarsList = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(path -> path.contains("/cache/jars/"))
                .reduce((a, b) -> a + File.pathSeparator + b)
                .orElse("");

        ConsoleLauncher.main("execute", "--scan-class-path", "-cp", jarsList);
    }
}
