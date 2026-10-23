package rege.rege.hmclmods.loader.game;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.metadata.ContactInformationImpl;
import net.fabricmc.loader.impl.util.Arguments;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.version.VersionParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Future-proof Fabric Loader {@link GameProvider} implementation for Hello
 * Minecraft! Launcher (HMCL).
 * <p>
 * Key features:
 * <ul>
 *   <li>Game ID: {@code "hmcl"}</li>
 *   <li>Unobfuscated target: Mixin transforms HMCL classes directly by their
 *       source names without remapping or {@code refmap.json}. Only
 *       {@link BuiltinTransform#CLASS_TWEAKS} is enabled via
 *       {@link #getBuiltinTransforms(String)} so mod-supplied access wideners
 *       work correctly.</li>
 *   <li>Forward compatibility with future HMCL versions:
 *       <ul>
 *         <li>Dynamically discovers the HMCL JAR from {@code -Dhmcl.jar},
 *             {@code -Dfabric.gameJarPath}, system classpath, or by scanning
 *             the working directory for {@code HMCL*.jar}.</li>
 *         <li>Dynamically resolves HMCL's {@code Main-Class} from
 *             {@code META-INF/MANIFEST.MF} if future HMCL releases rename
 *             {@code EntryPoint} / {@code Main}.</li>
 *         <li>Extracts HMCL's version via JAR Manifest
 *             {@code Implementation-Version}, ASM bytecode inspection of
 *             {@code Metadata.class}, or JAR filename without prematurely
 *             loading HMCL classes on the parent classloader.</li>
 *         <li>Automatically disables HMCL's self-integrity check
 *             ({@code -Dhmcl.self_integrity_check.disable=true}) so bytecode
 *             modifications via Mixin do not trigger update verification
 *             errors.</li>
 *       </ul>
 *   </li>
 * </ul>
 */
public class HmclGameProvider implements GameProvider {
    public static final String GAME_ID = "hmcl";
    public static final String GAME_NAME = "Hello Minecraft! Launcher";

    private static final String METADATA_CLASS_INTERNAL =
    "org/jackhuang/hmcl/Metadata.class";
    private static final String METADATA_CLASS_NAME =
    "org.jackhuang.hmcl.Metadata";

    /**
     * Known HMCL entrypoints across historical, current, and potential future
     * versions.<p>
     * Note: {@code EntryPoint} is prioritized over {@code Main} because
     * {@code Main} in HMCL checks the call stack to detect
     * "nested applications" (for self-update migration); if {@code Main.main}
     * is invoked reflectively from Fabric Loader, HMCL incorrectly believes it
     * is being migrated and triggers self-update!
     */
    private static final List<String> KNOWN_ENTRYPOINTS = Arrays.asList(
        "org.jackhuang.hmcl.EntryPoint",
        "org.glavo.hmcl.EntryPoint",
        "org.jackhuang.hmcl.Main",
        "org.glavo.hmcl.Main",
        "org.jackhuang.hmcl.Launcher",
        "org.glavo.hmcl.Launcher"
    );

    /**
     * Marker class files used to detect all HMCL modules (HMCL, HMCLCore,
     * HMCLBoot) so KnotClassLoader unlocks and Mixin can transform classes
     * across every HMCL submodule.
     */
    private static final List<String> HMCL_MODULE_MARKERS = Arrays.asList(
        "org/jackhuang/hmcl/Metadata.class",
        "org/jackhuang/hmcl/EntryPoint.class",
        "org/jackhuang/hmcl/Launcher.class",
        "org/jackhuang/hmcl/Main.class",
        "org/jackhuang/hmcl/util/Lang.class"
    );

    private static final Pattern VERSION_IN_FILENAME =
    Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+){0,3}(?:-[A-Za-z0-9._-]+)?)");

    private final GameTransformer transformer = new GameTransformer();
    private Arguments arguments;
    private String entrypoint;
    private Path launchDir;
    private final List<Path> gameJars = new ArrayList<>();
    private String rawVersion = "0.0.0";
    private String normalizedVersion = "0.0.0";

    @Override
    public String getGameId() {
        return GAME_ID;
    }

    @Override
    public String getGameName() {
        return GAME_NAME;
    }

    @Override
    public String getRawGameVersion() {
        return this.rawVersion;
    }

    @Override
    public String getNormalizedGameVersion() {
        return this.normalizedVersion;
    }

    @Override
    public Collection<BuiltinMod> getBuiltinMods() {
        Map<String, String> gameContactMap = new HashMap<>();
        gameContactMap.put("homepage", "https://hmcl.huangyuhui.net/");
        gameContactMap.put("sources", "https://github.com/HMCL-dev/HMCL");
        gameContactMap
        .put("issues", "https://github.com/HMCL-dev/HMCL/issues");

        BuiltinModMetadata.Builder metadata = new BuiltinModMetadata.Builder(
            this.getGameId(), this.getNormalizedGameVersion()
        ).setName(this.getGameName())
        .addAuthor("huanghongxun", Collections.emptyMap())
        .addContributor("HMCL Contributors", Collections.emptyMap())
        .setContact(new ContactInformationImpl(gameContactMap))
        .setDescription(
"A Minecraft Launcher which is multi-functional, cross-platform and popular"
        ).addIcon(32, "assets/img/icon.png")
        .addLicense("GPL-3.0-or-later");

        return Collections.singletonList(
            new BuiltinMod(this.gameJars, metadata.build())
        );
    }

    @Override
    public String getEntrypoint() {
        return this.entrypoint;
    }

    @Override
    public Path getLaunchDirectory() {
        return this.launchDir == null ? Paths.get(".") : this.launchDir;
    }

    @Override
    public boolean requiresUrlClassLoader() {
        return false;
    }

    /**
     * Returns the set of built-in bytecode transforms that Fabric Loader
     * should apply to the given class.
     * <p>
     * For HMCL (unobfuscated, non-Minecraft target):
     * <ul>
     *   <li>{@link BuiltinTransform#CLASS_TWEAKS} is always enabled so
     *       mod-supplied access wideners work.</li>
     *   <li>{@link BuiltinTransform#STRIP_ENVIRONMENT} and
     *       {@link BuiltinTransform#WIDEN_ALL_PACKAGE_ACCESS} are
     *       intentionally omitted — they are designed for obfuscated Minecraft
     *       and have no meaningful effect on unobfuscated HMCL classes.</li>
     * </ul>
     */
    @Override
    public Set<BuiltinTransform> getBuiltinTransforms(String className) {
        // Only apply class tweaks (access wideners from mods); skip
        // environment stripping and package access widening which are
        // Minecraft-specific concerns.
        return className.startsWith("org.jackhuang.hmcl.") ||
               className.startsWith("org.glavo.hmcl.") ?
               EnumSet.of(BuiltinTransform.CLASS_TWEAKS) :
               Collections.emptySet();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean locateGame(FabricLauncher launcher, String[] args) {
        this.arguments = new Arguments();
        this.arguments.parse(args);

        // Disable HMCL's self-integrity check by default so Mixin class
        // transformations do not trigger errors
        if (System.getProperty("hmcl.self_integrity_check.disable") == null) {
            System.setProperty("hmcl.self_integrity_check.disable", "true");
        }

        // Determine launch directory: respect -Dhmcl.dir, --gameDir, or
        // default to .hmcl
        this.launchDir = resolveLaunchDirectory(this.arguments);

        // Pre-create launch directory and mods directory recursively with
        // Files.createDirectories. Fabric Loader's DirectoryModCandidateFinder
        // uses Files.createDirectory (single level), which throws
        // NoSuchFileException and crashes if the parent directory (e.g. .hmcl)
        // does not exist yet.
        try {
            Files.createDirectories(this.launchDir.resolve("mods"));
        } catch (IOException e) {
            Log.warn(
                LogCategory.GAME_PROVIDER,
                "Failed to create mods directory %s: %s",
                this.launchDir.resolve("mods"), e.getMessage()
            );
        }

        // If a ./mods folder also exists in the working directory (and differs
        // from launchDir/mods), register it via fabric.addMods so mods placed
        // in the root directory are also seamlessly discovered
        Path rootMods = Paths.get("mods").toAbsolutePath().normalize();
        if (Files.isDirectory(rootMods) && !rootMods.equals(
            this.launchDir.resolve("mods").toAbsolutePath().normalize()
        )) {
            String existingAddMods = System.getProperty("fabric.addMods");
            System.setProperty(
                "fabric.addMods",
                existingAddMods == null || existingAddMods.isEmpty() ?
                rootMods.toString() :
                existingAddMods + File.pathSeparator + rootMods
            );
        }

        // Build candidate lookup paths:
        // 1. -Dhmcl.jar or -Dfabric.gameJarPath
        // 2. Classpath entries from FabricLauncher
        // 3. Auto-discovered HMCL*.jar in the launch/working directory
        //    (allows standalone `java -jar hmcl-fabric-loader-all.jar`)
        List<Path> lookupPaths = collectLookupPaths(launcher, this.launchDir);

        // Collect all entrypoints to probe (including custom -Dhmcl.mainClass
        // and dynamic Manifest Main-Class)
        List<String> candidateEntrypoints =
        buildCandidateEntrypoints(lookupPaths);

        EntrypointMatch match =
        findFirstEntrypoint(lookupPaths, candidateEntrypoints);
        if (match == null) {
            return false;
        }

        this.entrypoint = match.className;

        Set<Path> discoveredJars = new LinkedHashSet<>();
        discoveredJars.add(match.path);
        for (Path candidate : lookupPaths) {
            if (isHmclModule(candidate)) {
                discoveredJars.add(candidate);
            }
        }

        this.gameJars.clear();
        this.gameJars.addAll(discoveredJars);

        // Resolve HMCL version from system properties, Manifest,
        // Metadata.class bytecode, or JAR filename
        this.rawVersion = resolveHmclVersion(this.gameJars);
        this.normalizedVersion = normalizeVersion(this.rawVersion);

        return true;
    }

    @Override
    public void initialize(FabricLauncher launcher) {
        this.transformer.locateEntrypoints(launcher, this.gameJars);
    }

    @Override
    public GameTransformer getEntrypointTransformer() {
        return this.transformer;
    }

    @Override
    public void unlockClassPath(FabricLauncher launcher) {
        this.gameJars.forEach(launcher::addToClassPath);
    }

    @Override
    public void launch(ClassLoader loader) {
        String targetClass = this.entrypoint;

        // Ensure JavaFX and HMCL threads use KnotClassLoader as context class
        // loader so Mixins apply everywhere
        Thread.currentThread().setContextClassLoader(loader);

        // Initialize Fabric mods before starting HMCL so ModInitializer /
        // ClientModInitializer run automatically
        FabricLoaderImpl fabricLoader = FabricLoaderImpl.INSTANCE;
        fabricLoader.prepareModInit(this.launchDir, null);
        fabricLoader.invokeEntrypoints(
            "main", ModInitializer.class, ModInitializer::onInitialize
        );
        fabricLoader.invokeEntrypoints(
            "client", ClientModInitializer.class,
            ClientModInitializer::onInitializeClient
        );

        try {
            Class<?> mainClass = loader.loadClass(targetClass);
            Method mainMethod = mainClass.getMethod("main", String[].class);
            mainMethod.setAccessible(true);
            mainMethod.invoke(null, (Object)this.arguments.toArray());
        } catch (InvocationTargetException e) {
            throw new FormattedException("HMCL has crashed!", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new FormattedException("Failed to start HMCL!", e);
        }
    }

    @Override
    public Arguments getArguments() {
        return this.arguments;
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        return this.arguments == null ? new String[0] :
               this.arguments.toArray();
    }

    private static final class EntrypointMatch {
        final String className;
        final Path path;

        EntrypointMatch(String className, Path path) {
            this.className = className;
            this.path = path;
        }
    }

    /**
     * Locates the first matching entrypoint class across all candidate paths
     * (both JARs and IDE class directories) without relying on internal
     * GameProviderHelper overloads.
     */
    private static EntrypointMatch
    findFirstEntrypoint(List<Path> lookupPaths, List<String> entrypoints) {
        for (String ep : entrypoints) {
            String internalPath = ep.replace('.', '/') + ".class";
            for (Path path : lookupPaths) {
                if (Files.isDirectory(path)) {
                    if (Files.isRegularFile(path.resolve(internalPath))) {
                        return new EntrypointMatch(ep, path);
                    }
                } else if (Files.isRegularFile(path)) {
                    try (ZipFile zip = new ZipFile(path.toFile())) {
                        if (zip.getEntry(internalPath) != null) {
                            return new EntrypointMatch(ep, path);
                        }
                    } catch (IOException ignored) {}
                }
            }
        }
        return null;
    }

    /**
     * Collects candidate paths from system properties, launcher classpath, and
     * working directory scan.
     */
    private static List<Path>
    collectLookupPaths(FabricLauncher launcher, Path workingDir) {
        Set<Path> paths = new LinkedHashSet<>();

        for (String propName :
             new String[]{"hmcl.jar", SystemProperties.GAME_JAR_PATH}) {
            String propVal = System.getProperty(propName);
            if (propVal != null && !propVal.trim().isEmpty()) {
                for (String rawSegment : propVal.split(File.pathSeparator)) {
                    String segment = rawSegment.trim();
                    // Strip optional surrounding quotes if passed via scripts
                    // or GUI launchers
                    if ((segment.startsWith("\"") && segment.endsWith("\"")) ||
                        (segment.startsWith("'") && segment.endsWith("'"))) {
                        segment =
                        segment.substring(1, segment.length() - 1).trim();
                    }
                    if (segment.isEmpty()) {
                        continue;
                    }
                    // Expand leading tilde (~) on UNIX-like environments
                    if (segment.startsWith("~" + File.separator) ||
                        segment.equals("~")) {
                        segment = System.getProperty("user.home", "") +
                                  segment.substring(1);
                    }

                    Path p = Paths.get(segment);
                    Path resolved = null;
                    if (Files.exists(p)) {
                        resolved = p.toAbsolutePath().normalize();
                    } else if (!p.isAbsolute() && workingDir != null &&
                               Files.exists(workingDir.resolve(p))) {
                        resolved =
                        workingDir.resolve(p).toAbsolutePath().normalize();
                    }

                    if (resolved != null) {
                        Log.info(
                            LogCategory.GAME_PROVIDER,
                            "Found HMCL JAR via -D%s: %s", propName, resolved
                        );
                        paths.add(resolved);
                    } else {
                        Log.warn(
                            LogCategory.GAME_PROVIDER,
                            "Specified HMCL JAR via -D%s was not found: %s",
                            propName, rawSegment
                        );
                    }
                }
            }
        }

        for (Path cp : launcher.getClassPath()) {
            paths.add(cp.toAbsolutePath().normalize());
        }

        // Scan working directory and current directory for HMCL*.jar so users
        // can upgrade HMCL by replacing the JAR
        for (Path dir : new LinkedHashSet<>(Arrays.asList(
            workingDir, Paths.get(".").toAbsolutePath().normalize())
        )) {
            if (dir != null && Files.isDirectory(dir)) {
                paths.addAll(scanDirectoryForHmclJars(dir));
            }
        }

        return new ArrayList<>(paths);
    }

    /**
     * Scans a directory for HMCL JAR files, sorted newest-first so newly
     * downloaded HMCL versions take precedence.
     */
    private static List<Path> scanDirectoryForHmclJars(Path dir) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> stream =
             Files.newDirectoryStream(dir, "*.{jar,JAR,exe,EXE}")) {
            for (Path entry : stream) {
                String fileName =
                entry.getFileName().toString().toLowerCase(Locale.ROOT);
                if (fileName.contains("fabric") ||
                    fileName.contains("provider") ||
                    fileName.contains("mixin")) {
                    continue;
                }
                if (isHmclModule(entry)) {
                    found.add(entry.toAbsolutePath().normalize());
                }
            }
        } catch (IOException ignored) {}

        found.sort(Comparator.<Path>comparingLong(p -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }).reversed());

        return found;
    }

    /**
     * Builds the ordered list of candidate main classes, supporting custom
     * overrides and future HMCL Manifest Main-Class changes.
     */
    private static List<String>
    buildCandidateEntrypoints(List<Path> lookupPaths) {
        Set<String> entrypoints = new LinkedHashSet<>();

        String customMain = System.getProperty("hmcl.mainClass");
        if (customMain != null && !customMain.trim().isEmpty()) {
            entrypoints.add(customMain.trim());
        }

        // Inspect META-INF/MANIFEST.MF Main-Class of any detected HMCL JAR
        // first (future-proof if HMCL renames Main)
        for (Path path : lookupPaths) {
            if (isHmclModule(path)) {
                String manifestMain = readMainClassFromManifest(path);
                if (manifestMain != null && !manifestMain.isEmpty()) {
                    // In HMCL, org.jackhuang.hmcl.Main only wraps
                    // org.jackhuang.hmcl.EntryPoint. If Main is invoked
                    // reflectively, HMCL's UpdateHandler.isNestedApplication()
                    // falsely flags Fabric Loader as a migration wrapper and
                    // attempts self-update. Therefore, if Manifest points to
                    // Main, we check EntryPoint first.
                    if ("org.jackhuang.hmcl.Main".equals(manifestMain)) {
                        entrypoints.add("org.jackhuang.hmcl.EntryPoint");
                    } else if ("org.glavo.hmcl.Main".equals(manifestMain)) {
                        entrypoints.add("org.glavo.hmcl.EntryPoint");
                    }
                    entrypoints.add(manifestMain);
                }
            }
        }

        entrypoints.addAll(KNOWN_ENTRYPOINTS);
        return new ArrayList<>(entrypoints);
    }

    private static String readMainClassFromManifest(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                try (JarFile jarFile = new JarFile(path.toFile())) {
                    Manifest manifest = jarFile.getManifest();
                    if (manifest != null) {
                        String mainClass =
                        manifest.getMainAttributes()
                         .getValue(Attributes.Name.MAIN_CLASS);
                        if (mainClass != null && (
                            mainClass.startsWith("org.jackhuang.hmcl") ||
                            mainClass.startsWith("org.glavo.hmcl")
                        )) {
                            return mainClass.trim();
                        }
                    }
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /**
     * Checks whether a classpath or directory entry belongs to HMCL (HMCL,
     * HMCLCore, or HMCLBoot).
     */
    private static boolean isHmclModule(Path path) {
        if (Files.isDirectory(path)) {
            for (String marker : HMCL_MODULE_MARKERS) {
                if (Files.exists(path.resolve(marker))) {
                    return true;
                }
            }
            return Files.isDirectory(path.resolve("org/jackhuang/hmcl")) ||
                   Files.isDirectory(path.resolve("org/glavo/hmcl"));
        }
        if (Files.isRegularFile(path)) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                // Exclude our own provider jar
                if (zip.getEntry(
                    "rege/rege/hmclmods/loader/game/HmclGameProvider.class"
                ) != null) {
                    return false;
                }
                for (String marker : HMCL_MODULE_MARKERS) {
                    if (zip.getEntry(marker) != null) {
                        return true;
                    }
                }
                ZipEntry manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");
                if (manifestEntry != null) {
                    try (InputStream is = zip.getInputStream(manifestEntry)) {
                        Manifest manifest = new Manifest(is);
                        String mainClass =
                        manifest.getMainAttributes()
                        .getValue(Attributes.Name.MAIN_CLASS);
                        if (mainClass != null && (
                            mainClass.startsWith("org.jackhuang.hmcl") ||
                            mainClass.startsWith("org.glavo.hmcl")
                        )) {
                            return true;
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    /**
     * Resolves HMCL's working/data directory (-Dhmcl.dir, HMCL_LOCAL_HOME,
     * --gameDir, or .hmcl).
     */
    private static Path resolveLaunchDirectory(Arguments arguments) {
        String hmclDirProp = System.getProperty("hmcl.dir");
        if (hmclDirProp == null || hmclDirProp.isEmpty()) {
            hmclDirProp = System.getenv("HMCL_LOCAL_HOME");
        }
        if (hmclDirProp != null && !hmclDirProp.isEmpty()) {
            return Paths.get(hmclDirProp).toAbsolutePath().normalize();
        }
        String argGameDir = arguments.get("gameDir");
        if (argGameDir != null && !argGameDir.isEmpty()) {
            return Paths.get(argGameDir).toAbsolutePath().normalize();
        }
        return Paths.get(".hmcl").toAbsolutePath().normalize();
    }

    /**
     * Extracts HMCL's version without prematurely loading HMCL classes on the
     * parent classloader.
     * Priority:
     * <ol>
     *   <li>{@code -Dfabric.gameVersion} or {@code -Dhmcl.version.override}
     *       system property</li>
     *   <li>{@code Implementation-Version} in {@code META-INF/MANIFEST.MF} of
     *       the HMCL JAR</li>
     *   <li>ConstantValue / &lt;clinit> of org.jackhuang.hmcl.Metadata.VERSION
     *       via ASM {@link ClassReader}</li>
     *   <li>Version number pattern in the HMCL JAR filename (e.g.
     *       HMCL-3.16.4.jar)</li>
     *   <li>Reflective fallback on
     *       {@code org.jackhuang.hmcl.Metadata.VERSION}</li>
     * </ol>
     */
    private static String resolveHmclVersion(List<Path> hmclPaths) {
        String customVersion =
        System.getProperty(SystemProperties.GAME_VERSION);
        if (customVersion != null && !customVersion.trim().isEmpty()) {
            return customVersion.trim();
        }

        String hmclOverride = System.getProperty("hmcl.version.override");
        if (hmclOverride != null && !hmclOverride.trim().isEmpty()) {
            return hmclOverride.trim();
        }

        // 1. Read Implementation-Version from META-INF/MANIFEST.MF (standard
        //    in official HMCL builds)
        for (Path path : hmclPaths) {
            String manifestVersion = readVersionFromManifest(path);
            if (manifestVersion != null && !manifestVersion.isEmpty()) {
                return manifestVersion;
            }
        }

        // 2. Read Metadata.VERSION from class bytecode via ASM (avoids
        //    premature class initialization)
        for (Path path : hmclPaths) {
            String bytecodeVersion = readVersionFromMetadataBytecode(path);
            if (bytecodeVersion != null && !bytecodeVersion.isEmpty()) {
                return bytecodeVersion;
            }
        }

        // 3. Extract version from JAR filename (e.g. HMCL-3.16.4.jar) if
        //    future versions move Metadata
        for (Path path : hmclPaths) {
            if (Files.isRegularFile(path)) {
                String fileName = path.getFileName().toString();
                Matcher matcher = VERSION_IN_FILENAME.matcher(fileName);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }

        // 4. Fallback: reflectively read org.jackhuang.hmcl.Metadata.VERSION
        //    if accessible
        try {
            Class<?> metadataClass = Class.forName(
                METADATA_CLASS_NAME, true,
                HmclGameProvider.class.getClassLoader()
            );
            Object val = metadataClass.getDeclaredField("VERSION").get(null);
            if (val instanceof String && !((String)val).isEmpty()) {
                return (String)val;
            }
        } catch (Throwable ignored) {}

        return "0.0.0-unknown";
    }

    private static byte[] readAllBytes(InputStream inputStream)
    throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int nRead;
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    /**
     * Uses ASM to inspect {@code org/jackhuang/hmcl/Metadata.class} and
     * extract {@code VERSION} from either its field ConstantValue or its
     * {@code <clinit>} static initializer.
     */
    private static String readVersionFromMetadataBytecode(Path hmclPath) {
        byte[] classBytes = null;
        try {
            if (Files.isDirectory(hmclPath)) {
                Path classFile = hmclPath.resolve(METADATA_CLASS_INTERNAL);
                if (Files.isRegularFile(classFile)) {
                    classBytes = Files.readAllBytes(classFile);
                }
            } else if (Files.isRegularFile(hmclPath)) {
                try (ZipFile zip = new ZipFile(hmclPath.toFile())) {
                    ZipEntry entry = zip.getEntry(METADATA_CLASS_INTERNAL);
                    if (entry != null) {
                        try (InputStream is = zip.getInputStream(entry)) {
                            classBytes = readAllBytes(is);
                        }
                    }
                }
            }
        } catch (IOException ignored) {}

        if (classBytes == null) {
            return null;
        }

        final String[] foundVersion = new String[1];
        try {
            ClassReader reader = new ClassReader(classBytes);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(
                    int access, String name, String descriptor,
                    String signature, Object value
                ) {
                    if ("VERSION".equals(name) && value instanceof String &&
                        "Ljava/lang/String;".equals(descriptor)) {
                        foundVersion[0] = (String)value;
                    }
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(
                    int access, String name, String descriptor,
                    String signature, String[] exceptions
                ) {
                    if (foundVersion[0] == null && "<clinit>".equals(name)) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            private String lastLdcString;

                            @Override
                            public void visitLdcInsn(Object value) {
                                if (value instanceof String) {
                                    String str = (String)value;
                                    if (str.matches("^\\d+\\.\\d+.*")) {
                                        this.lastLdcString = str;
                                    }
                                }
                            }

                            @Override
                            public void visitFieldInsn(
                                int opcode, String owner, String fieldName,
                                String fieldDesc
                            ) {
                                if (opcode == Opcodes.PUTSTATIC &&
                                    owner.endsWith("/Metadata") &&
                                    "VERSION".equals(fieldName) &&
                                    this.lastLdcString != null &&
                                    foundVersion[0] == null) {
                                    foundVersion[0] = this.lastLdcString;
                                }
                            }
                        };
                    }
                    return null;
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable ignored) {}

        return foundVersion[0];
    }

    /**
     * Reads {@code Implementation-Version} from the HMCL JAR or directory
     * {@code META-INF/MANIFEST.MF}.
     */
    private static String readVersionFromManifest(Path hmclPath) {
        try {
            if (Files.isDirectory(hmclPath)) {
                Path manifestPath = hmclPath.resolve("META-INF/MANIFEST.MF");
                if (Files.isRegularFile(manifestPath)) {
                    try (InputStream is = Files.newInputStream(manifestPath)) {
                        Manifest manifest = new Manifest(is);
                        return extractManifestVersion(manifest);
                    }
                }
            } else if (Files.isRegularFile(hmclPath)) {
                try (JarFile jarFile = new JarFile(hmclPath.toFile())) {
                    Manifest manifest = jarFile.getManifest();
                    if (manifest != null) {
                        return extractManifestVersion(manifest);
                    }
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static String extractManifestVersion(Manifest manifest) {
        Attributes attrs = manifest.getMainAttributes();
        String mainClass = attrs.getValue(Attributes.Name.MAIN_CLASS);
        if (mainClass != null && !mainClass.startsWith("org.jackhuang.hmcl") &&
            !mainClass.startsWith("org.glavo.hmcl")) {
            return null;
        }
        String implVersion =
        attrs.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        if (implVersion != null && !implVersion.trim().isEmpty()) {
            return implVersion.trim();
        }
        String specVersion =
        attrs.getValue(Attributes.Name.SPECIFICATION_VERSION);
        if (specVersion != null && !specVersion.trim().isEmpty()) {
            return specVersion.trim();
        }
        return null;
    }

    /**
     * Normalizes any HMCL version string (such as {@code 3.5.9.254},
     * {@code 3.16.4}, or {@code 3.17.0.1-dev}) into a valid Semantic Version
     * string accepted by Fabric Loader's {@link VersionParser}.
     */
    private static String normalizeVersion(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "0.0.0";
        }
        String cleaned = raw.trim();
        if (cleaned.toLowerCase(Locale.ROOT).startsWith("hmcl-")) {
            cleaned = cleaned.substring(5);
        } else if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1);
        }

        try {
            VersionParser.parseSemantic(cleaned);
            return cleaned;
        } catch (VersionParsingException e) {
            // Convert 4+ segment numeric HMCL versions like "3.5.9.254" to
            // SemVer "3.5.9+254"
            String[] parts = cleaned.split("\\.", 4);
            if (parts.length == 4 && parts[0].matches("\\d+") &&
                parts[1].matches("\\d+") && parts[2].matches("\\d+")) {
                String buildMeta = parts[3].replaceAll("[^0-9A-Za-z.-]", "-");
                String candidate =
                parts[0] + '.' + parts[1] + '.' + parts[2] + '+' + buildMeta;
                try {
                    VersionParser.parseSemantic(candidate);
                    return candidate;
                } catch (VersionParsingException ignored) {}
            }
        }
        return "0.0.0";
    }
}
