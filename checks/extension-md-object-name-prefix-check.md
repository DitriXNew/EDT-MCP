# extension-md-object-name-prefix-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `extension-md-object-name-prefix-check` |
| **Title** | Extension object name does not have extension prefix |
| **Description** | Checks that new objects in extensions have the extension prefix in their name |
| **Severity** | `MAJOR` |
| **Type** | `ERROR` |
| **Complexity** | `TRIVIAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies **new metadata objects** created in a **configuration extension** that don't have the **extension prefix** in their names.

### Why This Is Important

- **Conflict prevention**: Avoids name clashes with base configuration
- **Identification**: Easy to identify extension objects
- **Maintainability**: Clear which objects belong to which extension
- **Standards compliance**: Follows 1C extension development standards

---

## ❌ Error Example

### Error Message

```
The object "ObjectName" should have "ExtPrefix_" prefix
```

### Noncompliant XML Configuration

```xml
<!-- Extension with prefix "ME_" defined -->
<mdclass:Configuration xmlns:mdclass="...">
  <namePrefix>ME_</namePrefix>
  
  <!-- ❌ Wrong: New catalog without extension prefix -->
  <containedObjects>
    <type>Catalog</type>
    <objectBelonging>Adopted</objectBelonging>
    <ref>Catalog.Products</ref>                    <!-- OK: Adopted from base -->
  </containedObjects>
  <containedObjects>
    <type>Catalog</type>
    <objectBelonging>Own</objectBelonging>
    <ref>Catalog.CustomProducts</ref>              <!-- ❌ Missing ME_ prefix -->
  </containedObjects>
</mdclass:Configuration>

<!-- Extension catalog without prefix -->
<mdclass:Catalog uuid="..." name="CustomProducts">  <!-- ❌ Should be ME_CustomProducts -->
  <synonym>
    <key>en</key>
    <value>Custom Products</value>
  </synonym>
</mdclass:Catalog>
```

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- Extension with prefix "ME_" defined -->
<mdclass:Configuration xmlns:mdclass="...">
  <namePrefix>ME_</namePrefix>
  
  <!-- ✅ Correct: Adopted object keeps original name -->
  <containedObjects>
    <type>Catalog</type>
    <objectBelonging>Adopted</objectBelonging>
    <ref>Catalog.Products</ref>                    <!-- OK: Adopted from base -->
  </containedObjects>
  <!-- ✅ Correct: New object has extension prefix -->
  <containedObjects>
    <type>Catalog</type>
    <objectBelonging>Own</objectBelonging>
    <ref>Catalog.ME_CustomProducts</ref>           <!-- ✅ Has ME_ prefix -->
  </containedObjects>
</mdclass:Configuration>

<!-- Extension catalog with proper prefix -->
<mdclass:Catalog uuid="..." name="ME_CustomProducts">  <!-- ✅ Correct prefix -->
  <synonym>
    <key>en</key>
    <value>Custom Products</value>
  </synonym>
</mdclass:Catalog>
```

### Use Extension Prefix

```
Extension: MyExtension (Prefix: "ME_")
└── Catalogs
    ├── Products           ← Adopted from base (OK)
    └── ME_CustomProducts  ← New object (✅ has prefix)
    
Extension: MyExtension (Prefix: "ME_")
└── Documents
    ├── Order              ← Adopted from base (OK)
    └── ME_SpecialOrder    ← New object (✅ has prefix)
```

---

## 📋 Understanding Extension Prefixes

### What Is Extension Prefix

```
Extension Properties:
├── Name: MyCompanyExtension
├── Prefix: MC_                ← This prefix
└── Objects: ...

New objects should use: MC_ObjectName
```

### Adopted vs New Objects

| Object Type | Prefix Required |
|-------------|-----------------|
| Adopted from base | No (keeps original name) |
| New in extension | Yes (must have prefix) |
| Extended (added attributes) | No (object exists in base) |

---

## 📋 Naming Examples

### Correct Naming

```
Extension Prefix: "ABC_"

✅ ABC_CustomCatalog
✅ ABC_NewDocument
✅ ABC_SpecialReport
✅ ABC_HelperModule
✅ ABC_AdditionalRole
```

### Incorrect Naming

```
Extension Prefix: "ABC_"

❌ CustomCatalog        (missing ABC_)
❌ NewDocument          (missing ABC_)
❌ ABCCustomCatalog     (missing underscore)
❌ abc_CustomCatalog    (wrong case)
```

---

## 📋 All Object Types to Check

### Metadata Object Types

| Object Type | Example with Prefix |
|-------------|---------------------|
| Catalogs | `ME_CustomCatalog` |
| Documents | `ME_CustomDocument` |
| DataProcessors | `ME_CustomProcessor` |
| Reports | `ME_CustomReport` |
| CommonModules | `ME_CommonFunctions` |
| Roles | `ME_CustomRole` |
| ExchangePlans | `ME_CustomExchange` |
| InformationRegisters | `ME_CustomRegister` |
| AccumulationRegisters | `ME_CustomAccumReg` |
| CommonForms | `ME_CustomForm` |
| Enumerations | `ME_CustomEnum` |

---

## 📋 Best Practices for Extension Development

### Choose Meaningful Prefix

```
// ✅ Good prefixes:
"СТР_" - Company abbreviation (Cyrillic)
"ABC_" - Company abbreviation (Latin)
"PRJ_" - Project abbreviation
"EXT_" - Generic extension prefix

// ❌ Avoid:
"_" - Too short
"AAAA_" - Not meaningful
"MyExt" - No underscore separator
```

### Consistent Usage

```
// Use same prefix throughout extension
Extension: ProjectExtension (Prefix: "PRJ_")
├── Catalogs
│   ├── PRJ_Settings
│   └── PRJ_Templates
├── Documents
│   └── PRJ_Request
├── CommonModules
│   ├── PRJ_Common
│   └── PRJ_Server
└── Roles
    └── PRJ_User
```

---

## 📋 Adopted Objects

### When Prefix Is NOT Required

```
// Objects adopted from base configuration:
Extension: MyExtension (Prefix: "ME_")
└── Catalogs
    └── Products           ← Already exists in base
        └── Attributes
            └── ME_CustomField  ← New attribute needs prefix
            
// The catalog "Products" keeps its name
// But NEW attributes in it need the prefix
```

### Adding to Existing Objects

```
// Adopted catalog from base:
Catalog: Products (from base)
└── Attributes
    ├── Name                    ← From base, no prefix
    ├── Price                   ← From base, no prefix
    └── ME_CustomAttribute      ← Added in extension, needs prefix
```

---

## 🔧 How to Fix

### Step 1: Identify objects without prefix

Find new objects in extension that lack the prefix.

### Step 2: Rename objects

Add the extension prefix to the object name.

### Step 3: Update all references

Update code, forms, and queries that reference the object.

### Step 4: Verify synonyms

Synonyms don't need prefix (they're for users).

---

## 📋 Renaming Considerations

### Impact of Renaming

```
Renaming Object:
├── Update all code references
├── Update form data paths
├── Update query table names
├── Update role references
├── Update command references
└── Test thoroughly
```

### Using IDE Refactoring

```
EDT provides rename refactoring:
1. Right-click object
2. Refactor → Rename
3. Enter new name with prefix
4. IDE updates all references
```

---

## 📋 Multiple Extensions

### Different Prefixes

```
Extension 1: CompanyCore (Prefix: "CC_")
├── CC_Settings
└── CC_Utilities

Extension 2: ProjectModule (Prefix: "PM_")
├── PM_Reports
└── PM_Integration

// No conflicts because different prefixes
```

---

## 🔍 Technical Details

### What Is Checked

1. Extension configuration objects
2. Object names
3. Extension prefix setting
4. New vs adopted objects

### Check Implementation Class

```
com.e1c.v8codestyle.md.check.ExtensionMdObjectNamePrefixCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/check/
```

---

## 📚 References

- [Naming Conventions](https://its.1c.ru/db/v8std/content/485/hdoc)
