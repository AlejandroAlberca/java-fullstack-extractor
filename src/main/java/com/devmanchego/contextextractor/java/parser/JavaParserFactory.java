package com.devmanchego.contextextractor.java.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Constructs a {@link JavaParser} configured with a {@link JavaSymbolSolver}
 * that resolves types against the target project's classpath.
 * Falls back to reflection-only resolution when the classpath is empty (degraded mode).
 */
public final class JavaParserFactory {

    private static final Logger log = LoggerFactory.getLogger(JavaParserFactory.class);

    private JavaParserFactory() {}

    public static JavaParser create(Path projectRoot, List<Path> classpath) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();

        // Resolve JDK types (always available)
        typeSolver.add(new ReflectionTypeSolver(false));

        // Resolve source types from the project itself
        Path srcMain = projectRoot.resolve("src").resolve("main").resolve("java");
        if (Files.isDirectory(srcMain)) {
            typeSolver.add(new JavaParserTypeSolver(srcMain));
            log.debug("Added source type solver for: {}", srcMain);
        }

        // Resolve dependency types from JARs on the classpath
        boolean hasJars = false;
        for (Path jar : classpath) {
            if (Files.isRegularFile(jar) && jar.toString().endsWith(".jar")) {
                try {
                    typeSolver.add(new JarTypeSolver(jar));
                    hasJars = true;
                } catch (IOException e) {
                    log.warn("Could not add JAR to type solver '{}': {}", jar, e.getMessage());
                }
            }
        }

        if (!hasJars && classpath.isEmpty()) {
            log.warn("No classpath JARs available. Type resolution is degraded — generic type parameters "
                    + "may not resolve correctly. Results will be marked in the Warnings section.");
        } else {
            log.debug("SymbolSolver configured with {} classpath entries.", classpath.size());
        }

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.CURRENT);

        return new JavaParser(config);
    }
}
