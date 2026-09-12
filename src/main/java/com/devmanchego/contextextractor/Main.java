package com.devmanchego.contextextractor;

import com.devmanchego.contextextractor.cli.CliArguments;
import com.devmanchego.contextextractor.cli.CliValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the java-angular-fullstack-extractor tool.
 *
 * Usage:
 *   java -jar java-angular-fullstack-extractor.jar
 *     &lt;java-project-path&gt; &lt;angular-project-path&gt;
 *     [output-file-name] [output-directory]
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) {
        CliArguments cliArgs;
        try {
            cliArgs = CliValidator.validate(args);
        } catch (CliValidator.CliException e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
            return;
        }
        try {
            Application.run(cliArgs);
        } catch (Exception e) {
            log.error("Fatal error during analysis: {}", e.getMessage(), e);
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(2);
        }
    }
}
