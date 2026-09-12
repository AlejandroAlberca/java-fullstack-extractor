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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage of the "Form Fields" table for React and Vue: proves
 * {@link ReactFormFieldExtractor} / {@link VueFormFieldExtractor} field names line up with
 * {@code ReactFormValidationExtractor} / {@code VueFormValidationExtractor}'s field names on the
 * same table row — the join that would silently break if either pair used a different
 * field-naming convention.
 */
class FrontendPagesRendererReactVueFormFieldsTest {

    private static final EndpointMatcher.MatchResult EMPTY_MATCH =
            new EndpointMatcher.MatchResult(List.of(), List.of(), List.of());

    private ComponentInfo writeComponent(Path dir, String fileName, String className, String source)
            throws IOException {
        Path file = dir.resolve(fileName);
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return new ComponentInfo(className, file.toString(), "app-form", List.of());
    }

    private RouteNode pageRoute(String path, String componentName) {
        return new RouteNode(path, componentName, null, null, false, List.of());
    }

    private String render(ComponentInfo comp, RouteNode route, FrontendFramework framework) {
        return new FrontendPagesRenderer(AngularI18nCatalog.empty()).render(
                List.of(route), Map.of(comp.getClassName(), comp), EMPTY_MATCH,
                List.of(), false, framework);
    }

    @Test
    void react_labelAndValidatorsJoinOnSameFieldName(@TempDir Path dir) throws IOException {
        String src = """
            import { z } from 'zod';

            const schema = z.object({
              email: z.string().min(3).email()
            });

            export function SignupForm() {
              return (
                <form>
                  <label htmlFor="email">Email Address</label>
                  <input id="email" {...register("email")} />
                </form>
              );
            }
            """;
        ComponentInfo comp = writeComponent(dir, "SignupForm.tsx", "SignupForm", src);

        String md = render(comp, pageRoute("signup", "SignupForm"), FrontendFramework.REACT);

        assertTrue(md.contains("| Field | Label | Validations |"));
        assertTrue(md.contains("| `email` | Email Address | min(3), email |"),
                "the register(\"email\") field name must join with the schema's \"email\" key");
    }

    @Test
    void vue_labelAndValidatorsJoinOnSameFieldName(@TempDir Path dir) throws IOException {
        String src = """
            <template>
              <label for="email">Email Address</label>
              <input id="email" v-model="form.email" rules="required|email" />
            </template>
            <script>
            export default {
              data() { return { form: { email: '' } }; }
            };
            </script>
            """;
        ComponentInfo comp = writeComponent(dir, "SignupForm.vue", "SignupFormVue", src);

        String md = render(comp, pageRoute("signup", "SignupFormVue"), FrontendFramework.VUE3);

        assertTrue(md.contains("| Field | Label | Validations |"));
        assertTrue(md.contains("| `email` | Email Address | required, email |"),
                "the v-model=\"form.email\" field name must join with the Vee-Validate rules' field name");
    }

    @Test
    void nextjs_reusesReactLabelExtractor(@TempDir Path dir) throws IOException {
        String src = """
            import { z } from 'zod';
            const schema = z.object({ email: z.string().email() });
            export function Page() {
              return (
                <div>
                  <label htmlFor="email">Email</label>
                  <input id="email" {...register("email")} />
                </div>
              );
            }
            """;
        ComponentInfo comp = writeComponent(dir, "Page.tsx", "PageComponent", src);

        String md = render(comp, pageRoute("page", "PageComponent"), FrontendFramework.NEXTJS);

        assertTrue(md.contains("| `email` | Email | email |"));
    }
}
