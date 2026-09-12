package com.devmanchego.contextextractor.angular.model;

import java.util.List;

/** An Angular @Component with the services it injects. */
public final class ComponentInfo {

    private final String className;
    private final String filePath;
    private final String selector;
    private final List<String> injectedServices;  // class names of injected services
    private final List<String> uiTabs;             // labels of UI tabs not backed by child routes

    public ComponentInfo(String className, String filePath,
                         String selector, List<String> injectedServices) {
        this(className, filePath, selector, injectedServices, List.of());
    }

    public ComponentInfo(String className, String filePath,
                         String selector, List<String> injectedServices,
                         List<String> uiTabs) {
        this.className = className;
        this.filePath = filePath;
        this.selector = selector;
        this.injectedServices = List.copyOf(injectedServices);
        this.uiTabs = List.copyOf(uiTabs);
    }

    public String getClassName() { return className; }
    public String getFilePath() { return filePath; }
    public String getSelector() { return selector; }
    public List<String> getInjectedServices() { return injectedServices; }
    public List<String> getUiTabs() { return uiTabs; }
}
