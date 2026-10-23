# HMCL Fabric Loader (`hmcl-fabric-loader`)

[English](#english) | [简体中文](#简体中文)

---

<a name="english"></a>
## English

A custom [Fabric Loader](https://docs.fabricmc.net/develop/loader/) implementation and multi-module Gradle project designed for [Hello Minecraft! Launcher (HMCL)](https://github.com/HMCL-dev/HMCL). It enables loading and running Fabric mods inside the HMCL desktop application via `KnotClassLoader` without modifying HMCL's original binaries.

### Project Structure

* **`:provider`**: Implements `HmclGameProvider` (`GameProvider` ID: `"hmcl"`). Can be built as a standalone offline FatJar (`hmcl-fabric-loader-1.0.0-all.jar`) or a lightweight on-demand bootstrapper (`hmcl-fabric-bootstrapper-1.0.0.jar`).
* **`hmcl-mod-menu`**: A standalone mod viewer inspired by Minecraft's Mod Menu, injecting a "Fabric Mods" section into HMCL's sidebar.
* **`hmcl-example-mod`**: A starter template for developing Fabric mods for unobfuscated HMCL.
* **`hmcl-i18n-merger`**: A foundational Fabric API mod for multi-mod i18n resource merging into HMCL's native `ResourceBundle`.

### Why Does It Support Future HMCL Versions Seamlessly?

1. **Automatic HMCL JAR Discovery**:
   * Scans paths in priority order: `-Dhmcl.jar=<path>` $\rightarrow$ `-Dfabric.gameJarPath=<path>` $\rightarrow$ System Classpath $\rightarrow$ **Current working directory scanning for `HMCL*.jar`**.
   * If multiple HMCL JARs exist in the directory, it automatically selects the newest one by last-modified time. Upgrading HMCL is as simple as dropping a new `HMCL-x.y.z.jar` into the directory.
2. **Dynamic `MANIFEST.MF` Main Class Resolution**:
   * In addition to built-in fallbacks (`org.jackhuang.hmcl.Main`, `org.jackhuang.hmcl.EntryPoint`, `org.jackhuang.hmcl.Launcher`, and `org.glavo.hmcl.*`), it reads `Main-Class` dynamically from `META-INF/MANIFEST.MF`, remaining compatible even if future releases rename entrypoint classes.
3. **Non-Invasive Version Extraction & SemVer Normalization**:
   * Resolves the version via `-Dfabric.gameVersion` / `-Dhmcl.version.override`, `Implementation-Version` in `MANIFEST.MF`, ASM bytecode parsing of `Metadata.class`, or filename regex—without prematurely loading HMCL classes.
   * Automatically normalizes four-digit versions (e.g., `3.5.9.254`) into valid SemVer (`3.5.9+254`).
4. **Self-Integrity Check Bypass & ClassLoader Binding**:
   * Automatically injects `-Dhmcl.self_integrity_check.disable=true` to prevent Mixin bytecode modifications from triggering HMCL update verification errors.
   * Sets the thread `ContextClassLoader` to `KnotClassLoader` before reflective startup, ensuring all JavaFX UI classes are transformed by Mixin.

### Unobfuscated Mixins (No Remap Needed)

Because HMCL is **unobfuscated**, compilation and runtime identifiers (classes, methods, descriptors) are identical:

1. **No `fabric-loom` or `refmap.json` Required**:
   * Mods use standard Gradle `java` plugins, adding `HMCL.jar` as a `compileOnly` dependency.
   * Omit `"refmap"` in `*.mixins.json`; Mixin injects bytecode using original names directly at runtime.
2. **Explicit `remap = false`**:
   * Specify `@Mixin(value = ..., remap = false)` and `@Inject(..., remap = false)` to instruct Mixin to skip mappings lookups.
3. **Crash Prevention with `require = 0` and `@Pseudo`**:
   * Set `"injectors": { "defaultRequire": 0 }` in mixin configs (or `@Inject(require = 0)`). If future HMCL versions change a non-critical method, Mixin logs a warning instead of crashing HMCL.

### Building & Running

1. **Build All Artifacts**:
   ```bash
   ./gradlew assemble
   ```
   * **Standalone Loader (FatJar, fully offline)**: `provider/build/libs/hmcl-fabric-loader-1.0.0-all.jar` (~6.0 MB)
   * **Lightweight Bootstrapper (downloads dependencies on first run)**: `provider/build/libs/hmcl-fabric-bootstrapper-1.0.0.jar` (~30 KB)

2. **Launch HMCL (Zero Configuration)**:
   Place the loader JAR and your `HMCL-x.y.z.jar` in the same directory, and put mods into `.hmcl/mods/` (or `mods/`):
   ```bash
   java -jar hmcl-fabric-loader-1.0.0-all.jar
   ```
   Or with the lightweight bootstrapper:
   ```bash
   java -jar hmcl-fabric-bootstrapper-1.0.0.jar
   ```
   Or specify the HMCL JAR path explicitly:
   ```bash
   java -Dhmcl.jar=/path/to/HMCL.jar -jar hmcl-fabric-loader-1.0.0-all.jar
   ```

---

<a name="简体中文"></a>
## 简体中文

为 [Hello Minecraft! Launcher (HMCL)](https://github.com/HMCL-dev/HMCL) 定制的 [Fabric Loader](https://docs.fabricmc.net/develop/loader/) 引导器与多模块模组开发工程。允许在无需修改 HMCL 原始二进制文件的前提下，通过 `KnotClassLoader` 在 HMCL 桌面应用内部无缝加载并运行 Fabric 模组。

### 项目结构

* **`:provider`**：实现 `HmclGameProvider`（`GameProvider` ID 为 `"hmcl"`），支持打包为离线 FatJar（`hmcl-fabric-loader-1.0.0-all.jar`）或约 30 KB 轻量级引导器（`hmcl-fabric-bootstrapper-1.0.0.jar`）。
* **`hmcl-mod-menu`**：参考 Minecraft Mod Menu 实现的独立 HMCL 模组浏览器，在左侧边栏“通用”分类下无缝注入“Fabric 模组”管理界面。
* **`hmcl-example-mod`**：面向未混淆 HMCL 的标准示例 Fabric Mod 与 Mixin 模组模板工程。
* **`hmcl-i18n-merger`**：专为 HMCL 打造的 Fabric 基础国际化合并模组，打通多模组与 HMCL 原生 `ResourceBundle`。

### 为什么后续版本的 HMCL 也能直接工作？

1. **自动扫描并识别新版 HMCL JAR**：
   * 启动时按 `-Dhmcl.jar=<path>` $\rightarrow$ `-Dfabric.gameJarPath=<path>` $\rightarrow$ 系统 Classpath $\rightarrow$ **当前工作目录自动扫描 `HMCL*.jar`** 的顺序查找。
   * 当目录下存在多个 HMCL JAR 时，自动按最后修改时间优先选择最新版本，升级 HMCL 只需将新版 `HMCL-x.y.z.jar` 放入同目录即可。
2. **动态读取 `MANIFEST.MF` 主类（`Main-Class`）**：
   * 除内置支持 `org.jackhuang.hmcl.Main`、`org.jackhuang.hmcl.EntryPoint`、`org.jackhuang.hmcl.Launcher` 及 `org.glavo.hmcl.*` 外，优先从目标 HMCL JAR 的 `META-INF/MANIFEST.MF` 动态读取 `Main-Class`，即使后续版本重命名入口类也能正常启动。
3. **无侵入多级版本号提取与 SemVer 规范化**：
   * 依次通过 `-Dfabric.gameVersion` / `-Dhmcl.version.override`、`META-INF/MANIFEST.MF` 的 `Implementation-Version`、ASM 字节码解析 `Metadata.class`（同时支持字段常量与静态块赋值）、以及文件名正则提取版本号，避免提前加载 HMCL 类破坏 Mixin。
   * 自动将四段式版本号（如 `3.5.9.254`）规范化为标准的 SemVer 格式（`3.5.9+254`）。
4. **自动禁用自校验并绑定 `KnotClassLoader`**：
   * 默认注入 `-Dhmcl.self_integrity_check.disable=true`，防止 Mixin 修改字节码后触发 HMCL 更新自校验报错。
   * 在反射启动前将当前线程 ContextClassLoader 设为 `KnotClassLoader`，确保 JavaFX UI 线程加载的所有类均经过 Mixin 转换。

### 未混淆环境下 Mixin 的处理方式（无需 Remap）

由于 HMCL 本身**未混淆**，编译期与运行期的类名、方法名、描述符完全一致：

1. **无需 `fabric-loom` 与 `refmap.json`**：
   * 模组工程直接使用标准 Gradle `java` 插件，将 `HMCL.jar` 放入 `libs/` 作为 `compileOnly` 依赖即可。
   * 在 `*.mixins.json` 中**不配置 `"refmap"` 字段**，Mixin 运行期直接使用注解中的原始名称进行字节码注入。
2. **在 `@Mixin` 与 `@Inject` 上声明 `remap = false`**：
   * 在每个 Mixin 类上添加 `@Mixin(value = ..., remap = false)`（以及 `@Inject(..., remap = false)`），显式告知编译期注解处理器与运行期 Mixin 引擎跳过混淆映射表查找。
3. **面向未来版本的防崩溃策略（`require = 0` 与 `@Pseudo`）**：
   * 在 `*.mixins.json` 中设置 `"injectors": { "defaultRequire": 0 }`（或在 `@Inject` 中显式设置 `require = 0`）。当未来版本 HMCL 重构了某个非核心方法签名时，仅打印日志警告而不会导致启动器崩溃。

### 构建与运行

1. **构建引导器**：
   ```bash
   ./gradlew assemble
   ```
   * **独立引导器（FatJar，离线开箱即用）**：`provider/build/libs/hmcl-fabric-loader-1.0.0-all.jar` (~6.0 MB)
   * **轻量引导器（Bootstrapper，按需下载依赖）**：`provider/build/libs/hmcl-fabric-bootstrapper-1.0.0.jar` (~24 KB)

2. **启动 HMCL（零配置方式）**：
   将引导器与任意版本的 `HMCL-x.y.z.jar` 放在同一目录，并将模组放入 `.hmcl/mods/`（或同目录的 `mods/`）文件夹中：
   ```bash
   java -jar hmcl-fabric-loader-1.0.0-all.jar
   ```
   或者使用轻量 Bootstrapper：
   ```bash
   java -jar hmcl-fabric-bootstrapper-1.0.0.jar
   ```
   或者通过 `-Dhmcl.jar` 显式指定 HMCL 路径：
   ```bash
   java -Dhmcl.jar=/path/to/HMCL.jar -jar hmcl-fabric-loader-1.0.0-all.jar
   ```
