package com.devmanchego.contextextractor.angular.template;

import com.devmanchego.contextextractor.common.TemplateEventExtractorStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TemplateEventExtractorTest {

    private final AngularTemplateEventExtractor extractor = new AngularTemplateEventExtractor();

    private Path writeComponent(Path dir, String name, String source) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void clickHandlerThatCallsHttpService_isBusinessTrigger(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              selector: 'app-account-list',
              template: `
                <button (click)="deleteAccount(account.id)">Delete</button>
              `
            })
            export class AccountListComponent {
              deleteAccount(id: number): void {
                this.accountService.deleteAccount(id).subscribe();
              }
            }
            """;
        Path file = writeComponent(dir, "account-list.component.ts", src);
        Map<String, String> descriptors = Map.of("deleteAccount", "DELETE /api/v1/accounts/{id}");

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), descriptors);

        assertEquals(1, events.size());
        assertEquals("Button 'Delete' → DELETE /api/v1/accounts/{id}", events.get(0).description());
    }

    @Test
    void ngSubmitHandler_isDescribedAsFormSubmit(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              selector: 'app-product-form',
              template: `
                <form [formGroup]="form" (ngSubmit)="onSubmit()">
                  <button type="submit">Save</button>
                </form>
              `
            })
            export class ProductFormComponent {
              onSubmit(): void {
                const request = this.form.value;
                this.productService.createProduct(request).subscribe();
              }
            }
            """;
        Path file = writeComponent(dir, "product-form.component.ts", src);
        Map<String, String> descriptors = Map.of("createProduct", "POST /api/v1/products");

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), descriptors);

        assertEquals(1, events.size());
        assertEquals("Form submit → POST /api/v1/products", events.get(0).description());
    }

    @Test
    void handlerThatDoesNotReachTheNetwork_isIgnored(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              selector: 'app-product-detail',
              template: `
                <button (click)="refreshStock()">Refresh Stock</button>
                <button (click)="cancel()">Cancel</button>
              `
            })
            export class ProductDetailComponent {
              refreshStock(): void {
                this.loadProduct();
              }
              cancel(): void {
                this.router.navigate(['/products']);
              }
            }
            """;
        Path file = writeComponent(dir, "product-detail.component.ts", src);
        // Only createProduct is an HTTP method; neither handler calls it.
        Map<String, String> descriptors = Map.of("createProduct", "POST /api/v1/products");

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), descriptors);

        assertTrue(events.isEmpty(), "handlers with no HTTP service call must be ignored");
    }

    @Test
    void emptyDescriptorMap_yieldsNoEvents(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `<button (click)="deleteAccount(1)">Delete</button>`
            })
            export class C {
              deleteAccount(id: number): void { this.accountService.deleteAccount(id).subscribe(); }
            }
            """;
        Path file = writeComponent(dir, "c.component.ts", src);

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), Map.of());

        assertTrue(events.isEmpty());
    }

    @Test
    void duplicateBindingsForSameTrigger_areDeduplicated(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <button (click)="cancelOrder(o.id)">Cancel</button>
                <button (click)="cancelOrder(o.id)">Cancel</button>
              `
            })
            export class OrderListComponent {
              cancelOrder(id: number): void { this.orderService.cancelOrder(id).subscribe(); }
            }
            """;
        Path file = writeComponent(dir, "order-list.component.ts", src);
        Map<String, String> descriptors = Map.of("cancelOrder", "DELETE /api/v1/orders/{id}");

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), descriptors);

        assertEquals(1, events.size(), "identical interactions should collapse to one");
    }
}
