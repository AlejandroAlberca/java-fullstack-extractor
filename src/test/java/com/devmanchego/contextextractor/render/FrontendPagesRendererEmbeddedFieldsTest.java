package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.i18n.AngularI18nCatalog;
import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.frontend.FrontendFramework;
import com.devmanchego.contextextractor.matching.EndpointMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage of embedded (non-routed) component fields: a reusable
 * {@code <app-address-form>} has no route of its own, so before this its form fields never
 * appeared anywhere in the generated documentation despite being plainly visible on the pages
 * that embed it.
 */
class FrontendPagesRendererEmbeddedFieldsTest {

    private static final EndpointMatcher.MatchResult EMPTY_MATCH =
            new EndpointMatcher.MatchResult(List.of(), List.of(), List.of());

    private ComponentInfo writeComponent(Path dir, String className, String selector,
                                         String template, String formGroupBody) throws IOException {
        String src = """
                @Component({
                  selector: '%s',
                  template: `%s`
                })
                export class %s {
                  form = this.fb.group({%s});
                }
                """.formatted(selector, template, className, formGroupBody);
        Path file = dir.resolve(className + ".ts");
        Files.writeString(file, src, StandardCharsets.UTF_8);
        return new ComponentInfo(className, file.toString(), selector, List.of());
    }

    private String render(List<RouteNode> routes, List<ComponentInfo> components) {
        Map<String, ComponentInfo> byName = new LinkedHashMap<>();
        components.forEach(c -> byName.put(c.getClassName(), c));
        return new FrontendPagesRenderer(AngularI18nCatalog.empty()).render(
                routes, byName, EMPTY_MATCH, List.of(), false, FrontendFramework.ANGULAR);
    }

    @Test
    void embeddedComponentFieldsAppearOnThePageThatEmbedsIt(@TempDir Path dir) throws IOException {
        ComponentInfo page = writeComponent(dir, "EmployeePageComponent", "app-employee-page",
                """
                <label for="name">Employee Name</label>
                <input id="name" formControlName="name" />
                <app-address-form></app-address-form>
                """,
                "name: ['', [Validators.required]]");
        ComponentInfo addressForm = writeComponent(dir, "AddressFormComponent", "app-address-form",
                """
                <label for="street">Street</label>
                <input id="street" formControlName="street" />
                """,
                "street: ['', [Validators.required, Validators.maxLength(120)]]");

        RouteNode route = new RouteNode("employees", "EmployeePageComponent", null, null, false, List.of());
        String md = render(List.of(route), List.of(page, addressForm));

        // The page's own field is unchanged...
        assertTrue(md.contains("- **Form Fields:**"));
        assertTrue(md.contains("| `name` | Employee Name | required |"));

        // ...and the embedded component contributes its own clearly-attributed table.
        assertTrue(md.contains("- **Embedded Fields — `<app-address-form>` (AddressFormComponent):**"),
                "the embedded component's provenance must be structural, not a column the reader may miss");
        assertTrue(md.contains("| `street` | Street | required, maxLength(120) |"));
    }

    @Test
    void sameFieldNameInPageAndEmbeddedComponentStaysSeparate(@TempDir Path dir) throws IOException {
        // The reason embedded fields get their own table: the Form Fields table is keyed by bare
        // field name, so merging would silently collapse these two distinct "email" fields.
        ComponentInfo page = writeComponent(dir, "SignupPageComponent", "app-signup-page",
                """
                <label for="email">Account Email</label>
                <input id="email" formControlName="email" />
                <app-contact-form></app-contact-form>
                """,
                "email: ['', [Validators.required]]");
        ComponentInfo contactForm = writeComponent(dir, "ContactFormComponent", "app-contact-form",
                """
                <label for="email">Contact Email</label>
                <input id="email" formControlName="email" />
                """,
                "email: ['', [Validators.email]]");

        RouteNode route = new RouteNode("signup", "SignupPageComponent", null, null, false, List.of());
        String md = render(List.of(route), List.of(page, contactForm));

        assertTrue(md.contains("| `email` | Account Email | required |"),
                "the page's own email field keeps its own label and validators");
        assertTrue(md.contains("| `email` | Contact Email | email |"),
                "the embedded component's same-named field is reported separately, not merged");
    }

    @Test
    void routedComponentEmbeddedElsewhereIsNotDuplicated(@TempDir Path dir) throws IOException {
        // OtherPage has its own route entry, so its fields must not also be pulled into this page.
        ComponentInfo page = writeComponent(dir, "HomePageComponent", "app-home-page",
                "<app-other-page></app-other-page>", "a: ['', [Validators.required]]");
        ComponentInfo otherPage = writeComponent(dir, "OtherPageComponent", "app-other-page",
                "<input formControlName=\"b\" />", "b: ['', [Validators.required]]");

        List<RouteNode> routes = List.of(
                new RouteNode("home", "HomePageComponent", null, null, false, List.of()),
                new RouteNode("other", "OtherPageComponent", null, null, false, List.of()));
        String md = render(routes, List.of(page, otherPage));

        assertFalse(md.contains("Embedded Fields"),
                "a routed component already has its own page entry and must not be inlined here");
    }

    @Test
    void embeddedComponentWithoutFieldsEmitsNoTable(@TempDir Path dir) throws IOException {
        ComponentInfo page = writeComponent(dir, "PageComponent", "app-page",
                "<app-banner></app-banner>", "a: ['', [Validators.required]]");
        Path bannerFile = dir.resolve("BannerComponent.ts");
        Files.writeString(bannerFile, """
                @Component({ selector: 'app-banner', template: `<h1>Welcome</h1>` })
                export class BannerComponent {}
                """, StandardCharsets.UTF_8);
        ComponentInfo banner = new ComponentInfo("BannerComponent", bannerFile.toString(), "app-banner", List.of());

        RouteNode route = new RouteNode("page", "PageComponent", null, null, false, List.of());
        String md = render(List.of(route), List.of(page, banner));

        assertFalse(md.contains("Embedded Fields"),
                "a field-less embedded component adds nothing and should stay out of the document");
    }
}
