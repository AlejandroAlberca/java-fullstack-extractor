#!/usr/bin/env node
/**
 * ts-morph-extractor.js
 *
 * Multi-framework Strategy A extractor: uses ts-morph to analyze an Angular,
 * React, Vue 3, Vue 2, Next.js, or Nuxt frontend project and writes a
 * structured JSON payload to stdout.
 *
 * Usage:
 *   node ts-morph-extractor.js <frontend-project-path>
 *
 * The JSON schema matches TsMorphPayload.Root on the Java side.
 *
 * Requires: npm install -g ts-morph
 * (or ts-morph available in the project's node_modules)
 */

'use strict';

const path = require('path');
const fs   = require('fs');

// Locate ts-morph: prefer local node_modules, fall back to global
function requireTsMorph(projectRoot) {
  const candidates = [
    path.join(projectRoot, 'node_modules', 'ts-morph'),
    path.join(__dirname, '..', 'node_modules', 'ts-morph'),
    'ts-morph',
  ];
  for (const c of candidates) {
    try { return require(c); } catch (_) {}
  }
  throw new Error(
    'ts-morph not found. Install it with: npm install -g ts-morph\n' +
    'or locally in the project: npm install --save-dev ts-morph'
  );
}

const projectRoot = process.argv[2];
if (!projectRoot || !fs.existsSync(projectRoot)) {
  process.stderr.write('ERROR: frontend-project-path is required and must exist.\n');
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Framework detection from package.json
// ---------------------------------------------------------------------------

function detectFramework(root) {
  const pkgPath = path.join(root, 'package.json');
  if (!fs.existsSync(pkgPath)) return 'UNKNOWN';
  let pkg;
  try { pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8')); } catch (_) { return 'UNKNOWN'; }
  const all = Object.assign({}, pkg.dependencies || {}, pkg.devDependencies || {});
  if (all['@angular/core']) return 'ANGULAR';
  if (all['next'])          return 'NEXTJS';
  if (all['nuxt'])          return 'NUXT';
  if (all['react'])         return 'REACT';
  if (all['vue'])           return 'VUE';
  return 'UNKNOWN';
}

const framework = detectFramework(projectRoot);
process.stderr.write('Detected framework: ' + framework + '\n');

const { Project, SyntaxKind } = requireTsMorph(projectRoot);

const project = new Project({
  tsConfigFilePath: findTsConfig(projectRoot),
  addFilesFromTsConfig: !!findTsConfig(projectRoot),
  skipAddingFilesFromTsConfig: !findTsConfig(projectRoot),
  compilerOptions: { strict: false, skipLibCheck: true },
});

if (!findTsConfig(projectRoot)) {
  // Add .ts and .tsx source files; for Vue also add .vue (ts-morph partial support)
  const patterns = [
    path.join(projectRoot, 'src', '**', '*.ts'),
    path.join(projectRoot, 'src', '**', '*.tsx'),
  ];
  if (framework === 'VUE' || framework === 'NUXT') {
    patterns.push(path.join(projectRoot, 'src', '**', '*.vue'));
    patterns.push(path.join(projectRoot, '**', '*.vue'));
  }
  project.addSourceFilesAtPaths(patterns);
}

const components = [];
const services   = [];
const models     = [];

for (const sourceFile of project.getSourceFiles()) {
  const fp = sourceFile.getFilePath();
  if (fp.includes('node_modules') || fp.endsWith('.spec.ts') || fp.endsWith('.d.ts')) continue;

  if (framework === 'ANGULAR') {
    // Angular: use decorator-based extraction
    for (const cls of sourceFile.getClasses()) {
      const componentDec  = cls.getDecorator('Component');
      const injectableDec = cls.getDecorator('Injectable');
      if (componentDec) {
        components.push(extractAngularComponent(cls, fp));
      } else if (injectableDec) {
        const svc = extractAngularService(cls, fp);
        if (svc.httpCalls.length > 0) services.push(svc);
      } else {
        const model = extractClassModel(cls, fp);
        if (model.fields.length > 0) models.push(model);
      }
    }
    for (const iface of sourceFile.getInterfaces()) {
      const model = extractInterfaceModel(iface, fp);
      if (model.fields.length > 0) models.push(model);
    }
  } else {
    // React / Next.js / Vue / Nuxt: convention-based extraction
    if (isServiceFile(fp)) {
      const svc = extractConventionService(sourceFile, fp);
      if (svc && svc.httpCalls.length > 0) services.push(svc);
    }
    for (const iface of sourceFile.getInterfaces()) {
      const model = extractInterfaceModel(iface, fp);
      if (model.fields.length > 0) models.push(model);
    }
    for (const cls of sourceFile.getClasses()) {
      const model = extractClassModel(cls, fp);
      if (model.fields.length > 0) models.push(model);
    }
  }
}

const routes = framework === 'ANGULAR' ? extractRouteTree(project) : [];

const output = {
  strategy: 'ts-morph',
  version:  '1',
  components,
  services,
  models,
  routes,
};

process.stdout.write(JSON.stringify(output, null, 2));

// ---------------------------------------------------------------------------
// Service file detection (React / Vue convention)
// ---------------------------------------------------------------------------

const SERVICE_FILE_RE = /(?:\.service\.[tj]sx?|Api\.[tj]sx?|Client\.[tj]sx?|Repository\.[tj]sx?|^use[A-Z].+\.[tj]sx?)$/i;
const SERVICE_DIRS = new Set(['services', 'api', 'hooks', 'composables', 'lib']);

function isServiceFile(fp) {
  const base = path.basename(fp);
  if (SERVICE_FILE_RE.test(base)) return true;
  const parts = fp.replace(/\\/g, '/').split('/');
  return parts.some(p => SERVICE_DIRS.has(p.toLowerCase()));
}

// ---------------------------------------------------------------------------
// Convention-based extraction (React / Vue / Next / Nuxt)
// ---------------------------------------------------------------------------

const REACT_HTTP_RE = /(?:axios|\$axios|http|\$http)\.(get|post|put|delete|patch)\s*(?:<[^>]*)?\s*\(['"`]([^'"`\s]+)['"`]/i;
const FETCH_RE      = /fetch\s*\(\s*['"`]([^'"`\s]+)['"`]/i;

function extractConventionService(sourceFile, filePath) {
  const calls = [];
  const className = path.basename(filePath).replace(/\.[tj]sx?$/, '');
  const allText = sourceFile.getFullText();

  for (const line of allText.split('\n')) {
    const m = REACT_HTTP_RE.exec(line);
    if (m) {
      calls.push({ methodName: 'unknown', httpVerb: m[1].toUpperCase(), urlTemplate: m[2], responseType: 'any', bodyType: null });
      continue;
    }
    const f = FETCH_RE.exec(line);
    if (f) {
      const verb = /['"]POST['"]/i.test(line) ? 'POST'
                 : /['"]PUT['"]/i.test(line)  ? 'PUT'
                 : /['"]DELETE['"]/i.test(line) ? 'DELETE'
                 : /['"]PATCH['"]/i.test(line)  ? 'PATCH' : 'GET';
      calls.push({ methodName: 'unknown', httpVerb: verb, urlTemplate: f[1], responseType: 'any', bodyType: null });
    }
  }
  return { className, filePath, httpCalls: calls };
}

// ---------------------------------------------------------------------------
// Angular-specific extraction helpers
// ---------------------------------------------------------------------------

function extractAngularComponent(cls, filePath) {
  const dec = cls.getDecorator('Component');
  const selector = getDecoratorStringProp(dec, 'selector') || '';

  const injectedServices = [];
  const ctor = cls.getConstructors()[0];
  if (ctor) {
    for (const param of ctor.getParameters()) {
      const typeNode = param.getTypeNode();
      if (typeNode) injectedServices.push(typeNode.getText());
    }
  }

  return {
    className: cls.getName() || '(anonymous)',
    filePath,
    selector,
    injectedServices,
  };
}

function extractAngularService(cls, filePath) {
  const httpCalls = [];

  // Collect class-level string field constants for URL resolution.
  // Handles: private readonly base = '/api/v1';
  const fieldValues = {};
  for (const prop of cls.getProperties()) {
    const init = prop.getInitializer();
    if (!init) continue;
    const kind = init.getKind();
    if (kind === SyntaxKind.StringLiteral || kind === SyntaxKind.NoSubstitutionTemplateLiteral) {
      fieldValues[prop.getName()] = init.getLiteralValue();
    }
  }

  // Detect injected HttpClient field names from the constructor.
  // Handles any naming convention: http, httpClient, client, etc.
  const httpClientFieldNames = new Set();
  for (const ctor of cls.getConstructors()) {
    for (const param of ctor.getParameters()) {
      const typeText = param.getType().getText();
      if (typeText === 'HttpClient' || typeText.endsWith('HttpClient')) {
        httpClientFieldNames.add(param.getName());
      }
    }
  }
  if (httpClientFieldNames.size === 0) httpClientFieldNames.add('http'); // safe fallback

  for (const method of cls.getMethods()) {
    const body = method.getBody();
    if (!body) continue;

    const calls = body.getDescendantsOfKind(SyntaxKind.CallExpression);
    for (const call of calls) {
      const expr = call.getExpression();
      if (expr.getKind() !== SyntaxKind.PropertyAccessExpression) continue;

      const propAccess = expr.asKindOrThrow(SyntaxKind.PropertyAccessExpression);
      const httpMethod = propAccess.getName().toLowerCase();
      if (!['get', 'post', 'put', 'delete', 'patch'].includes(httpMethod)) continue;

      const owner = propAccess.getExpression();
      if (owner.getKind() !== SyntaxKind.PropertyAccessExpression) continue;
      const ownerProp = owner.asKindOrThrow(SyntaxKind.PropertyAccessExpression);
      if (!httpClientFieldNames.has(ownerProp.getName())) continue;

      const args = call.getArguments();
      const urlTemplate = args[0] ? normalizeUrl(args[0].getText(), fieldValues) : '/unknown';

      // Unwrap Observable<T>, Promise<T>, T[]
      const returnTypeText = method.getReturnType().getText();
      const responseType = unwrapType(returnTypeText);

      let bodyType = null;
      if (['post', 'put', 'patch'].includes(httpMethod) && args[1]) {
        bodyType = args[1].getType().getText();
        if (bodyType === 'any' || bodyType === 'unknown') bodyType = args[1].getText();
      }

      // Unwrap type args from the call expression: this.http.get<MyType>(...)
      const typeArgs = call.getTypeArguments();
      const explicitResponseType = typeArgs.length > 0 ? unwrapType(typeArgs[0].getText()) : responseType;

      httpCalls.push({
        methodName:   method.getName(),
        httpVerb:     httpMethod.toUpperCase(),
        urlTemplate,
        responseType: explicitResponseType,
        bodyType,
      });
    }
  }

  // Detect URL-builder methods: string-returning methods whose body is a single
  // return of a URL-like template literal. Treated as implicit GET calls
  // (used for href links, window.open(), download anchors, etc.).
  for (const method of cls.getMethods()) {
    const returnType = method.getReturnType().getText();
    if (returnType !== 'string') continue;

    const body = method.getBody();
    if (!body) continue;

    const returnStmts = body.getDescendantsOfKind(SyntaxKind.ReturnStatement);
    for (const ret of returnStmts) {
      const expr = ret.getExpression();
      if (!expr) continue;
      const url = normalizeUrl(expr.getText(), fieldValues);
      // Only capture URL-like strings (start with '/') that aren't already captured
      if (url.startsWith('/') && !httpCalls.some(c => c.urlTemplate === url)) {
        httpCalls.push({
          methodName:   method.getName(),
          httpVerb:     'GET',
          urlTemplate:  url,
          responseType: 'string',
          bodyType:     null,
        });
      }
    }
  }

  return {
    className: cls.getName() || '(anonymous)',
    filePath,
    httpCalls,
  };
}

function extractClassModel(cls, filePath) {
  return {
    name:    cls.getName() || '(anonymous)',
    filePath,
    kind:    'class',
    fields:  extractFields(cls.getProperties()),
  };
}

function extractInterfaceModel(iface, filePath) {
  return {
    name:    iface.getName(),
    filePath,
    kind:    'interface',
    fields:  extractFields(iface.getProperties()),
  };
}

function extractFields(props) {
  return props.map(p => ({
    name:     p.getName(),
    type:     p.getType().getText(),
    optional: p.hasQuestionToken(),
  }));
}

// ---------------------------------------------------------------------------
// Type unwrapping
// ---------------------------------------------------------------------------

function unwrapType(type) {
  if (!type) return 'any';
  // Observable<T> → T
  const obsMatch = type.match(/^Observable<(.+)>$/);
  if (obsMatch) return unwrapType(obsMatch[1]);
  // Promise<T> → T
  const promMatch = type.match(/^Promise<(.+)>$/);
  if (promMatch) return unwrapType(promMatch[1]);
  return type;
}

// ---------------------------------------------------------------------------
// URL normalization: template literals → {param}, with class-field folding
// ---------------------------------------------------------------------------

/**
 * Normalises a URL expression from an Angular HTTP call to a path template.
 *
 * @param {string} urlText   - Raw text of the first argument to this.http.*()
 * @param {object} fieldValues - Map of {fieldName: stringValue} for this class
 *
 * Handles:
 *   - String literals:       '/api/v1/items'
 *   - Template literals:     `${this.base}/items/${id}`  → resolved via fieldValues
 *   - Concatenation:         this.base + '/items/' + id  → resolved via fieldValues
 */
function normalizeUrl(urlText, fieldValues) {
  fieldValues = fieldValues || {};

  // Template literal: starts and ends with backtick
  if (urlText.startsWith('`') && urlText.endsWith('`')) {
    const content = urlText.slice(1, -1);
    // Replace ${this.fieldName} with resolved value or {fieldName} placeholder
    const folded = content.replace(/\$\{this\.([^}]+)}/g, (_, name) =>
      Object.prototype.hasOwnProperty.call(fieldValues, name)
        ? fieldValues[name]
        : `{${name}}`
    );
    // Replace remaining ${expr} → {expr}
    return folded.replace(/\$\{([^}]+)}/g, '{$1}');
  }

  // Plain string literal
  if ((urlText.startsWith("'") && urlText.endsWith("'")) ||
      (urlText.startsWith('"') && urlText.endsWith('"'))) {
    return urlText.slice(1, -1);
  }

  // String concatenation: this.base + '/path' + ...
  // Split on '+', resolve each part, concatenate
  const parts = urlText.split('+');
  const resolved = parts.map(part => {
    const p = part.trim();
    // this.fieldName
    const thisMatch = p.match(/^this\.(\w+)$/);
    if (thisMatch) {
      const name = thisMatch[1];
      return Object.prototype.hasOwnProperty.call(fieldValues, name)
        ? fieldValues[name]
        : `{${name}}`;
    }
    // String literal
    if ((p.startsWith("'") && p.endsWith("'")) || (p.startsWith('"') && p.endsWith('"'))) {
      return p.slice(1, -1);
    }
    // Template literal
    if (p.startsWith('`') && p.endsWith('`')) {
      return normalizeUrl(p, fieldValues);
    }
    // Variable or complex expression → treat as placeholder
    if (/^\w+$/.test(p)) return `{${p}}`;
    return p;
  });

  const joined = resolved.join('');
  // If the result contains any real path chars, return it; else fall back
  return joined || `/${urlText} (dynamic)`;
}

// ---------------------------------------------------------------------------
// Decorator helpers
// ---------------------------------------------------------------------------

function getDecoratorStringProp(decorator, propName) {
  if (!decorator) return null;
  const args = decorator.getArguments();
  if (!args[0]) return null;
  if (args[0].getKind() !== SyntaxKind.ObjectLiteralExpression) return null;
  const obj = args[0].asKindOrThrow(SyntaxKind.ObjectLiteralExpression);
  const prop = obj.getProperty(propName);
  if (!prop) return null;
  const init = prop.getInitializer ? prop.getInitializer() : null;
  if (!init) return null;
  return init.getText().replace(/^['"`]|['"`]$/g, '');
}

// ---------------------------------------------------------------------------
// Angular route tree extraction
// ---------------------------------------------------------------------------

/**
 * Builds the application route tree (functional sections) for Angular projects.
 *
 * Strategy:
 *  1. Collect every Routes array in the project, remembering which file declares it
 *     and whether it is registered with forRoot (app root) or forChild/provideRouter.
 *  2. Resolve loadChildren/loadComponent lazy imports by linking the route to the
 *     Routes array declared in the imported file.
 *  3. Return the tree rooted at the forRoot array (fallback: all arrays concatenated).
 */
function extractRouteTree(project) {
  // file path → array of parsed route objects declared in that file
  const routesByFile = new Map();
  let rootFile = null;

  for (const sourceFile of project.getSourceFiles()) {
    const fp = sourceFile.getFilePath();
    if (fp.includes('node_modules') || fp.endsWith('.spec.ts') || fp.endsWith('.d.ts')) continue;

    const fileRoutes = [];

    // Pattern 1: typed variable — const routes: Routes = [...]
    for (const varDecl of sourceFile.getVariableDeclarations()) {
      const typeNode = varDecl.getTypeNode();
      const typeText = typeNode ? typeNode.getText() : '';
      if (typeText !== 'Routes' && typeText !== 'Route[]') continue;
      const init = varDecl.getInitializer();
      if (init && init.getKind() === SyntaxKind.ArrayLiteralExpression) {
        fileRoutes.push(...parseRouteArray(init, sourceFile));
      }
    }

    // Pattern 2: inline array — RouterModule.forRoot([...]) / forChild([...]) / provideRouter([...])
    for (const call of sourceFile.getDescendantsOfKind(SyntaxKind.CallExpression)) {
      const exprText = call.getExpression().getText();
      const isForRoot  = exprText === 'RouterModule.forRoot';
      const isForChild = exprText === 'RouterModule.forChild';
      const isProvide  = exprText === 'provideRouter';
      if (!isForRoot && !isForChild && !isProvide) continue;

      if (isForRoot || isProvide) rootFile = fp;

      const arg = call.getArguments()[0];
      if (!arg) continue;
      if (arg.getKind() === SyntaxKind.ArrayLiteralExpression) {
        fileRoutes.push(...parseRouteArray(arg, sourceFile));
      }
      // Identifier argument: routes already captured by Pattern 1 in this file.
    }

    if (fileRoutes.length > 0) {
      routesByFile.set(fp, dedupeRoutes(fileRoutes));
    }
  }

  // Link lazy imports: route.lazyImport (module path) → routes declared in that file
  for (const [fp, fileRoutes] of routesByFile) {
    linkLazyChildren(fileRoutes, fp, routesByFile);
  }

  if (rootFile && routesByFile.has(rootFile)) {
    return routesByFile.get(rootFile).map(stripInternal);
  }
  // Fallback: no forRoot found — return all top-level arrays concatenated
  const all = [];
  for (const fileRoutes of routesByFile.values()) all.push(...fileRoutes);
  return all.map(stripInternal);
}

/** Parses an ArrayLiteralExpression of route object literals. */
function parseRouteArray(arrayExpr, sourceFile) {
  const result = [];
  for (const el of arrayExpr.getElements()) {
    if (el.getKind() !== SyntaxKind.ObjectLiteralExpression) continue;
    result.push(parseRouteObject(el, sourceFile));
  }
  return result;
}

function parseRouteObject(obj, sourceFile) {
  const route = {
    path: '', componentName: null, title: null, redirectTo: null,
    lazy: false, lazyImport: null, children: [],
  };

  for (const prop of obj.getProperties()) {
    if (prop.getKind() !== SyntaxKind.PropertyAssignment) continue;
    const name = prop.getName();
    const init = prop.getInitializer();
    if (!init) continue;

    switch (name) {
      case 'path':
        route.path = stripQuotes(init.getText());
        break;
      case 'component':
      case 'loadComponent': {
        if (name === 'loadComponent') route.lazy = true;
        // component: MyComponent | loadComponent: () => import(...).then(m => m.MyComponent)
        const compName = extractComponentName(init);
        if (compName) route.componentName = compName;
        break;
      }
      case 'redirectTo':
        route.redirectTo = stripQuotes(init.getText());
        break;
      case 'children':
        if (init.getKind() === SyntaxKind.ArrayLiteralExpression) {
          route.children = parseRouteArray(init, sourceFile);
        }
        break;
      case 'loadChildren': {
        route.lazy = true;
        const importPath = extractImportPath(init);
        if (importPath) {
          route.lazyImport = resolveImportPath(importPath, sourceFile.getFilePath());
        }
        break;
      }
      case 'data': {
        if (init.getKind() === SyntaxKind.ObjectLiteralExpression) {
          const title = getObjectStringProp(init, 'title')
                     || getObjectStringProp(init, 'breadcrumb')
                     || getObjectStringProp(init, 'label');
          if (title) route.title = title;
        }
        break;
      }
      case 'title':
        // Angular 14+ route-level title
        route.title = stripQuotes(init.getText());
        break;
    }
  }
  return route;
}

/** component: MyComponent → "MyComponent"; loadComponent: () => import(...).then(m => m.X) → "X" */
function extractComponentName(init) {
  const text = init.getText();
  if (init.getKind() === SyntaxKind.Identifier) return text;
  const thenMatch = text.match(/=>\s*\w+\.(\w+)\s*\)?\s*$/);
  if (thenMatch) return thenMatch[1];
  const idMatch = text.match(/^\w+$/);
  return idMatch ? idMatch[0] : null;
}

/** Extracts the string inside import('...') from a loadChildren initializer. */
function extractImportPath(init) {
  const m = init.getText().match(/import\s*\(\s*['"`]([^'"`]+)['"`]\s*\)/);
  return m ? m[1] : null;
}

/** Resolves a relative import path to an absolute file path with .ts extension. */
function resolveImportPath(importPath, fromFile) {
  const base = path.dirname(fromFile);
  let resolved = path.resolve(base, importPath);
  if (!resolved.endsWith('.ts')) resolved += '.ts';
  return resolved.replace(/\\/g, '/');
}

/** Attaches lazily-loaded route arrays as children of their loading route. */
function linkLazyChildren(routes, currentFile, routesByFile, visited) {
  visited = visited || new Set();
  for (const route of routes) {
    if (route.lazyImport) {
      const target = route.lazyImport;
      if (!visited.has(target)) {
        visited.add(target);
        // Match by normalized path (routesByFile keys use forward slashes already)
        for (const [fp, fileRoutes] of routesByFile) {
          if (fp.replace(/\\/g, '/') === target && fileRoutes !== routes) {
            route.children = route.children.concat(fileRoutes);
            break;
          }
        }
      }
    }
    if (route.children.length > 0) {
      linkLazyChildren(route.children, currentFile, routesByFile, visited);
    }
  }
}

/** Removes duplicate routes (same path+component) that arise from multiple declarations. */
function dedupeRoutes(routes) {
  const seen = new Set();
  return routes.filter(r => {
    const key = r.path + '|' + (r.componentName || '') + '|' + (r.redirectTo || '');
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/** Drops internal fields (lazyImport) before JSON output. */
function stripInternal(route) {
  return {
    path: route.path,
    componentName: route.componentName,
    title: route.title,
    redirectTo: route.redirectTo,
    lazy: route.lazy,
    children: route.children.map(stripInternal),
  };
}

function getObjectStringProp(obj, propName) {
  const prop = obj.getProperty(propName);
  if (!prop || !prop.getInitializer) return null;
  const init = prop.getInitializer();
  if (!init) return null;
  const k = init.getKind();
  if (k === SyntaxKind.StringLiteral || k === SyntaxKind.NoSubstitutionTemplateLiteral) {
    return init.getLiteralValue();
  }
  return null;
}

function stripQuotes(s) {
  return s.replace(/^['"`]|['"`]$/g, '');
}

// ---------------------------------------------------------------------------
// tsconfig.json finder
// ---------------------------------------------------------------------------

function findTsConfig(root) {
  const candidates = [
    path.join(root, 'tsconfig.app.json'),
    path.join(root, 'tsconfig.json'),
  ];
  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }
  return null;
}
