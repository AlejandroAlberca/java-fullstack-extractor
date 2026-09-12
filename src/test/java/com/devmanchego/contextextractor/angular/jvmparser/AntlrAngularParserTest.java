package com.devmanchego.contextextractor.angular.jvmparser;

import com.devmanchego.contextextractor.angular.model.*;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the ANTLR-based JVM TypeScript parser (Strategy B).
 * Uses hand-crafted TypeScript files written to a temp directory —
 * no real Angular project or Node.js installation required.
 */
class AntlrAngularParserTest {

    @TempDir
    Path tempDir;

    @Test
    void simpleInterface_extractedAsModel() throws IOException {
        writeFile("product.model.ts", """
                export interface Product {
                  id: number;
                  name: string;
                  description?: string;
                }
                """);

        AngularProject project = new AntlrAngularParser(tempDir).parse();

        assertEquals(AngularProject.ParsingStrategy.JVM_ANTLR, project.getStrategy());
        TsModelInfo model = project.getModels().stream()
                .filter(m -> "Product".equals(m.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("Model 'Product' not found"));

        assertEquals(TsModelInfo.Kind.INTERFACE, model.getKind());
        assertEquals(3, model.getFields().size());
        assertField(model.getFields(), "id", "number", false);
        assertField(model.getFields(), "description", "string", true);
    }

    @Test
    void httpGetCall_extractedFromService() throws IOException {
        writeFile("product.service.ts", """
                import { Injectable } from '@angular/core';
                import { HttpClient } from '@angular/common/http';
                @Injectable({ providedIn: 'root' })
                export class ProductService {
                  constructor(private http: HttpClient) {}
                  getProducts() {
                    return this.http.get<Product[]>('/api/products');
                  }
                  createProduct(body: CreateProductRequest) {
                    return this.http.post<Product>('/api/products', body);
                  }
                }
                """);

        AngularProject project = new AntlrAngularParser(tempDir).parse();

        ServiceInfo svc = project.getServices().stream()
                .filter(s -> "ProductService".equals(s.getClassName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Service 'ProductService' not found"));

        assertTrue(svc.getHttpCalls().stream()
                .anyMatch(c -> c.getHttpVerb() == HttpVerb.GET && "/api/products".equals(c.getUrlTemplate())),
                "GET /api/products call not found");
        assertTrue(svc.getHttpCalls().stream()
                .anyMatch(c -> c.getHttpVerb() == HttpVerb.POST && "/api/products".equals(c.getUrlTemplate())),
                "POST /api/products call not found");
    }

    @Test
    void templateLiteralUrl_normalizedToPlaceholders() throws IOException {
        writeFile("item.service.ts", """
                import { Injectable } from '@angular/core';
                import { HttpClient } from '@angular/common/http';
                @Injectable({ providedIn: 'root' })
                export class ItemService {
                  constructor(private http: HttpClient) {}
                  getItem(id: number) {
                    return this.http.get<Item>(`/api/items/${id}`);
                  }
                }
                """);

        AngularProject project = new AntlrAngularParser(tempDir).parse();

        HttpCallInfo call = project.getServices().stream()
                .flatMap(s -> s.getHttpCalls().stream())
                .filter(c -> c.getHttpVerb() == HttpVerb.GET)
                .findFirst()
                .orElseThrow();

        assertEquals("/api/items/{id}", call.getUrlTemplate(),
                "Template literal ${id} must be normalized to {id}");
    }

    @Test
    void specFiles_ignored() throws IOException {
        writeFile("product.spec.ts", """
                describe('ProductService', () => {
                  it('should work', () => {});
                });
                """);

        AngularProject project = new AntlrAngularParser(tempDir).parse();
        assertTrue(project.getServices().isEmpty() && project.getModels().isEmpty(),
                ".spec.ts files must be ignored");
    }

    @Test
    void componentInjectedServices_detected() throws IOException {
        writeFile("app.component.ts", """
                import { Component } from '@angular/core';
                import { ProductService } from './product.service';
                @Component({ selector: 'app-root', template: '' })
                export class AppComponent {
                  constructor(private productService: ProductService) {}
                }
                """);

        AngularProject project = new AntlrAngularParser(tempDir).parse();

        ComponentInfo comp = project.getComponents().stream()
                .filter(c -> "AppComponent".equals(c.getClassName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AppComponent not found"));

        assertTrue(comp.getInjectedServices().contains("ProductService"),
                "ProductService must be in injected services");
    }

    @Test
    void allHttpVerbs_extracted() throws IOException {
        writeFile("crud.service.ts", """
                import { Injectable } from '@angular/core';
                import { HttpClient } from '@angular/common/http';
                @Injectable({ providedIn: 'root' })
                export class CrudService {
                  constructor(private http: HttpClient) {}
                  getAll()        { return this.http.get<any[]>('/r'); }
                  create(b: any)  { return this.http.post<any>('/r', b); }
                  update(b: any)  { return this.http.put<any>('/r', b); }
                  remove()        { return this.http.delete<void>('/r'); }
                  patch(b: any)   { return this.http.patch<any>('/r', b); }
                }
                """);

        AngularProject project = new AntlrAngularParser(tempDir).parse();
        List<HttpCallInfo> calls = project.getServices().stream()
                .flatMap(s -> s.getHttpCalls().stream()).toList();

        assertTrue(calls.stream().anyMatch(c -> c.getHttpVerb() == HttpVerb.GET));
        assertTrue(calls.stream().anyMatch(c -> c.getHttpVerb() == HttpVerb.POST));
        assertTrue(calls.stream().anyMatch(c -> c.getHttpVerb() == HttpVerb.PUT));
        assertTrue(calls.stream().anyMatch(c -> c.getHttpVerb() == HttpVerb.DELETE));
        assertTrue(calls.stream().anyMatch(c -> c.getHttpVerb() == HttpVerb.PATCH));
    }

    @Test
    void httpClientFieldNamedHttpClient_detectedCorrectly() throws IOException {
        // Many Angular projects name the injected field 'httpClient' instead of 'http'.
        // All HTTP calls must still be detected regardless of the field name.
        writeFile("accommodation-manager.service.ts", """
                import { Injectable } from '@angular/core';
                import { HttpClient } from '@angular/common/http';
                @Injectable({ providedIn: 'root' })
                export class AccommodationManagerService {
                  constructor(private httpClient: HttpClient) {}
                  findAvailable() {
                    return this.httpClient.get<any[]>(`/api/accommodationRequests/current/available`);
                  }
                  assign(id: string) {
                    return this.httpClient.post<any>(`/api/accommodationRequests/current/assign/${id}`, null);
                  }
                }
                """);

        AngularProject project = new AntlrAngularParser(tempDir).parse();
        List<HttpCallInfo> calls = project.getServices().stream()
                .filter(s -> "AccommodationManagerService".equals(s.getClassName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AccommodationManagerService not found"))
                .getHttpCalls();

        assertTrue(calls.stream().anyMatch(c ->
                        c.getHttpVerb() == HttpVerb.GET
                                && "/api/accommodationRequests/current/available".equals(c.getUrlTemplate())),
                "GET with httpClient field not detected");
        assertTrue(calls.stream().anyMatch(c ->
                        c.getHttpVerb() == HttpVerb.POST
                                && "/api/accommodationRequests/current/assign/{id}".equals(c.getUrlTemplate())),
                "POST with httpClient field not detected");
    }

    @Test
    void innerCallbackMethod_doesNotPollutEnclosingMethodName() throws IOException {
        // upload() uses form.append() and forEach() before the http.post call —
        // the reported method name must be 'upload', not 'append' or 'forEach'.
        writeFile("upload.svc.ts", """
                import { Injectable } from '@angular/core';
                import { HttpClient } from '@angular/common/http';
                @Injectable({ providedIn: 'root' })
                export class UploadSvc {
                  constructor(private http: HttpClient) {}
                  upload(files: any[]) {
                    const form = new FormData();
                    files.forEach(f => form.append('files', f, f.name));
                    return this.http.post<any>('/api/upload', form);
                  }
                }
                """);

        AngularProject project = new AntlrAngularParser(tempDir).parse();
        HttpCallInfo call = project.getServices().stream()
                .flatMap(s -> s.getHttpCalls().stream())
                .filter(c -> c.getHttpVerb() == HttpVerb.POST)
                .findFirst().orElseThrow();

        assertEquals("upload", call.getMethodName(),
                "Enclosing method should be 'upload', not an inner callback like 'append'");
    }

    @Test
    void urlBuilderMethod_detectedAsImplicitGet() throws IOException {
        // exportPdfUrl() returns a URL string — should be captured as implicit GET
        writeFile("export.service.ts", """
                import { Injectable } from '@angular/core';
                import { HttpClient } from '@angular/common/http';
                @Injectable({ providedIn: 'root' })
                export class ExportService {
                  private readonly base = '/api/v1';
                  constructor(private http: HttpClient) {}
                  exportPdfUrl(sessionId: string): string {
                    return `${this.base}/export/pdf/${sessionId}`;
                  }
                  exportCsvUrl(sessionId: string): string {
                    return `${this.base}/export/csv/${sessionId}/data`;
                  }
                }
                """);

        AngularProject project = new AntlrAngularParser(tempDir).parse();
        List<HttpCallInfo> calls = project.getServices().stream()
                .filter(s -> "ExportService".equals(s.getClassName()))
                .findFirst().orElseThrow().getHttpCalls();

        assertTrue(calls.stream().anyMatch(c ->
                        c.getHttpVerb() == HttpVerb.GET
                                && "/api/v1/export/pdf/{sessionId}".equals(c.getUrlTemplate())),
                "exportPdfUrl must be detected as implicit GET /api/v1/export/pdf/{sessionId}");
        assertTrue(calls.stream().anyMatch(c ->
                        c.getHttpVerb() == HttpVerb.GET
                                && "/api/v1/export/csv/{sessionId}/data".equals(c.getUrlTemplate())),
                "exportCsvUrl must be detected as implicit GET /api/v1/export/csv/{sessionId}/data");
    }

    @Test
    void classFieldBaseUrl_resolvedInTemplateLiteral() throws IOException {
        writeFile("upload.service.ts", """
                import { Injectable } from '@angular/core';
                import { HttpClient } from '@angular/common/http';
                @Injectable({ providedIn: 'root' })
                export class UploadService {
                  private readonly base = '/api/v1';
                  constructor(private http: HttpClient) {}
                  upload(form: FormData) {
                    return this.http.post<UploadResponse>(`${this.base}/upload`, form);
                  }
                  analyze(sessionId: string) {
                    return this.http.post<AnalysisResultDto>(`${this.base}/analyze/${sessionId}`, null);
                  }
                }
                """);

        AngularProject project = new AntlrAngularParser(tempDir).parse();

        List<HttpCallInfo> calls = project.getServices().stream()
                .filter(s -> "UploadService".equals(s.getClassName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("UploadService not found"))
                .getHttpCalls();

        assertTrue(calls.stream().anyMatch(c ->
                        c.getHttpVerb() == HttpVerb.POST && "/api/v1/upload".equals(c.getUrlTemplate())),
                "Expected POST /api/v1/upload — base field not resolved");
        assertTrue(calls.stream().anyMatch(c ->
                        c.getHttpVerb() == HttpVerb.POST && "/api/v1/analyze/{sessionId}".equals(c.getUrlTemplate())),
                "Expected POST /api/v1/analyze/{sessionId}");
    }

    @Test
    void classFieldBaseUrl_resolvedInStringConcatenation() throws IOException {
        writeFile("items.service.ts", """
                import { Injectable } from '@angular/core';
                import { HttpClient } from '@angular/common/http';
                @Injectable({ providedIn: 'root' })
                export class ItemsService {
                  private readonly apiUrl = '/api/items';
                  constructor(private http: HttpClient) {}
                  getItem(id: number) {
                    return this.http.get<Item>(this.apiUrl + '/' + id);
                  }
                }
                """);

        AngularProject project = new AntlrAngularParser(tempDir).parse();

        HttpCallInfo call = project.getServices().stream()
                .flatMap(s -> s.getHttpCalls().stream())
                .filter(c -> c.getHttpVerb() == HttpVerb.GET)
                .findFirst()
                .orElseThrow(() -> new AssertionError("GET call not found"));

        assertEquals("/api/items/{id}", call.getUrlTemplate(),
                "String concatenation with class field not resolved");
    }

    @Test
    void protectedRoutesWithChildrenButNoComponent_extractedCorrectly() throws IOException {
        // Routes with empty path, canActivate guard, but no component should still traverse children
        writeFile("app.routes.ts", """
                export const routes: Routes = [
                  {
                    path: '',
                    canActivate: [AuthenticationGuard],
                    children: [
                      {
                        path: '',
                        redirectTo: 'site-welcome',
                        pathMatch: 'full',
                      },
                      {
                        path: 'site-welcome',
                        component: SiteWelcomeComponent,
                      },
                      {
                        path: 'lock-topography',
                        component: LockTopographyComponent,
                      },
                      {
                        path: 'accommodation-manager',
                        component: AccommodationManagerComponent,
                      }
                    ]
                  }
                ];
                """);

        // Use JvmRouteExtractor directly (routes are extracted separately from components)
        com.devmanchego.contextextractor.angular.jvmparser.JvmRouteExtractor extractor =
                new com.devmanchego.contextextractor.angular.jvmparser.JvmRouteExtractor(tempDir);
        List<RouteNode> routes = extractor.extract();

        // The root route has no component (it's just a routing container)
        assertEquals(1, routes.size(), "Expected 1 top-level route");
        RouteNode root = routes.get(0);
        assertEquals("", root.getPath(), "Root should have empty path");
        assertNull(root.getComponentName(), "Root should have no component (it's just a container)");

        // But its children should be properly extracted
        List<RouteNode> children = root.getChildren();
        assertTrue(children.size() >= 3, "Should have at least 3 child routes (redirect + 3 components)");

        // Verify specific child routes are present
        assertTrue(children.stream().anyMatch(c -> "site-welcome".equals(c.getPath()) && "SiteWelcomeComponent".equals(c.getComponentName())),
                "Missing SiteWelcomeComponent route");
        assertTrue(children.stream().anyMatch(c -> "lock-topography".equals(c.getPath()) && "LockTopographyComponent".equals(c.getComponentName())),
                "Missing LockTopographyComponent route");
        assertTrue(children.stream().anyMatch(c -> "accommodation-manager".equals(c.getPath()) && "AccommodationManagerComponent".equals(c.getComponentName())),
                "Missing AccommodationManagerComponent route");
    }

    @Test
    void templateStringsWithFunctionCalls_extractLiteralParts() throws IOException {
        // Routes using template strings like `${NavigationService.getApplicantURLPrefix()}/path/to/component`
        writeFile("applicant-routing.module.ts", """
                const accommodationRequestsRoutes: Routes = [
                  {
                    path: `${NavigationService.getApplicantURLPrefix()}/accommodation-requests/new`,
                    component: AccommodationRequestWorkflowComponent,
                    canActivate: [AuthGuard]
                  },
                  {
                    path: `${NavigationService.getApplicantURLPrefix()}/my-requests`,
                    component: MyRequestsComponent,
                    canActivate: [AuthGuard]
                  },
                  {
                    path: `${NavigationService.getApplicantURLPrefix()}/accommodations/:id`,
                    component: AccommodationDetailsComponent,
                    canActivate: [AuthGuard]
                  }
                ];
                """);

        com.devmanchego.contextextractor.angular.jvmparser.JvmRouteExtractor extractor =
                new com.devmanchego.contextextractor.angular.jvmparser.JvmRouteExtractor(tempDir);
        List<RouteNode> routes = extractor.extract();

        // Should extract 3 routes (the literal parts of the template strings)
        assertTrue(routes.size() >= 3, "Expected at least 3 routes");

        // Verify that the literal parts are extracted (without the dynamic prefix)
        assertTrue(routes.stream().anyMatch(r -> "/accommodation-requests/new".equals(r.getPath()) && "AccommodationRequestWorkflowComponent".equals(r.getComponentName())),
                "Missing /accommodation-requests/new route");
        assertTrue(routes.stream().anyMatch(r -> "/my-requests".equals(r.getPath()) && "MyRequestsComponent".equals(r.getComponentName())),
                "Missing /my-requests route");
        assertTrue(routes.stream().anyMatch(r -> "/accommodations/:id".equals(r.getPath()) && "AccommodationDetailsComponent".equals(r.getComponentName())),
                "Missing /accommodations/:id route");
    }

    @Test
    void forChildFeatureModules_appendedAsTopLevelRoutes() throws IOException {
        // Root module: RouterModule.forRoot with a single redirect (like a real app-routing.module.ts)
        writeFile("app-routing.module.ts", """
                import {RouterModule, Routes} from '@angular/router';
                const routes: Routes = [
                  { path: '', redirectTo: 'login', pathMatch: 'full' }
                ];
                @NgModule({
                  imports: [RouterModule.forRoot(routes, { useHash: true })],
                  exports: [RouterModule]
                })
                export class AppRoutingModule {}
                """);
        // Feature module A: RouterModule.forChild, eagerly imported (no loadChildren link)
        writeFile("clu-routing.module.ts", """
                import {RouterModule, Routes} from '@angular/router';
                const cluRoutes: Routes = [
                  { path: 'clu/accommodations', component: AccommodationsComponent },
                  { path: 'clu/cnpe', component: CnpeFormComponent }
                ];
                @NgModule({ imports: [RouterModule.forChild(cluRoutes)], exports: [RouterModule] })
                export class CluRoutingModule {}
                """);
        // Feature module B: also forChild
        writeFile("accommodation-manager-routing.module.ts", """
                import {RouterModule, Routes} from '@angular/router';
                const accommodationManagerRoutes: Routes = [
                  { path: 'am/accommodation-requests', component: AccommodationRequestsComponent }
                ];
                @NgModule({ imports: [RouterModule.forChild(accommodationManagerRoutes)], exports: [RouterModule] })
                export class AccommodationManagerRoutingModule {}
                """);

        com.devmanchego.contextextractor.angular.jvmparser.JvmRouteExtractor extractor =
                new com.devmanchego.contextextractor.angular.jvmparser.JvmRouteExtractor(tempDir);
        List<RouteNode> routes = extractor.extract();

        // The root redirect plus all forChild feature routes must be present
        assertTrue(routes.stream().anyMatch(r -> "login".equals(r.getRedirectTo())),
                "Root redirect route missing");
        assertTrue(routes.stream().anyMatch(r -> "AccommodationsComponent".equals(r.getComponentName())),
                "forChild CLU route (AccommodationsComponent) was dropped");
        assertTrue(routes.stream().anyMatch(r -> "CnpeFormComponent".equals(r.getComponentName())),
                "forChild CLU route (CnpeFormComponent) was dropped");
        assertTrue(routes.stream().anyMatch(r -> "AccommodationRequestsComponent".equals(r.getComponentName())),
                "forChild accommodation-manager route was dropped");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void writeFile(String name, String content) throws IOException {
        Files.writeString(tempDir.resolve(name), content);
    }

    private void assertField(List<TsFieldInfo> fields, String name, String type, boolean optional) {
        TsFieldInfo f = fields.stream().filter(x -> name.equals(x.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("Field '" + name + "' not found"));
        assertEquals(type, f.getType(), "Type mismatch for field " + name);
        assertEquals(optional, f.isOptional(), "Optional mismatch for field " + name);
    }
}
