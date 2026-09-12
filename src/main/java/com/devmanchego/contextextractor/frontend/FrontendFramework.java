package com.devmanchego.contextextractor.frontend;

public enum FrontendFramework {
    ANGULAR("Angular"),
    REACT("React"),
    VUE3("Vue 3"),
    VUE2("Vue 2"),
    NEXTJS("Next.js"),
    NUXT("Nuxt"),
    /** Server-rendered JSP views driven by a webpack-bundled jQuery layer — no SPA framework. */
    JSP_JQUERY("JSP + jQuery"),
    UNKNOWN("Frontend");

    private final String displayName;

    FrontendFramework(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }
}
