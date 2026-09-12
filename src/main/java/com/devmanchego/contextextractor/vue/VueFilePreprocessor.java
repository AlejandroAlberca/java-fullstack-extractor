package com.devmanchego.contextextractor.vue;

import java.util.Optional;
import java.util.regex.*;

/**
 * Extracts the TypeScript/JavaScript source from the {@code <script>} or
 * {@code <script setup>} block of a {@code .vue} Single File Component.
 */
public final class VueFilePreprocessor {

    private static final Pattern SCRIPT_OPEN =
            Pattern.compile("<script(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_CLOSE =
            Pattern.compile("</script\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LANG_ATTR =
            Pattern.compile("\\blang\\s*=\\s*[\"']?(ts|typescript)[\"']?", Pattern.CASE_INSENSITIVE);

    private VueFilePreprocessor() {}

    /**
     * Parses the raw content of a {@code .vue} file and returns the extracted
     * script block source, or {@link Optional#empty()} if no script block is found.
     */
    public static Optional<ScriptBlock> extract(String vueSource) {
        Matcher open = SCRIPT_OPEN.matcher(vueSource);
        if (!open.find()) return Optional.empty();

        String openTag = open.group();
        boolean isTypeScript = LANG_ATTR.matcher(openTag).find();
        int contentStart = open.end();

        Matcher close = SCRIPT_CLOSE.matcher(vueSource);
        if (!close.find(contentStart)) return Optional.empty();

        String content = vueSource.substring(contentStart, close.start());
        return Optional.of(new ScriptBlock(content, isTypeScript));
    }

    public record ScriptBlock(String source, boolean isTypeScript) {}
}
