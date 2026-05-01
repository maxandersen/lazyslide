# Agent Notes for lazyslide

## Project structure

- `lazyslide.java` — single-file JBang application (Java 25+, ~1670 lines)
- `test/lazyslideTest.java` — unit tests (JUnit 5, `//SOURCES ../lazyslide.java`)
- `test/lazyslideITest.java` — integration tests (JUnit 5 + cli-assured, runs via `jbang`)
- `favicon.png` — bundled via `//FILES` directive
- `.local/review.md` — code review notes (gitignored)

## Running

```bash
# Run the tool
jbang lazyslide.java serve slides/
jbang lazyslide.java render slides/
jbang lazyslide.java init

# Run tests
jbang test/lazyslideTest.java    # unit tests (~70ms)
jbang test/lazyslideITest.java   # integration tests (~25s)
```

## Key design decisions

- **Single-file JBang app** — all code lives in `lazyslide.java`. Dependencies declared via `//DEPS` comments. No Maven/Gradle.
- **TTY detection** — `serve` and `watch` use `System.console() != null` to decide between TUI mode (interactive terminal) and headless mode (piped/CI). This matters for testing — cli-assured pipes stdout so the tool runs headless, printing plain text instead of TUI escape codes.
- **Methods made package-private for testing** — several methods were changed from `private` to package-private so `lazyslideTest.java` (compiled via `//SOURCES`) can access them directly. These include: `fileExtension`, `injectLiveReload`, `formatBytes`, `formatNanos`, `parseAttributesFile`, `generateVirtualIndex`, `scanAdocFiles`, `resolveInput`, `rootDir`, `outputName`, `outputDir`, `isIgnoredGeneratedOutput`.
- **Port 0** — `--port 0` lets the OS pick a free port. After `httpServer.listen()`, the actual port is read via `httpServer.actualPort()` and stored back in the `port` field. The `exportPdfSync()` method also uses port 0 for its temporary server so `render --pdf` never conflicts with a running `serve`.
- **Output directory** — `-o` / `--output-dir` is relative to CWD, not to the input file. In serve mode, output goes to a temp dir (`serveTempDir`) to avoid polluting the source tree.
- **Asset mirroring** — directories from root are mirrored recursively. Loose files are only copied if their extension is in `MIRRORED_FILE_EXTENSIONS` (images, fonts, css, js, media, pdf).

## Testing notes

- **Unit tests** (`test/lazyslideTest.java`) use `//SOURCES ../lazyslide.java` to compile the main source as a dependency. They test pure methods directly — no processes spawned.
- **Integration tests** (`test/lazyslideITest.java`) use cli-assured to launch `jbang lazyslide.java` as a subprocess. The `LAZYSLIDE` path is resolved as `Path.of("lazyslide.java").toAbsolutePath()` — this assumes CWD is the project root. Do not use `../lazyslide.java` — that resolves one level too high.
- **`stderrToStdout()`** is required on all cli-assured commands because jbang emits build output and asciidoctor emits warnings to stderr, which cli-assured treats as assertion failures by default.
- **Serve test** uses `Await.lineMatching()` to capture the port from stdout (headless mode prints `serving at http://localhost:PORT/...` as plain text). Then it HTTP-fetches the page and verifies content.
- **Render tests** must pass `-o <tempdir>` explicitly since the default `public/` is relative to CWD (the project root), which would pollute the repo.

## Known issues

See `.local/review.md` for the full code review. Key items:

- **Path traversal in HTTP server** — hand-rolled file serving with `normalize()` + `startsWith()` is fragile. Should use Vert.x `StaticHandler` or `Path.toRealPath()`.
- **`parseAttributesFile` truncates URL values** — `:url: https://example.com` gets cut at the protocol colon. The parser finds the wrong closing `:`.
- **Watcher doesn't register new directories** — files in newly created subdirectories won't trigger rebuilds until restart.
- **Concurrency** — `scheduleRender` has a race on the volatile `pendingRender` field; `asciidoctorReady`/`asciidoctor` publish ordering lacks a happens-before guarantee.

## PDF export

Uses headless Chrome (`--print-to-pdf`) against reveal.js's `?print-pdf` mode. Chrome is auto-detected via `findChrome()`. Do **not** use `--virtual-time-budget` — it prevents CDN resources from loading. The `--run-all-compositor-stages-before-draw` flag ensures rendering completes before printing.
