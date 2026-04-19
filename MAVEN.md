# Maven integration

The build is a two-module reactor:

| Module | Artifact | Role |
|--------|----------|------|
| `compiler/` | `dev.icxd:yakou` | Compiler + shaded `yakouc` CLI |
| `yakou-maven-plugin/` | `dev.icxd:yakou-maven-plugin` | `compile-yk` mojo |

From the repository root:

```bash
mvn install
```

Installs the compiler and the plugin into the local repository.

## Using the plugin in another project

Put `.yk` files under `src/main/yk/` (any subdirectory). The Mojos pass **`--source-root`** as that directory, so **`src/main/yk/foo/bar/baz.yk`** gets an implicit **`foo::bar`** package prefix—no nested `pkg` blocks needed for path mirroring Java. Use **`pkg`** inside a file only for Rust-style nesting. The goal **`yakou:compile-yk`** runs in the **`process-classes`** phase (after `javac`) so compiled Java classes are on the classpath for Java interop.

```xml
<build>
  <plugins>
    <plugin>
      <groupId>dev.icxd</groupId>
      <artifactId>yakou-maven-plugin</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <executions>
        <execution>
          <goals>
            <goal>compile-yk</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

### Properties

| Property | Default | Meaning |
|----------|---------|---------|
| `yakou.skip` | `false` | Set `true` to skip Yakou compilation |
| `yakou.sourceDirectory` | `${project.basedir}/src/main/yk` | Root scanned for `*.yk` |
| `yakou.outputDirectory` | `${project.build.outputDirectory}` | Usually `target/classes` |
| `yakou.compileOnly` | `false` | `true` → passes `-c` (no `yk/Main` launcher; use for libraries) |

### Running the compiler module’s CLI from the repo

The compiler lives under `compiler/`. After `mvn -pl compiler package`, run:

```bash
java -jar compiler/target/yakouc.jar --help
```

Or from `compiler/`:

```bash
mvn exec:java -Dexec.args="--help"
```

## Mixed Java + Yakou

Because Yakou runs in **`process-classes`**, compile Java first (default), then Yakou sees `target/classes` and dependency JARs on `-cp`.

If the Yakou module is **library-only** (no single `fn main` entry), set **`yakou.compileOnly=true`** so linking does not require exactly one main.

## Paper / Minecraft sample

`examples/minecraft-plugin/` is a minimal **Paper** plugin written in **Yakou only**: a `class ... : JavaPlugin` in `src/main/yk/hello.yk` (package `dev::icxd::yakou::example::mc`) with `override fn onEnable(self)`; the Paper loader’s `main` is that emitted class (`dev.icxd.yakou.example.mc.HelloYakouPlugin`). The goal passes the **Maven compile classpath** (including `provided` deps such as `paper-api`) so types like `org::bukkit::plugin::java::JavaPlugin` resolve during typecheck and codegen. Set **`yakou.compileOnly=true`** when there is no top-level `fn main`.

Build (after `mvn install` at the repo root):

```bash
cd examples/minecraft-plugin
mvn package
```

Install **`examples/minecraft-plugin/target/hello-yakou-paper-1.0.0-SNAPSHOT.jar`** into your Paper server’s `plugins/` folder. Adjust **`paper.api.version`** in that `pom.xml` to match your server. Sources live under `src/main/yakou/`.

### Language server (Cursor / VS Code)

The language server completes **`java::`** paths using the running JDK’s module image and merges **`target/yakou-ls.classpath`** from Maven when present (dependency JARs). **`examples/maven-sample`** writes that file in **`generate-sources`** so third-party deps match the compile classpath.

After **`mvn generate-sources`** (or any build) in **`examples/maven-sample`**, choose one workflow:

1. **Recommended when editing from the repo root:** **File → Open Workspace from File…** and pick **`yakou.code-workspace`**. Its workspace settings point **`yakou.classpathFile`** at **`examples/maven-sample/target/yakou-ls.classpath`** (nested **`examples/maven-sample/.vscode/`** is **not** merged when you only open the root folder).

2. **Open Folder** on **`examples/maven-sample`** so **`.vscode/settings.json`** applies.

3. With a single root open on **`yakou`**, set **`yakou.classpathFile`** to **`${workspaceFolder}/examples/maven-sample/target/yakou-ls.classpath`**, or list JARs in **`yakou.classpath`**.

If **`classpathFile`** is set but missing, the extension warns; regenerate with **`mvn generate-sources`** in that module. Restart the Yakou language client after **`yakou.*`** changes if needed.
