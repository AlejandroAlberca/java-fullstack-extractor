package com.devmanchego.contextextractor.angular.template;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-first fixtures for {@link AngularEmbeddedComponentResolver}, which answers: "which
 * non-routed components does this page's template embed, transitively?"
 *
 * <p>The attribution rules encoded here are the load-bearing design decision of this phase —
 * getting them wrong silently mis-attributes fields to the wrong page, which is hard to notice
 * in generated documentation.
 */
class AngularEmbeddedComponentResolverTest {

    private ComponentInfo writeComponent(Path dir, String className, String selector, String template)
            throws IOException {
        String src = """
                @Component({
                  selector: '%s',
                  template: `%s`
                })
                export class %s {}
                """.formatted(selector, template, className);
        Path file = dir.resolve(className + ".ts");
        Files.writeString(file, src, StandardCharsets.UTF_8);
        return new ComponentInfo(className, file.toString(), selector, List.of());
    }

    private List<String> classNamesOf(List<ComponentInfo> components) {
        return components.stream().map(ComponentInfo::getClassName).toList();
    }

    @Test
    void resolvesDirectlyEmbeddedNonRoutedComponent(@TempDir Path dir) throws IOException {
        ComponentInfo page = writeComponent(dir, "EmployeePageComponent", "app-employee-page",
                "<h1>Employee</h1><app-address-form></app-address-form>");
        ComponentInfo addressForm = writeComponent(dir, "AddressFormComponent", "app-address-form",
                "<input formControlName=\"street\" />");

        var resolver = AngularEmbeddedComponentResolver.build(
                List.of(page, addressForm), Set.of("EmployeePageComponent"));

        assertEquals(List.of("AddressFormComponent"), classNamesOf(resolver.resolveEmbedded(page)));
    }

    @Test
    void resolvesTransitively(@TempDir Path dir) throws IOException {
        ComponentInfo page = writeComponent(dir, "PageComponent", "app-page",
                "<app-outer></app-outer>");
        ComponentInfo outer = writeComponent(dir, "OuterComponent", "app-outer",
                "<app-inner></app-inner>");
        ComponentInfo inner = writeComponent(dir, "InnerComponent", "app-inner",
                "<input formControlName=\"deep\" />");

        var resolver = AngularEmbeddedComponentResolver.build(
                List.of(page, outer, inner), Set.of("PageComponent"));

        assertEquals(List.of("OuterComponent", "InnerComponent"), classNamesOf(resolver.resolveEmbedded(page)),
                "breadth-first from the page, so the reading order matches nesting depth");
    }

    @Test
    void mutualCycleTerminatesWithoutDuplicates(@TempDir Path dir) throws IOException {
        ComponentInfo page = writeComponent(dir, "PageComponent", "app-page", "<app-a></app-a>");
        ComponentInfo a = writeComponent(dir, "AComponent", "app-a", "<app-b></app-b>");
        ComponentInfo b = writeComponent(dir, "BComponent", "app-b", "<app-a></app-a>");

        var resolver = AngularEmbeddedComponentResolver.build(
                List.of(page, a, b), Set.of("PageComponent"));

        assertEquals(List.of("AComponent", "BComponent"), classNamesOf(resolver.resolveEmbedded(page)));
    }

    @Test
    void selfRecursiveComponentTerminates(@TempDir Path dir) throws IOException {
        // A tree node rendering itself is a legitimate Angular pattern, not a bug to reject.
        ComponentInfo page = writeComponent(dir, "PageComponent", "app-page", "<app-tree-node></app-tree-node>");
        ComponentInfo treeNode = writeComponent(dir, "TreeNodeComponent", "app-tree-node",
                "<app-tree-node *ngFor=\"let c of children\"></app-tree-node>");

        var resolver = AngularEmbeddedComponentResolver.build(
                List.of(page, treeNode), Set.of("PageComponent"));

        assertEquals(List.of("TreeNodeComponent"), classNamesOf(resolver.resolveEmbedded(page)));
    }

    @Test
    void routedComponentsAreNotTraversed(@TempDir Path dir) throws IOException {
        // A component with its own page entry is excluded: pulling its whole content into this
        // page would duplicate another page and blur the routing structure that the
        // "Child Components (Direct)" bullet already documents.
        ComponentInfo page = writeComponent(dir, "PageComponent", "app-page",
                "<app-other-page></app-other-page><app-widget></app-widget>");
        ComponentInfo otherPage = writeComponent(dir, "OtherPageComponent", "app-other-page",
                "<input formControlName=\"x\" />");
        ComponentInfo widget = writeComponent(dir, "WidgetComponent", "app-widget",
                "<input formControlName=\"y\" />");

        var resolver = AngularEmbeddedComponentResolver.build(
                List.of(page, otherPage, widget), Set.of("PageComponent", "OtherPageComponent"));

        assertEquals(List.of("WidgetComponent"), classNamesOf(resolver.resolveEmbedded(page)));
    }

    @Test
    void selectorPrefixDoesNotMatchLongerSelector(@TempDir Path dir) throws IOException {
        // "app-address" must not match "<app-address-form>": a hyphen is not a regex word
        // character, so a naive \b boundary would wrongly match here.
        ComponentInfo page = writeComponent(dir, "PageComponent", "app-page",
                "<app-address-form></app-address-form>");
        ComponentInfo address = writeComponent(dir, "AddressComponent", "app-address",
                "<input formControlName=\"a\" />");
        ComponentInfo addressForm = writeComponent(dir, "AddressFormComponent", "app-address-form",
                "<input formControlName=\"b\" />");

        var resolver = AngularEmbeddedComponentResolver.build(
                List.of(page, address, addressForm), Set.of("PageComponent"));

        assertEquals(List.of("AddressFormComponent"), classNamesOf(resolver.resolveEmbedded(page)));
    }

    @Test
    void selfClosingTagIsRecognised(@TempDir Path dir) throws IOException {
        ComponentInfo page = writeComponent(dir, "PageComponent", "app-page", "<app-widget />");
        ComponentInfo widget = writeComponent(dir, "WidgetComponent", "app-widget",
                "<input formControlName=\"y\" />");

        var resolver = AngularEmbeddedComponentResolver.build(
                List.of(page, widget), Set.of("PageComponent"));

        assertEquals(List.of("WidgetComponent"), classNamesOf(resolver.resolveEmbedded(page)));
    }

    @Test
    void tagWithAttributesIsRecognised(@TempDir Path dir) throws IOException {
        ComponentInfo page = writeComponent(dir, "PageComponent", "app-page",
                "<app-widget [value]=\"x\" class=\"big\"></app-widget>");
        ComponentInfo widget = writeComponent(dir, "WidgetComponent", "app-widget",
                "<input formControlName=\"y\" />");

        var resolver = AngularEmbeddedComponentResolver.build(
                List.of(page, widget), Set.of("PageComponent"));

        assertEquals(List.of("WidgetComponent"), classNamesOf(resolver.resolveEmbedded(page)));
    }

    @Test
    void componentsWithoutElementSelectorAreIgnored(@TempDir Path dir) throws IOException {
        // Attribute/class selectors ([appHighlight], .foo) are directives, not embeddable
        // elements — matching them as tags would be meaningless.
        ComponentInfo page = writeComponent(dir, "PageComponent", "app-page",
                "<div appHighlight></div>");
        ComponentInfo directive = writeComponent(dir, "HighlightDirective", "[appHighlight]", "");
        ComponentInfo noSelector = writeComponent(dir, "NoSelectorComponent", "", "");

        var resolver = AngularEmbeddedComponentResolver.build(
                List.of(page, directive, noSelector), Set.of("PageComponent"));

        assertTrue(resolver.resolveEmbedded(page).isEmpty());
    }

    @Test
    void pageWithNoTemplateYieldsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("NoTemplate.ts");
        Files.writeString(file, "export class NoTemplateComponent {}", StandardCharsets.UTF_8);
        ComponentInfo page = new ComponentInfo("NoTemplateComponent", file.toString(), "app-no-template", List.of());

        var resolver = AngularEmbeddedComponentResolver.build(List.of(page), Set.of("NoTemplateComponent"));

        assertTrue(resolver.resolveEmbedded(page).isEmpty());
    }

    @Test
    void emptyResolverAlwaysYieldsEmpty(@TempDir Path dir) throws IOException {
        ComponentInfo page = writeComponent(dir, "PageComponent", "app-page", "<app-widget></app-widget>");

        assertTrue(AngularEmbeddedComponentResolver.empty().resolveEmbedded(page).isEmpty());
    }
}
