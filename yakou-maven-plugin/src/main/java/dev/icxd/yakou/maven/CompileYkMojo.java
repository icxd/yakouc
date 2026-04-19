package dev.icxd.yakou.maven;

import dev.icxd.yakou.Yakouc;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Compiles {@code .yk} sources under {@link #sourceDirectory} into the same
 * output
 * directory tree as Java ({@code target/classes}) so jars and tests see
 * generated
 * classes. Runs in {@link LifecyclePhase#PROCESS_CLASSES} so bytecode from
 * {@code javac} is already on the compile classpath for Java interop.
 */
@Mojo(name = "compile-yk", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class CompileYkMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Root directory scanned recursively for {@code *.yk} (default:
     * {@code src/main/yakou}).
     */
    @Parameter(defaultValue = "${project.basedir}/src/main/yakou", property = "yakou.sourceDirectory", required = true)
    private File sourceDirectory;

    /**
     * Where to emit {@code .class} files (normally {@code target/classes}).
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", property = "yakou.outputDirectory", required = true)
    private File outputDirectory;

    /**
     * When {@code true}, passes {@code -c} (no {@code yk/Main} launcher; use for
     * libraries).
     */
    @Parameter(defaultValue = "false", property = "yakou.compileOnly")
    private boolean compileOnly;

    /**
     * Skip compilation (e.g. {@code -Dyakou.skip=true}).
     */
    @Parameter(defaultValue = "false", property = "yakou.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Yakou compile skipped (yakou.skip=true)");
            return;
        }
        if (!sourceDirectory.isDirectory()) {
            getLog().debug("Yakou source directory missing; skipping: " + sourceDirectory);
            return;
        }
        List<Path> sources;
        try {
            sources = listYkSources(sourceDirectory.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("could not scan Yakou sources under " + sourceDirectory, e);
        }
        if (sources.isEmpty()) {
            getLog().debug("No .yk files under " + sourceDirectory + "; skipping Yakou compile");
            return;
        }

        List<String> args = new ArrayList<>();
        if (compileOnly) {
            args.add("-c");
        }
        args.add("-d");
        args.add(outputDirectory.getAbsolutePath());
        final List<String> classpathElements;
        try {
            classpathElements = project.getCompileClasspathElements();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("compile classpath not available for Yakou", e);
        }
        String cp = String.join(File.pathSeparator, classpathElements);
        args.add("-cp");
        args.add(cp);
        args.add("--source-root");
        args.add(sourceDirectory.getAbsolutePath());
        for (Path p : sources) {
            args.add(p.toAbsolutePath().normalize().toString());
        }

        getLog().info("Compiling " + sources.size() + " Yakou source(s) to " + outputDirectory);
        if (getLog().isDebugEnabled()) {
            getLog().debug("yakouc args: " + args);
        }

        int code = Yakouc.run(args.toArray(String[]::new));
        if (code != 0) {
            throw new MojoFailureException("yakouc failed with exit code " + code);
        }
    }

    private static List<Path> listYkSources(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".yk"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }
}
