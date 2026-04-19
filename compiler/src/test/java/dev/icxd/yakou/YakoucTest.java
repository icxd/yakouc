package dev.icxd.yakou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YakoucTest {

    @Test
    void versionFlagExitsZero() {
        assertEquals(0, Yakouc.run(new String[] { "--version" }));
    }

    @Test
    void helpFlagExitsZero() {
        assertEquals(0, Yakouc.run(new String[] { "--help" }));
    }

    @Test
    void unknownArgumentExitsNonZero() {
        assertEquals(1, Yakouc.run(new String[] { "--not-a-real-flag" }));
    }

    @Test
    void noInputFilesExitsNonZero() {
        assertEquals(1, Yakouc.run(new String[] {}));
    }

    @Test
    void compileEmitsClassFile(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("mini.yk");
        Files.writeString(src, "fn main(): unit {}\n");
        assertEquals(0, Yakouc.run(new String[] { "-d", dir.toString(), src.toString() }));
        assertTrue(Files.exists(dir.resolve("yk/miniYk.class")));
        assertTrue(Files.exists(dir.resolve("yk/Main.class")));
    }

    @Test
    void compileOnlySkipsLink(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("mini.yk");
        Files.writeString(src, "fn main(): unit {}\n");
        assertEquals(0, Yakouc.run(new String[] { "-c", "-d", dir.toString(), src.toString() }));
        assertTrue(Files.exists(dir.resolve("yk/miniYk.class")));
        assertFalse(Files.exists(dir.resolve("yk/Main.class")));
    }

    @Test
    void linkFailsWhenNoTopLevelMain(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("lib.yk");
        Files.writeString(src, "fn f(): i32 { 1 }\n");
        assertEquals(1, Yakouc.run(new String[] { "-d", dir.toString(), src.toString() }));
    }

    @Test
    void linkFailsWhenMultipleMains(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.yk"), "fn main(): unit {}\n");
        Files.writeString(dir.resolve("b.yk"), "fn main(): unit {}\n");
        assertEquals(
                1,
                Yakouc.run(new String[] {
                        "-d", dir.toString(), dir.resolve("a.yk").toString(), dir.resolve("b.yk").toString()
                }));
    }

    @Test
    void compilesJavaInstanceFieldAccess(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("jf.yk");
        try (InputStream in = getClass().getResourceAsStream("/java_instance_field.yk")) {
            Files.copy(in, src);
        }
        assertEquals(0, Yakouc.run(new String[] { "-c", "-d", dir.toString(), src.toString() }));
        assertTrue(Files.exists(dir.resolve("yk/jfYk.class")));
    }

    @Test
    void compilesJavaInstanceMethodCall(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("j.yk");
        try (InputStream in = getClass().getResourceAsStream("/java_instance_method.yk")) {
            Files.copy(in, src);
        }
        assertEquals(0, Yakouc.run(new String[] { "-c", "-d", dir.toString(), src.toString() }));
        assertTrue(Files.exists(dir.resolve("yk/jYk.class")));
    }

    @Test
    void compilesGenericConstructorCalls(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("g.yk");
        try (InputStream in = getClass().getResourceAsStream("/generic_ctor.yk")) {
            Files.copy(in, src);
        }
        assertEquals(0, Yakouc.run(new String[] { "-c", "-d", dir.toString(), src.toString() }));
        assertTrue(Files.exists(dir.resolve("yk/gYk.class")));
        assertTrue(Files.exists(dir.resolve("Box.class")));
    }

    @Test
    void launcherRuns(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("app.yk");
        Files.writeString(src, "fn main(): unit {}\n");
        assertEquals(0, Yakouc.run(new String[] { "-d", dir.toString(), src.toString() }));
        ProcessBuilder pb = new ProcessBuilder("java", "-cp", dir.toString(), "yk.Main");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        assertEquals(0, p.waitFor());
    }

    @Test
    void printTokensLexesAndExitsZero(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("mini.yk");
        Files.writeString(f, "fn main(): unit {}\n");
        var capture = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
            assertEquals(0, Yakouc.run(new String[] { "--print-tokens", f.toString() }));
        } finally {
            System.setOut(old);
        }
        String out = capture.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("KW_FN"));
        assertTrue(out.contains("EOF"));
    }

    @Test
    void parserCollectsSourcesAndDashD() {
        ArgumentParser.ParseResult pr = ArgumentParser.parse(
                new String[] { "-d", "out", "-c", "a.yk", "b.yk" });
        assertTrue(pr instanceof ArgumentParser.ParseResult.Ok);
        var ok = (ArgumentParser.ParseResult.Ok) pr;
        CompileOptions o = ok.options();
        assertTrue(o.compileOnly());
        assertEquals(Path.of("out").toAbsolutePath().normalize(), o.classOutputDir());
        assertEquals(2, o.sources().size());
        assertFalse(o.printTokens());
        assertFalse(o.printAst());
        assertFalse(o.checkResolve());
        assertFalse(o.checkTypes());
        assertTrue(o.sourceRoot().isEmpty());
    }

    @Test
    void mergesSourcesAtDifferentDirectoryDepths(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("src");
        Files.createDirectories(root.resolve("demo/nested"));
        Path shallow = root.resolve("demo/first.yk");
        Path deep = root.resolve("demo/nested/second.yk");
        String sup = "use java::lang::Object;\n";
        Files.writeString(shallow, sup + "class A(): Object;\n");
        Files.writeString(deep, sup + "class B(): Object;\n");
        Path out = dir.resolve("out");
        Files.createDirectories(out);
        assertEquals(
                0,
                Yakouc.run(new String[] {
                        "-c",
                        "--source-root",
                        root.toString(),
                        "-d",
                        out.toString(),
                        shallow.toString(),
                        deep.toString()
                }));
        assertTrue(Files.exists(out.resolve("demo/A.class")));
        assertTrue(Files.exists(out.resolve("demo/nested/B.class")));
    }

    @Test
    void mergesMultipleSourcesUnderSameImplicitPackage(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("src");
        Files.createDirectories(root.resolve("demo"));
        Path cls = root.resolve("demo/types.yk");
        Path client = root.resolve("demo/use_types.yk");
        Files.writeString(cls, "class Exported(): unit;\n");
        Files.writeString(client, "fn touch(): unit { Exported(); }\n");
        Path out = dir.resolve("out");
        Files.createDirectories(out);
        assertEquals(
                0,
                Yakouc.run(new String[] {
                        "-c",
                        "--source-root",
                        root.toString(),
                        "-d",
                        out.toString(),
                        cls.toString(),
                        client.toString()
                }));
        assertTrue(Files.exists(out.resolve("demo/Exported.class")));
    }

    @Test
    void sourceRootMapsDirectoriesToQualifier(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("src");
        Files.createDirectories(root.resolve("demo/pkg"));
        Path src = root.resolve("demo/pkg/hi.yk");
        Files.writeString(src, "use java::lang::Object;\nclass Hi(): Object;\n");
        Path out = dir.resolve("out");
        Files.createDirectories(out);
        assertEquals(
                0,
                Yakouc.run(new String[] {
                        "-c",
                        "--source-root",
                        root.toString(),
                        "-d",
                        out.toString(),
                        src.toString()
                }));
        assertTrue(Files.exists(out.resolve("demo/pkg/Hi.class")));
    }

    @Test
    void parserSetsPrintTokens() {
        ArgumentParser.ParseResult pr = ArgumentParser.parse(new String[] { "--print-tokens", "a.yk" });
        assertTrue(pr instanceof ArgumentParser.ParseResult.Ok);
        assertTrue(((ArgumentParser.ParseResult.Ok) pr).options().printTokens());
    }

    @Test
    void parserParsesSourceRoot() {
        ArgumentParser.ParseResult pr = ArgumentParser.parse(new String[] { "--source-root", "src/yk", "a.yk" });
        assertTrue(pr instanceof ArgumentParser.ParseResult.Ok);
        var o = ((ArgumentParser.ParseResult.Ok) pr).options();
        assertTrue(o.sourceRoot().isPresent());
        assertEquals(Path.of("src/yk").toAbsolutePath().normalize(), o.sourceRoot().get());
    }

    @Test
    void helpContainsUsageLine() {
        var buf = new ByteArrayOutputStream();
        Yakouc.printHelp(new PrintStream(buf, true, StandardCharsets.UTF_8));
        String s = buf.toString(StandardCharsets.UTF_8);
        assertTrue(s.contains("Usage: yakouc"));
        assertTrue(s.contains("--print-tokens"));
        assertTrue(s.contains("--print-ast"));
        assertTrue(s.contains("--resolve"));
        assertTrue(s.contains("--typecheck"));
        assertTrue(s.contains("--source-root"));
        assertFalse(s.isEmpty());
    }

    @Test
    void versionStringNonEmpty() {
        assertFalse(Yakouc.version().isEmpty());
    }

    @Test
    void mergedResolveDiagnosticNamesSourceFileForSpan(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("yk");
        Files.createDirectories(root.resolve("demo/sub"));
        Path x = root.resolve("demo/x.yk");
        Path y = root.resolve("demo/sub/y.yk");
        Files.writeString(x, "use java::lang::Object;\nclass X(): Object;\n");
        Files.writeString(y, "fn f(): unit { NoSuch(); }\n");
        var cap = new ByteArrayOutputStream();
        PrintStream old = System.err;
        try {
            System.setErr(new PrintStream(cap, true, StandardCharsets.UTF_8));
            assertEquals(
                    1,
                    Yakouc.run(new String[] {
                            "--resolve",
                            "--source-root",
                            root.toString(),
                            x.toString(),
                            y.toString()
                    }));
        } finally {
            System.setErr(old);
        }
        String err = cap.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("sub/y.yk"), err);
        assertTrue(err.contains("NoSuch"), err);
    }
}
