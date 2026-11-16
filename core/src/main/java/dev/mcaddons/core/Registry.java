package dev.mcaddons.core;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Reads the community plugin index from GitHub and installs plugins from it.
 * <p>Safety: only HTTPS URLs on an allow-listed GitHub host are fetched, every file is size-limited,
 * verified against the SHA-256 in the index (which CI generated from the reviewed, merged repo
 * contents) and written into a staging folder that only replaces the plugin when everything checked out.
 */
public final class Registry {
    public static final String DEFAULT_INDEX =
            "https://raw.githubusercontent.com/RandomPixelStudios/McAddons-Plugins/main/index.json";
    private static final Set<String> ALLOWED_HOSTS = Set.of("raw.githubusercontent.com");
    private static final int MAX_FILE = 4 << 20;
    private static final int MAX_FILES = 64;

    public static final class Index {
        public int schema;
        public String updated;
        public List<Entry> plugins = new ArrayList<>();
    }

    public static final class Entry {
        public String id, name, version, description, api, side, icon, repo;
        public List<String> authors = new ArrayList<>();
        public List<String> permissions = new ArrayList<>();
        public List<FileRef> files = new ArrayList<>();
        public long downloads;

        public boolean compatible() { return ApiVersion.compatible(api); }
    }

    public static final class FileRef {
        public String path, url, sha256;
        public long size;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final String indexUrl;
    private volatile Index cached;

    public Registry(String indexUrl) { this.indexUrl = indexUrl; }

    public Index cached() { return cached; }

    public CompletableFuture<Index> fetch() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] data = get(indexUrl + "?t=" + System.currentTimeMillis() / 60000);
                Index idx = new Gson().fromJson(new String(data, java.nio.charset.StandardCharsets.UTF_8), Index.class);
                if (idx == null || idx.plugins == null) throw new IOException("empty index");
                idx.plugins.removeIf(e -> e.id == null || !Manifest.ID.matcher(e.id).matches());
                cached = idx;
                return idx;
            } catch (Exception e) {
                throw new RuntimeException("could not load plugin index: " + e.getMessage(), e);
            }
        });
    }

    public byte[] get(String url) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !ALLOWED_HOSTS.contains(uri.getHost()))
            throw new IOException("blocked URL (only https://" + ALLOWED_HOSTS + "): " + url);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "McAddons/" + ApiVersion.STRING).GET().build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " for " + url);
        if (res.body().length > MAX_FILE) throw new IOException("file too large: " + url);
        return res.body();
    }

    /** Downloads, verifies and atomically installs a plugin. Runs off-thread. */
    public CompletableFuture<String> install(Entry e, Path pluginsDir) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!e.compatible()) throw new IOException("plugin needs API " + e.api + ", this build has " + ApiVersion.STRING);
                if (e.files == null || e.files.isEmpty() || e.files.size() > MAX_FILES) throw new IOException("bad file list");
                Path staging = pluginsDir.resolve(".staging-" + e.id);
                deleteTree(staging);
                Files.createDirectories(staging);
                for (FileRef f : e.files) {
                    byte[] data = get(f.url);
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
                    if (!hash.equalsIgnoreCase(f.sha256)) throw new IOException("checksum mismatch for " + f.path);
                    Path out = Paths.resolveInside(staging, f.path);
                    Files.createDirectories(out.getParent());
                    Files.write(out, data);
                }
                Manifest m = Manifest.read(staging.resolve("plugin.json"));
                if (!m.id.equals(e.id)) throw new IOException("index id and plugin.json id differ");
                if (!Set.copyOf(e.permissions).containsAll(m.permissions))
                    throw new IOException("plugin.json requests permissions not shown in the index");
                Path target = pluginsDir.resolve(e.id);
                deleteTree(target);
                Files.move(staging, target);
                return "Installed " + m.name + " " + m.version;
            } catch (Exception ex) {
                try { deleteTree(pluginsDir.resolve(".staging-" + e.id)); } catch (IOException ignored) {}
                throw new RuntimeException(ex.getMessage(), ex);
            }
        });
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }
}
