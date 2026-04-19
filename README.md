# Yakou

Yakou is a programming language built on top of the Java Virtual Machine (JVM). It is built to get rid of the annoyances of working with Java. The language is a sort of hybrid between Rust, Java, and Kotlin, with a bit of Scala sprinkled in just for fun.

Currently, we have a fully functional compiler, a Visual Studio Code extension with auto-complete, inlay hints, syntax highlighting, etc. We also have full Maven support using the [yakou-maven-plugin](yakou-maven-plugin) (see [examples/maven-sample](examples/maven-sample/)).

## Building

### Compiler

To build the compiler, simply run `mvn -pl compiler,yakou-maven-plugin clean package` from the root directory. 
Once the compiler and Maven plugin are built, you can install them using `mvn -pl compiler,yakou-maven-plugin -DskipTests install`.
Then, to invoke the compiler, you can run `java -jar compiler/target/yakouc.jar --help`.

### Language Server

To build the language server, make sure to build all the Maven projects first using `maven -pl compiler,language-server,yakou-maven-plugin clean package`.
Then, to package the extension into a `.vsix` file, run `cd editors/vscode && npm run package`.
After packaging the extension, simply go to VS Code, press `CTRL+SHIFT+P` or `CMD+SHIFT+P`, type "Install from VSIX" into the search bar, select the "Extensions: Install from VSIX..." option, then find and select `yakou-lang-0.1.0.vsix`.
Once the extension is installed, simply restart VS Code.

## Getting started

Start off by creating a base Maven project, something like this:

```
myProject
│   pom.xml
└───.vscode
│   │   settings.json
└───src
    └───main
        └───yakou
            │   main.yk
```

The most minimal `pom.xml` file can be found [here](examples/maven-sample/pom.xml).

```json
// settings.json

{
  "yakou.classpathFile": "${workspaceFolder}/target/yakou-ls.classpath",
  "yakou.sourceRoot": "${workspaceFolder}/src/main/yakou",
  "java.configuration.updateBuildConfiguration": "automatic"
}
```

```yakou
// main.yk
use java::lang::System;

fn main(): unit {
  System::out.println("Hello, Yakou!")
}
```

You can find a more concrete sample project [here](examples/maven-sample/)

# License

Yakou is licensed under the [GNU General Public License, Version 3](LICENSE).
