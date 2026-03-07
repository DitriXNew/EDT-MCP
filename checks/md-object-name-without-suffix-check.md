# md-object-name-without-suffix-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `md-object-name-without-suffix-check` |
| **Title** | Metadata object name without required suffix |
| **Description** | Checks that metadata object names include required suffixes for identification |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `TRIVIAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies **metadata object names** that are missing required **suffixes**. Suffixes help identify object types and purposes when the object type isn't clear from context.

### Why This Is Important

- **Clarity**: Suffix identifies object purpose
- **Conventions**: Standard naming patterns
- **Code readability**: Easier to understand references
- **Maintenance**: Quick identification in lists

---

## ❌ Error Example

### Error Message

```
Metadata object name does not have required suffix
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Noncompliant: Common modules without required suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>WorkWithDocuments</name>           <!-- ❌ Missing suffix: Server/Client/etc -->
  <server>true</server>
  <clientManagedApplication>false</clientManagedApplication>
</mdclass:CommonModule>

<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>CatalogOperations</name>           <!-- ❌ Missing suffix -->
  <clientManagedApplication>true</clientManagedApplication>
  <server>false</server>
</mdclass:CommonModule>

<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>ServerFunctions</name>             <!-- ❌ Missing suffix -->
  <server>true</server>
  <serverCall>true</serverCall>
</mdclass:CommonModule>
```

### Noncompliant Configuration

```
// CommonModules without suffix
CommonModule: WorkWithDocuments       ❌ Missing suffix
CommonModule: CatalogOperations       ❌ Missing suffix
CommonModule: ServerFunctions         ❌ Missing suffix

// Depending on suffix list configuration
DataProcessor: ImportData             ❌ May need suffix
Report: SalesAnalysis                 ❌ May need suffix
```

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: Common modules with proper suffixes -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>WorkWithDocumentsServer</name>     <!-- ✅ Server suffix -->
  <server>true</server>
  <clientManagedApplication>false</clientManagedApplication>
</mdclass:CommonModule>

<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>CatalogOperationsClient</name>     <!-- ✅ Client suffix -->
  <clientManagedApplication>true</clientManagedApplication>
  <server>false</server>
</mdclass:CommonModule>

<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>ServerFunctionsServerCall</name>   <!-- ✅ ServerCall suffix -->
  <server>true</server>
  <serverCall>true</serverCall>
</mdclass:CommonModule>

<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>WorkWithDocumentsCached</name>     <!-- ✅ Cached suffix -->
  <server>true</server>
  <returnValuesReuse>DuringSession</returnValuesReuse>
</mdclass:CommonModule>
```

### Add Required Suffixes

```
// CommonModules with suffixes
CommonModule: WorkWithDocumentsServer       ✅ Server suffix
CommonModule: CatalogOperationsClient       ✅ Client suffix
CommonModule: ServerFunctionsServerCall     ✅ ServerCall suffix

// Or context-specific suffixes
CommonModule: WorkWithDocumentsCached       ✅ Caching module
CommonModule: WorkWithDocumentsReUse        ✅ Reuse module
```

---

## 📋 Common Suffix Patterns

### Common Module Suffixes

| Suffix | Purpose | Compilation |
|--------|---------|-------------|
| `Server` | Server-side module | Server only |
| `ServerCall` | Called from client | Server, callable |
| `Client` | Client-side module | Client only |
| `ClientServer` | Both contexts | Client + Server |
| `Cached` | With caching | Server + Session cache |
| `ReUse` | Reusable functions | Any |
| `Global` | Global scope | Client |
| `Overridable` | Override points | Any |

### Examples

```
CommonModule: UsersServer           ← Server functions
CommonModule: UsersServerCall       ← Client-callable server
CommonModule: UsersClient           ← Client functions
CommonModule: UsersClientServer     ← Shared functions
CommonModule: UsersCached           ← Cached data
```

---

## 📋 Why Suffixes Matter

### Without Suffix

```bsl
// Unclear compilation context:
Result = Users.GetCurrentUser();  // Server? Client? Both?
```

### With Suffix

```bsl
// Clear context:
Result = UsersServerCall.GetCurrentUserAtServer();  // Server call
Name = UsersClient.FormatUserName(User);            // Client code
```

---

## 📋 Configuration

### Suffix List Parameter

```
// Configure required suffixes:
nameSuffixList = Server,ServerCall,Client,ClientServer,Cached,ReUse,Global,Overridable

// Or for specific project:
nameSuffixList = Srv,Cli,Shared
```

### Object Type Specific

```
// Different suffixes for different object types:
CommonModules: Server, Client, etc.
Reports: Report (if needed)
DataProcessors: (usually no suffix needed)
```

---

## 📋 BSP Naming Conventions

### Standard Suffixes in BSP

```
BSP CommonModule naming:
├── <Subsystem>Server
├── <Subsystem>ServerCall
├── <Subsystem>Client
├── <Subsystem>ClientServer
├── <Subsystem>Cached
├── <Subsystem>Overridable
└── <Subsystem>Internal
```

### Examples from BSP

```
UsersServer              ← Core user server functions
UsersServerCall          ← Client-callable user functions
UsersClientServer        ← Shared user functions
UsersOverridable         ← Customization points
UsersInternal            ← Internal implementation
```

---

## 📋 Object Types and Suffixes

### Where Suffixes Apply

| Object Type | Suffix Common | Example |
|-------------|---------------|---------|
| CommonModules | Yes | `UserServer`, `UserClient` |
| DataProcessors | Sometimes | `ImportDataProcessor` |
| Reports | Rarely | `SalesReport` |
| Catalogs | No | `Products` (not `ProductsCatalog`) |
| Documents | No | `Order` (not `OrderDocument`) |

### Why Some Objects Don't Need Suffix

```
// Object type is already clear:
Catalog.Products           ← "Catalog" is the suffix
Document.Order             ← "Document" is the suffix
Report.SalesAnalysis       ← "Report" may not need more

// But CommonModule has no automatic type indicator
CommonModule.Users         ← Is it server? Client? Cached?
```

---

## 🔧 How to Fix

### Step 1: Identify missing suffixes

Find objects without required suffix.

### Step 2: Determine correct suffix

Based on object purpose and compilation.

### Step 3: Rename with suffix

Add appropriate suffix to name.

### Step 4: Update references

Update all code references.

---

## 📋 Choosing Correct Suffix

### Decision Tree

```
Is it a CommonModule?
├── Yes → What's the compilation?
│         ├── Server only → "Server"
│         ├── Server, callable → "ServerCall"
│         ├── Client only → "Client"
│         ├── Client + Server → "ClientServer"
│         └── Cached → "Cached"
│
└── No → Check project conventions
```

### By Compilation Setting

```
CommonModule compilation settings:
├── Server = True, Client = False
│   └── Suffix: Server
├── Server = True, Client = True
│   └── Suffix: ClientServer
├── Server = True, ServerCall = True
│   └── Suffix: ServerCall
└── Client = True, Server = False
    └── Suffix: Client
```

---

## 📋 Migration Example

### Before

```
CommonModule: DocumentOperations
├── Compilation: Server
└── (No suffix indicating this)
```

### After

```
CommonModule: DocumentOperationsServer
├── Compilation: Server
└── Suffix indicates server-side
```

### Code Updates

```bsl
// Before
Result = DocumentOperations.Calculate();

// After
Result = DocumentOperationsServer.Calculate();
```

---

## 🔍 Technical Details

### What Is Checked

1. Metadata object names
2. Comparison with suffix list
3. Missing suffix detection

### Check Implementation Class

```
com.e1c.v8codestyle.md.check.MdObjectNameWithoutSuffix
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/check/
```

---

## 📚 References

- [1C Naming Standards](https://its.1c.ru/db/v8std/content/485/hdoc)
