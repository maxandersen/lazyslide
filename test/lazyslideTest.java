///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.junit.jupiter:junit-jupiter-engine:5.12.2
//DEPS org.junit.platform:junit-platform-console:1.12.2

//SOURCES ../lazyslide.java

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.console.ConsoleLauncher;

public class lazyslideTest {

    // ── fileExtension ──────────────────────────────────────────────────

    @Test
    void fileExtension_png() {
        assertEquals("png", lazyslide.fileExtension(Path.of("logo.png")));
    }

    @Test
    void fileExtension_uppercase() {
        assertEquals("jpg", lazyslide.fileExtension(Path.of("photo.JPG")));
    }

    @Test
    void fileExtension_noExtension() {
        assertEquals("", lazyslide.fileExtension(Path.of("Makefile")));
    }

    @Test
    void fileExtension_dotfile() {
        assertEquals("gitignore", lazyslide.fileExtension(Path.of(".gitignore")));
    }

    @Test
    void fileExtension_multiple_dots() {
        assertEquals("gz", lazyslide.fileExtension(Path.of("archive.tar.gz")));
    }

    // ── formatBytes ────────────────────────────────────────────────────

    @Test
    void formatBytes_bytes() {
        assertEquals("512B", lazyslide.formatBytes(512));
    }

    @Test
    void formatBytes_kilobytes() {
        assertEquals("1.5KB", lazyslide.formatBytes(1536));
    }

    @Test
    void formatBytes_megabytes() {
        assertEquals("2.0MB", lazyslide.formatBytes(2 * 1024 * 1024));
    }

    @Test
    void formatBytes_zero() {
        assertEquals("0B", lazyslide.formatBytes(0));
    }

    // ── formatNanos ────────────────────────────────────────────────────

    @Test
    void formatNanos_zero() {
        assertEquals("—", lazyslide.formatNanos(0));
    }

    @Test
    void formatNanos_microseconds() {
        String result = lazyslide.formatNanos(500_000);
        assertTrue(result.endsWith("µs"), "Expected µs suffix, got: " + result);
    }

    @Test
    void formatNanos_milliseconds() {
        String result = lazyslide.formatNanos(50_000_000);
        assertTrue(result.endsWith("ms"), "Expected ms suffix, got: " + result);
    }

    @Test
    void formatNanos_seconds() {
        String result = lazyslide.formatNanos(2_500_000_000L);
        assertTrue(result.endsWith("s"), "Expected s suffix, got: " + result);
        assertTrue(result.startsWith("2.5"), "Expected 2.5, got: " + result);
    }

    // ── injectLiveReload ───────────────────────────────────────────────

    @Test
    void injectLiveReload_beforeBodyClose() {
        String html = "<html><body><h1>Hello</h1></body></html>";
        String result = lazyslide.injectLiveReload(html);
        assertTrue(result.contains("livereload"), "Should contain livereload script");
        assertTrue(result.contains("</body>"), "Should still have closing body tag");
        assertTrue(result.indexOf("livereload") < result.indexOf("</body>"),
                "Script should be before </body>");
    }

    @Test
    void injectLiveReload_noBodyTag() {
        String html = "<html><h1>Hello</h1></html>";
        String result = lazyslide.injectLiveReload(html);
        assertTrue(result.contains("livereload"), "Should append livereload script");
    }

    // ── parseAttributesFile ────────────────────────────────────────────

    @Test
    void parseAttributes_basic(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("attrs.adoc");
        Files.writeString(file, """
                :icons: font
                :revealjs_theme: black
                """);
        var ls = new lazyslide();
        Map<String, String> attrs = ls.parseAttributesFile(file);
        assertEquals("font", attrs.get("icons"));
        assertEquals("black", attrs.get("revealjs_theme"));
    }

    @Test
    void parseAttributes_skipsComments(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("attrs.adoc");
        Files.writeString(file, """
                // this is a comment
                :key: value
                
                :other: data
                """);
        var ls = new lazyslide();
        Map<String, String> attrs = ls.parseAttributesFile(file);
        assertEquals(2, attrs.size());
        assertEquals("value", attrs.get("key"));
        assertEquals("data", attrs.get("other"));
    }

    @Test
    void parseAttributes_urlValues(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("attrs.adoc");
        Files.writeString(file, """
                :highlightjs-theme: https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/atom-one-dark.min.css
                :revealjsdir: https://cdn.jsdelivr.net/npm/reveal.js@5.2.0
                :imagesdir: img
                """);
        var ls = new lazyslide();
        Map<String, String> attrs = ls.parseAttributesFile(file);
        assertEquals("https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/atom-one-dark.min.css",
                attrs.get("highlightjs-theme"), "URL values should not be truncated at colons");
        assertEquals("https://cdn.jsdelivr.net/npm/reveal.js@5.2.0", attrs.get("revealjsdir"));
        assertEquals("img", attrs.get("imagesdir"));
    }

    @Test
    void parseAttributes_emptyFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("attrs.adoc");
        Files.writeString(file, "");
        var ls = new lazyslide();
        Map<String, String> attrs = ls.parseAttributesFile(file);
        assertTrue(attrs.isEmpty());
    }

    // ── scanAdocFiles ──────────────────────────────────────────────────

    @Test
    void scanAdocFiles_findsAdocFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("01-intro.adoc"), "= Intro");
        Files.writeString(dir.resolve("02-main.adoc"), "= Main");
        Files.writeString(dir.resolve("notes.txt"), "not a slide");
        var ls = new lazyslide();
        ls.outputDirName = "public";
        List<Path> files = ls.scanAdocFiles(dir);
        assertEquals(2, files.size());
        assertTrue(files.get(0).toString().contains("01-intro"));
        assertTrue(files.get(1).toString().contains("02-main"));
    }

    @Test
    void scanAdocFiles_excludesHidden(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("visible.adoc"), "= Visible");
        Files.writeString(dir.resolve(".hidden.adoc"), "= Hidden");
        var ls = new lazyslide();
        ls.outputDirName = "public";
        List<Path> files = ls.scanAdocFiles(dir);
        assertEquals(1, files.size());
    }

    @Test
    void scanAdocFiles_excludesUnderscore(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("slide.adoc"), "= Slide");
        Files.writeString(dir.resolve("_attributes.adoc"), ":key: val");
        Files.writeString(dir.resolve("_lazyslide_attributes.adoc"), ":key: val");
        var ls = new lazyslide();
        ls.outputDirName = "public";
        List<Path> files = ls.scanAdocFiles(dir);
        assertEquals(1, files.size());
    }

    @Test
    void scanAdocFiles_sorted(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("b.adoc"), "= B");
        Files.writeString(dir.resolve("a.adoc"), "= A");
        Files.writeString(dir.resolve("c.adoc"), "= C");
        var ls = new lazyslide();
        ls.outputDirName = "public";
        List<Path> files = ls.scanAdocFiles(dir);
        assertEquals(3, files.size());
        assertTrue(files.get(0).getFileName().toString().equals("a.adoc"));
        assertTrue(files.get(1).getFileName().toString().equals("b.adoc"));
        assertTrue(files.get(2).getFileName().toString().equals("c.adoc"));
    }

    // ── generateVirtualIndex ───────────────────────────────────────────

    @Test
    void generateVirtualIndex_includesAllFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("01-intro.adoc"), "= Intro");
        Files.writeString(dir.resolve("02-main.adoc"), "= Main");
        var ls = new lazyslide();
        List<Path> files = List.of(dir.resolve("01-intro.adoc"), dir.resolve("02-main.adoc"));
        String index = ls.generateVirtualIndex(dir, files);
        assertTrue(index.contains("include::01-intro.adoc"));
        assertTrue(index.contains("include::02-main.adoc"));
        assertTrue(index.contains("leveloffset=+1"));
        assertTrue(index.startsWith("= Slides"));
    }

    // ── resolveInput ───────────────────────────────────────────────────

    @Test
    void resolveInput_singleFile(@TempDir Path dir) throws IOException {
        Path adoc = dir.resolve("deck.adoc");
        Files.writeString(adoc, "= Deck");
        var ls = new lazyslide();
        ls.file = adoc.toFile();
        ls.outputDirName = "public";
        ls.resolveInput();
        assertNull(ls.inputDir, "Single file should not set inputDir");
    }

    @Test
    void resolveInput_directoryWithIndex(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("index.adoc"), "= Index");
        Files.writeString(dir.resolve("other.adoc"), "= Other");
        var ls = new lazyslide();
        ls.file = dir.toFile();
        ls.outputDirName = "public";
        ls.resolveInput();
        assertNull(ls.inputDir, "Dir with index.adoc should use single-file mode");
        assertEquals("index.adoc", ls.file.getName());
    }

    @Test
    void resolveInput_directoryWithoutIndex(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("01-intro.adoc"), "= Intro");
        Files.writeString(dir.resolve("02-main.adoc"), "= Main");
        var ls = new lazyslide();
        ls.file = dir.toFile();
        ls.outputDirName = "public";
        ls.resolveInput();
        assertNotNull(ls.inputDir, "Dir without index.adoc should set inputDir");
        assertNotNull(ls.virtualIndexContent);
        assertTrue(ls.virtualIndexContent.contains("include::01-intro.adoc"));
    }

    @Test
    void resolveInput_emptyDirectory(@TempDir Path dir) {
        var ls = new lazyslide();
        ls.file = dir.toFile();
        ls.outputDirName = "public";
        assertThrows(IOException.class, ls::resolveInput, "Empty dir should throw");
    }

    @Test
    void resolveInput_missingFile(@TempDir Path dir) {
        var ls = new lazyslide();
        ls.file = dir.resolve("nonexistent.adoc").toFile();
        ls.outputDirName = "public";
        assertThrows(IOException.class, ls::resolveInput);
    }

    // ── CLI attributes ───────────────────────────────────────────────────

    @Test
    void cliAttributes_Dflag() {
        var ls = new lazyslide();
        ls.cliAttributes.put("icons", "image");
        ls.cliAttributes.put("stem", "asciimath");
        assertEquals("image", ls.cliAttributes.get("icons"));
        assertEquals("asciimath", ls.cliAttributes.get("stem"));
    }

    @Test
    void cliAttributes_Rflag() {
        var ls = new lazyslide();
        ls.cliRevealAttributes.put("theme", "white");
        ls.cliRevealAttributes.put("transition", "fade");
        assertEquals("white", ls.cliRevealAttributes.get("theme"));
        assertEquals("fade", ls.cliRevealAttributes.get("transition"));
    }

    // ── template loading ─────────────────────────────────────────────────

    @Test
    void substituteVars_replacesPlaceholders() {
        String result = lazyslide.Init.substituteVars(
                "= {{title}}\n:author: {{author}}",
                Map.of("title", "My Talk", "author", "Jane"));
        assertEquals("= My Talk\n:author: Jane", result);
    }

    @Test
    void substituteVars_leavesUnknownPlaceholders() {
        String result = lazyslide.Init.substituteVars(
                "{{title}} and {{unknown}}",
                Map.of("title", "Hello"));
        assertEquals("Hello and {{unknown}}", result);
    }

    @Test
    void loadTemplate_unknownTemplateThrows() {
        assertThrows(IOException.class, () ->
                lazyslide.Init.loadTemplate("nonexistent", Map.of("title", "X", "author", "Y")));
    }

    // ── cycleTheme ─────────────────────────────────────────────────────

    @Test
    void cycleTheme_iteratesForward() {
        var ls = new lazyslide();
        ls.cycleTheme(1);
        assertEquals("beige", ls.cliAttributes.get("revealjs_theme"));
        ls.cycleTheme(1);
        assertEquals("black", ls.cliAttributes.get("revealjs_theme"));
    }

    @Test
    void cycleTheme_iteratesReverse() {
        var ls = new lazyslide();
        ls.cycleTheme(-1);
        assertEquals("white", ls.cliAttributes.get("revealjs_theme"),
                "Reverse from default should start at last theme");
        ls.cycleTheme(-1);
        assertEquals("solarized", ls.cliAttributes.get("revealjs_theme"));
    }

    @Test
    void cycleTheme_wrapsToNoOverride() {
        var ls = new lazyslide();
        int count = lazyslide.REVEAL_THEMES.size();
        for (int i = 0; i <= count; i++) ls.cycleTheme(1);
        assertNull(ls.cliAttributes.get("revealjs_theme"),
                "Should remove override after cycling past all themes");
    }

    @Test
    void cycleTheme_reverseWrapsToNoOverride() {
        var ls = new lazyslide();
        int count = lazyslide.REVEAL_THEMES.size();
        for (int i = 0; i <= count; i++) ls.cycleTheme(-1);
        assertNull(ls.cliAttributes.get("revealjs_theme"),
                "Should remove override after reverse cycling past all themes");
    }

    @Test
    void cycleTheme_resumesAfterReset() {
        var ls = new lazyslide();
        int count = lazyslide.REVEAL_THEMES.size();
        for (int i = 0; i <= count; i++) ls.cycleTheme(1);
        assertNull(ls.cliAttributes.get("revealjs_theme"));
        ls.cycleTheme(1);
        assertEquals("beige", ls.cliAttributes.get("revealjs_theme"));
    }

    // ── outputName ─────────────────────────────────────────────────────

    @Test
    void outputName_singleFile() {
        var ls = new lazyslide();
        ls.file = new File("presentation.adoc");
        assertEquals("presentation.html", ls.outputName());
    }

    @Test
    void outputName_directoryMode() {
        var ls = new lazyslide();
        ls.inputDir = Path.of("/some/slides");
        assertEquals("index.html", ls.outputName());
    }

    // ── isIgnoredGeneratedOutput ───────────────────────────────────────

    @Test
    void isIgnored_htmlFiles() {
        var ls = new lazyslide();
        assertTrue(ls.isIgnoredGeneratedOutput(Path.of("/src"), "output.html"));
    }

    @Test
    void isIgnored_adocFiles() {
        var ls = new lazyslide();
        assertFalse(ls.isIgnoredGeneratedOutput(Path.of("/src"), "slides.adoc"));
    }

    @Test
    void isIgnored_cssFiles() {
        var ls = new lazyslide();
        assertFalse(ls.isIgnoredGeneratedOutput(Path.of("/src"), "style.css"));
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
