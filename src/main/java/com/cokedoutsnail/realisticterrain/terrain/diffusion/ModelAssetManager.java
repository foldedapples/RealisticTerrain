package com.cokedoutsnail.realisticterrain.terrain.diffusion;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModelAssetManager {
    private static final Logger LOG = LoggerFactory.getLogger(ModelAssetManager.class);
    private static final String MANIFEST = "/model-assets-manifest.json";
    private static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("realisticterrain-models");
    private static final AtomicBoolean READY = new AtomicBoolean(false);
    private static final Gson GSON = new Gson();
    private static final Type MTYPE = new TypeToken<Manifest>() {}.getType();

    private ModelAssetManager() {}

    public static void ensureAssetsReady() {
        if (READY.get()) return;
        synchronized (ModelAssetManager.class) {
            if (READY.get()) return;
            try {
                Files.createDirectories(DIR);
                Manifest m = loadManifest();
                LOG.info("Preparing model assets in {}", DIR);
                m.assets.forEach((name, a) -> ensureFile(DIR.resolve(name), a));
                READY.set(true);
                LOG.info("Model assets ready");
            } catch (Exception e) { throw new IllegalStateException("Failed to prepare model assets", e); }
        }
    }

    public static Path resolveAssetPath(String name) { return DIR.resolve(name); }
    public static boolean isReady() { return READY.get(); }

    private static void ensureFile(Path p, Asset a) {
        if (Files.exists(p)) {
            try { if (sha256(p).equals(a.sha256)) return; } catch (Exception e) { /* re-download */ }
        }
        download(p, a);
    }

    private static void download(Path p, Asset a) {
        LOG.info("Downloading '{}' ({} bytes)...", p.getFileName(), a.sizeBytes);
        Path tmp = null;
        try {
            tmp = Files.createTempFile(DIR, "dl_", ".tmp");
            HttpResponse<InputStream> resp = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                    .send(HttpRequest.newBuilder().uri(URI.create(a.url)).timeout(java.time.Duration.ofMinutes(30)).build(),
                            HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
            try (InputStream is = resp.body(); OutputStream os = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[262144]; long total = 0; int next = 10; int n;
                while ((n = is.read(buf)) != -1) { os.write(buf, 0, n); total += n;
                    if (a.sizeBytes > 0) { int pct = (int)(total*100/a.sizeBytes);
                        while (pct >= next && next <= 100) { LOG.info("Downloading... {}%", next); next += 10; } } }
            }
            if (!sha256(tmp).equals(a.sha256)) throw new IOException("SHA-256 mismatch");
            Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Downloaded '{}'", p.getFileName());
        } catch (Exception e) {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw new IllegalStateException("Failed to download '" + p.getFileName() + "'", e);
        }
    }

    private static Manifest loadManifest() throws Exception {
        try (InputStream is = ModelAssetManager.class.getResourceAsStream(MANIFEST)) {
            if (is == null) throw new IllegalStateException("Manifest not found");
            Manifest m = GSON.fromJson(new String(is.readAllBytes(), StandardCharsets.UTF_8), MTYPE);
            if (m == null || m.assets == null || m.assets.isEmpty()) throw new IllegalStateException("Empty manifest");
            return m;
        }
    }

    private static String sha256(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(bytes));
        } catch (IOException | NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    private static String hex(byte[] b) { StringBuilder sb = new StringBuilder(b.length*2); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }

    static final class Manifest { String repositorySlug; String revision; Map<String, Asset> assets; }
    static final class Asset { String sha256; long sizeBytes; String url; }
}