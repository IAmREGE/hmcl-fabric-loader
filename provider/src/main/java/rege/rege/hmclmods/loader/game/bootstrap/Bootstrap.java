package rege.rege.hmclmods.loader.game.bootstrap;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Ultra-lightweight bootstrapper (&lt; 30 KB) for HMCL Fabric Loader.
 * <p>
 * Automatically reads dependency manifests generated at build time,
 * allows dynamic version overrides via system properties, and downloads/caches
 * dependencies on demand from mirrors.
 */
public final class Bootstrap {
    private static final String
    MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
    private static final String
    BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/";

    private static final String[] ADD_OPENS = {
        "java.base/java.lang",
        "java.base/java.lang.reflect",
        "java.base/jdk.internal.loader",
        "javafx.base/com.sun.javafx.binding",
        "javafx.base/com.sun.javafx.event",
        "javafx.base/com.sun.javafx.runtime",
        "javafx.base/javafx.beans.property",
        "javafx.graphics/javafx.css",
        "javafx.graphics/javafx.stage",
        "javafx.graphics/javafx.scene",
        "javafx.graphics/com.sun.glass.ui",
        "javafx.graphics/com.sun.javafx.stage",
        "javafx.graphics/com.sun.javafx.util",
        "javafx.graphics/com.sun.prism",
        "javafx.controls/com.sun.javafx.scene.control",
        "javafx.controls/com.sun.javafx.scene.control.behavior",
        "javafx.graphics/com.sun.javafx.tk.quantum",
        "javafx.controls/javafx.scene.control",
        "javafx.controls/javafx.scene.control.skin",
        "jdk.attach/sun.tools.attach"
    };

    public static void main(String[] args) {
        System.out.println(
            "[HMCL-Fabric-Bootstrap] Initializing dynamic bootstrapper..."
        );

        Path cacheDir = resolveCacheDir();
        List<Path> classpathJars = new ArrayList<>();

        // Add this bootstrapper jar itself (contains HmclGameProvider and
        // GameProvider SPI)
        Path thisJar = getThisJarPath();
        if (thisJar != null) {
            classpathJars.add(thisJar);
        }

        List<Dependency> dependencies = loadDependencies();
        if (dependencies.isEmpty()) {
            System.err.println(
"[HMCL-Fabric-Bootstrap] Warning: No dependencies found in manifest!"
            );
        }

        // Verify or download dependencies
        for (Dependency dep : dependencies) {
            Path targetFile = cacheDir.resolve(dep.getRelativePath());
            try {
                ensureDependency(dep, targetFile);
                classpathJars.add(targetFile);
            } catch (IOException e) {
                System.err.println(
                    "[HMCL-Fabric-Bootstrap] Failed to download dependency " +
                    dep.getFileName()
                );
                e.printStackTrace(System.err);
                System.exit(1);
            }
        }

        System.out.println(
"[HMCL-Fabric-Bootstrap] All dependencies verified. Launching HMCL via KnotClient..."
        );
        launchKnot(classpathJars, args);
    }

    /**
     * Loads the build-time dependency manifest with support for runtime
     * version overrides.
     */
    private static List<Dependency> loadDependencies() {
        List<Dependency> list = new ArrayList<>();
        try (InputStream in = Bootstrap.class.getResourceAsStream(
            "/META-INF/hmcl-fabric-dependencies.txt"
        )) {
            if (in != null) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)
                );
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split("\t");
                    if (parts.length >= 5) {
                        String group = parts[0];
                        String name = parts[1];
                        String version = parts[2];
                        String sha1 = parts[3];
                        String repo = parts[4];

                        // Allow dynamic override via system properties:
                        // e.g. -Dfabric.loader.version=0.19.6,
                        //      -Dfabric.sponge-mixin.version=...
                        String propKey =
                        "fabric." + name.replace('-', '.') + ".version";
                        String override = System.getProperty(propKey);
                        if (override == null) {
                            override =
                            System.getProperty("fabric." + name + ".version");
                        }
                        if (override == null && "fabric-loader".equals(name) &&
                            (override = System.getProperty(
                            "fabric.loader.version"
                        )) == null) {
                            override = System.getProperty("fabric.version");
                        }

                        if (override != null && !override.trim().isEmpty()) {
                            System.out.printf(
"[HMCL-Fabric-Bootstrap] Overriding %s version: %s -> %s%n",
name, version, override.trim()
                            );
                            version = override.trim();
                            // Dynamically fetch or skip SHA1 check
                            // for custom versions
                            sha1 = null;
                        }

                        list.add(new Dependency(
                            group, name, version, sha1, repo
                        ));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println(
                "[HMCL-Fabric-Bootstrap] Error loading dependencies manifest: "
                + e.getMessage()
            );
        }

        return list;
    }

    private static Path resolveCacheDir() {
        // 1. Explicit system property -Dfabric.cache.dir or
        //    -Dhmcl.dependencies.dir
        String explicit = System.getProperty("fabric.cache.dir");
        if (explicit == null || explicit.trim().isEmpty()) {
            explicit = System.getProperty("hmcl.dependencies.dir");
        }
        if (explicit != null && !explicit.trim().isEmpty()) {
            return Paths.get(explicit.trim()).toAbsolutePath().normalize();
        }

        // 2. HMCL native dependencies dir (property or environment variable)
        String hmclNativeDeps = System.getProperty("hmcl.dependencies.dir");
        if (hmclNativeDeps == null || hmclNativeDeps.trim().isEmpty()) {
            hmclNativeDeps = System.getenv("HMCL_DEPENDENCIES_DIR");
        }
        if (hmclNativeDeps != null && !hmclNativeDeps.trim().isEmpty()) {
            return Paths.get(hmclNativeDeps.trim())
                   .toAbsolutePath().normalize();
        }

        // 3. HMCL home dir (property or environment variable)
        String hmclDir = System.getProperty("hmcl.dir");
        if (hmclDir == null || hmclDir.trim().isEmpty()) {
            hmclDir = System.getenv("HMCL_LOCAL_HOME");
        }
        return Paths.get(
            hmclDir != null && !hmclDir.trim().isEmpty() ? hmclDir.trim() :
            ".hmcl", "dependencies"
        ).toAbsolutePath().normalize();
    }

    private static Path getThisJarPath() {
        try {
            Path path = Paths.get(Bootstrap.class.getProtectionDomain()
                                  .getCodeSource().getLocation().toURI())
                        .toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void ensureDependency(Dependency dep, Path targetFile)
    throws IOException {
        if (Files.isRegularFile(targetFile)) {
            if (dep.expectedSha1 != null && !dep.expectedSha1.isEmpty()) {
                if (verifySha1(targetFile, dep.expectedSha1)) {
                    return;
                }
            } else if (Files.size(targetFile) > 0) {
                return;
            }
            System.out.println(
                "[HMCL-Fabric-Bootstrap] SHA1 mismatch or invalid file for " +
                dep.getFileName() + ", re-downloading..."
            );
            Files.deleteIfExists(targetFile);
        }

        Files.createDirectories(targetFile.getParent());
        Path tempFile =
        targetFile.resolveSibling(targetFile.getFileName() + ".tmp");

        List<String> mirrors = new ArrayList<>();
        // Prefer Chinese mirror if user.country is CN
        boolean isChina =
        "CN".equalsIgnoreCase(System.getProperty("user.country", ""));
        if (isChina) {
            mirrors.add(BMCLAPI_MAVEN + dep.getRelativePath());
        }
        mirrors.add(dep.primaryRepo + dep.getRelativePath());
        if (!isChina) {
            mirrors.add(BMCLAPI_MAVEN + dep.getRelativePath());
        }
        if (!dep.primaryRepo.equals(MAVEN_CENTRAL)) {
            mirrors.add(MAVEN_CENTRAL + dep.getRelativePath());
        }

        boolean success = false;
        IOException lastEx = null;

        for (String urlStr : mirrors) {
            System.out.println(
                "[HMCL-Fabric-Bootstrap] Downloading " + dep.getFileName() +
                " from " + urlStr + "..."
            );
            try {
                downloadFile(urlStr, tempFile);
                boolean valid = false;
                if (dep.expectedSha1 != null && !dep.expectedSha1.isEmpty()) {
                    valid = verifySha1(tempFile, dep.expectedSha1);
                } else {
                    // Try to fetch remote .sha1 file
                    String remoteSha1 = fetchRemoteSha1(urlStr + ".sha1");
                    valid = remoteSha1 != null && !remoteSha1.isEmpty() ?
                            verifySha1(tempFile, remoteSha1) :
                            // Accept valid non-empty download
                            Files.size(tempFile) > 0;
                }

                if (valid) {
                    Files.move(tempFile, targetFile,
                               StandardCopyOption.REPLACE_EXISTING);
                    success = true;
                    break;
                } else {
                    Files.deleteIfExists(tempFile);
                    throw new IOException("Checksum mismatch after download");
                }
            } catch (IOException e) {
                lastEx = e;
                Files.deleteIfExists(tempFile);
            }
        }

        if (!success) {
            throw lastEx != null ? lastEx : new IOException(
                "All download mirrors failed for " + dep.getFileName()
            );
        }
    }

    private static String fetchRemoteSha1(String urlStr) {
        try {
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "HMCL-Fabric-Bootstrap/1.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[256];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        out.write(buf, 0, r);
                    }
                    String text = out.toString("UTF-8").trim();
                    if (text.length() >= 40) {
                        return text.substring(0, 40);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void downloadFile(String urlStr, Path target)
    throws IOException {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "HMCL-Fabric-Bootstrap/1.0");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP " + code + ' ' +
                                  conn.getResponseMessage());
        }

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[16384];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
        }
    }

    private static boolean verifySha1(Path file, String expectedSha1) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[16384];
                int read;
                while ((read = in.read(buf)) != -1) {
                    digest.update(buf, 0, read);
                }
            }
            byte[] digestBytes = digest.digest();
            StringBuilder sb = new StringBuilder(digestBytes.length * 2);
            for (byte b : digestBytes) {
                sb.append(String.format("%02x", Byte.valueOf(b)));
            }
            String actualSha1 = sb.toString();
            return actualSha1.equalsIgnoreCase(expectedSha1);
        } catch (Exception e) {
            return false;
        }
    }

    private static void launchKnot(List<Path> classpathJars, String[] args) {
        String javaExec = findCurrentJavaExecutable();

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExec);

        // Pass through all system properties
        for (Map.Entry<Object, Object> entry :
             System.getProperties().entrySet()) {
            Object k = entry.getKey();
            Object v = entry.getValue();
            if (k instanceof String && v instanceof String) {
                String key = (String)k;
                if (key.startsWith("hmcl.") || key.startsWith("fabric.") ||
                    key.startsWith("mixin.")) {
                    cmd.add("-D" + key + '=' + v);
                }
            }
        }

        // Add module opens required by JavaFX and HMCL on
        // modern JDKs (17/21/25+)
        for (String open : ADD_OPENS) {
            cmd.add("--add-opens=" + open + "=ALL-UNNAMED");
        }
        cmd.add("--enable-native-access=ALL-UNNAMED");

        // Build classpath string
        StringBuilder cp = new StringBuilder();
        for (int i = 0; i < classpathJars.size(); i++) {
            if (i > 0) {
                cp.append(File.pathSeparator);
            }
            cp.append(classpathJars.get(i).toAbsolutePath());
        }

        cmd.add("-cp");
        cmd.add(cp.toString());
        cmd.add("net.fabricmc.loader.impl.launch.knot.KnotClient");
        cmd.addAll(Arrays.asList(args));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            Process proc = pb.start();
            int exitCode = proc.waitFor();
            System.exit(exitCode);
        } catch (Exception e) {
            System.err.println(
                "[HMCL-Fabric-Bootstrap] Failed to launch Knot: " +
                e.getMessage()
            );
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static String findCurrentJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path binJava = Paths.get(javaHome, "bin", "java");
            if (Files.isRegularFile(binJava)) {
                return binJava.toString();
            }
            Path binJavaExe = Paths.get(javaHome, "bin", "java.exe");
            if (Files.isRegularFile(binJavaExe)) {
                return binJavaExe.toString();
            }
        }
        return "java";
    }
}
