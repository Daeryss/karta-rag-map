package io.github.daeryss.karta.corpus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads a {@link CorpusSpec} from YAML and resolves it to a concrete, ordered map
 * of relative-path → file contents.
 *
 * <p>For {@link CorpusSpec#isFilesMode() files mode}, each file is fetched over HTTP
 * from {@code baseUrl + relativePath} and cached under {@code cacheDir} so subsequent
 * runs hit disk only.</p>
 *
 * <p>For {@link CorpusSpec#isBasePathMode() basePath mode}, the loader walks the
 * filesystem under {@code basePath} and includes any file matching at least one
 * {@code include} glob and none of the {@code exclude} globs. Returned paths are
 * relative to {@code basePath} so that downstream output is portable.</p>
 */
public final class CorpusLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private CorpusLoader() {}

    /** Parse the YAML spec without resolving any file content. */
    public static CorpusSpec parse(Path yamlPath) throws IOException {
        CorpusSpec spec = YAML.readValue(yamlPath.toFile(), CorpusSpec.class);
        validate(spec);
        return spec;
    }

    /** Resolve a parsed spec into an ordered map of relative-path → file text. */
    public static Map<String, String> resolve(CorpusSpec spec, Path cacheDir) throws IOException {
        if (spec.isFilesMode())    return resolveFromHttp(spec, cacheDir);
        if (spec.isBasePathMode()) return resolveFromFilesystem(spec);
        throw new IllegalStateException("CorpusSpec has neither files nor basePath populated");
    }

    private static Map<String, String> resolveFromHttp(CorpusSpec spec, Path cacheDir) throws IOException {
        Files.createDirectories(cacheDir);
        Map<String, String> out = new LinkedHashMap<>();
        for (String rel : spec.files()) {
            String name = rel.substring(rel.lastIndexOf('/') + 1);
            Path local = cacheDir.resolve(name);
            if (Files.notExists(local)) {
                URI uri = URI.create(spec.baseUrl() + rel);
                try (InputStream in = uri.toURL().openStream()) {
                    Files.copy(in, local);
                }
            }
            out.put(rel, Files.readString(local, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Map<String, String> resolveFromFilesystem(CorpusSpec spec) throws IOException {
        String resolvedBase = resolveEnvVars(spec.basePath());
        if (resolvedBase == null || resolvedBase.isBlank())
            throw new IllegalStateException(
                    "CorpusSpec.id=" + spec.id() + ": base_path did not resolve to a value. "
                  + "Did you set the env var referenced in '" + spec.basePath() + "'?");
        Path root = Paths.get(resolvedBase).toAbsolutePath().normalize();
        if (!Files.isDirectory(root))
            throw new IllegalStateException(
                    "CorpusSpec.id=" + spec.id() + ": resolved base_path is not a directory: " + root);
        List<PathMatcher> includes = compile(spec.include());
        List<PathMatcher> excludes = compile(spec.exclude());
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> matches = new ArrayList<>();
            walk.filter(p -> safeIsRegular(p))
                .forEach(p -> {
                    Path rel = root.relativize(p);
                    if (!matchAny(rel, includes)) return;
                    if (matchAny(rel, excludes)) return;
                    matches.add(p);
                });
            // Deterministic ordering for reproducibility — sort by relative path string.
            matches.sort((a, b) -> root.relativize(a).toString().compareTo(root.relativize(b).toString()));
            for (Path p : matches) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                out.put(rel, Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static List<PathMatcher> compile(List<String> globs) {
        if (globs == null) return List.of();
        List<PathMatcher> out = new ArrayList<>(globs.size());
        for (String g : globs) {
            out.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
        }
        return out;
    }

    private static boolean matchAny(Path rel, List<PathMatcher> matchers) {
        for (PathMatcher m : matchers) if (m.matches(rel)) return true;
        return false;
    }

    /**
     * Resolves {@code ${env:NAME}} and {@code ${sys:PROP}} placeholders in {@code raw}.
     * Unset env vars / system properties resolve to the literal {@code (unset)} string so
     * downstream validation surfaces a clear error rather than silently using an empty path.
     * Plain strings pass through untouched.
     */
    static String resolveEnvVars(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            int open = raw.indexOf("${", i);
            if (open < 0) { sb.append(raw, i, raw.length()); break; }
            sb.append(raw, i, open);
            int close = raw.indexOf('}', open + 2);
            if (close < 0) { sb.append(raw, open, raw.length()); break; }
            String token = raw.substring(open + 2, close);
            String value;
            if (token.startsWith("env:")) {
                String name = token.substring(4);
                value = System.getenv(name);
            } else if (token.startsWith("sys:")) {
                String name = token.substring(4);
                value = System.getProperty(name);
            } else {
                // Unknown placeholder — pass through literal so caller sees it.
                value = "${" + token + "}";
            }
            sb.append(value == null ? "(unset)" : value);
            i = close + 1;
        }
        return sb.toString();
    }

    private static boolean safeIsRegular(Path p) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            return attrs.isRegularFile();
        } catch (IOException e) {
            return false;
        }
    }

    private static void validate(CorpusSpec spec) {
        if (spec.id() == null || spec.id().isBlank())
            throw new IllegalArgumentException("CorpusSpec.id is required");
        boolean files = spec.isFilesMode();
        boolean base  = spec.isBasePathMode();
        if (files && base) throw new IllegalArgumentException(
                "CorpusSpec.id=" + spec.id() + ": both 'files' and 'base_path' present — pick one");
        if (!files && !base) throw new IllegalArgumentException(
                "CorpusSpec.id=" + spec.id() + ": neither 'files' nor 'base_path' present");
        if (files && (spec.baseUrl() == null || spec.baseUrl().isBlank()))
            throw new IllegalArgumentException(
                    "CorpusSpec.id=" + spec.id() + ": 'files' mode requires 'base_url'");
        if (base && (spec.include() == null || spec.include().isEmpty()))
            throw new IllegalArgumentException(
                    "CorpusSpec.id=" + spec.id() + ": 'base_path' mode requires at least one 'include' glob");
    }
}
