# db-object-anyref-type

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `db-object-anyref-type` |
| **Title** | AnyRef type should not be used in database object attributes |
| **Description** | Check that AnyRef type is not used in object attributes |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [728](https://its.1c.ru/db/v8std/content/728/hdoc) |

---

## 🎯 What This Check Does

This check validates that database object attributes (catalogs, documents, etc.) do not use the generic `AnyRef` type. Instead, specific reference types should be defined.

### Why This Is Important

- **Type safety**: Prevents storing incompatible references
- **Query performance**: Database can use proper indexes
- **Data integrity**: Only valid reference types can be stored
- **Code clarity**: Developers know what types to expect
- **Standards compliance**: Follows Standard 728

---

## ❌ Error Example

### Error Message

```
AnyRef type should not be used in database object attributes
```

**Russian:**
```
Тип ЛюбаяСсылка не должен использоваться в реквизитах объекта базы данных
```

### Noncompliant Code Example

```xml
<!-- Catalog attribute definition -->
<Attribute>
    <Name>RelatedObject</Name>
    <Type>
        <Type>AnyRef</Type>  ❌ Too generic
    </Type>
</Attribute>
```

**In Designer:**
- Attribute name: `RelatedObject`
- Type: `AnyRef` ❌

---

## ✅ Compliant Solution

### Correct Type Definition

```xml
<!-- Catalog attribute definition -->
<Attribute>
    <Name>RelatedObject</Name>
    <Type>
        <Type>CatalogRef.Products</Type>
        <Type>CatalogRef.Services</Type>
        <Type>DocumentRef.Invoice</Type>
    </Type>
</Attribute>
```

**In Designer:**
- Attribute name: `RelatedObject`
- Type: Composite type with specific references ✅
  - `CatalogRef.Products`
  - `CatalogRef.Services`
  - `DocumentRef.Invoice`

---

## 📖 Understanding AnyRef

### What Is AnyRef

`AnyRef` is a generic type that can hold a reference to ANY object in the database:
- Any catalog item
- Any document
- Any chart of accounts item
- etc.

### Problems with AnyRef

| Problem | Impact |
|---------|--------|
| No type checking | Can store invalid references |
| Poor query performance | No specific indexes |
| Runtime errors | Type mismatches discovered late |
| Maintenance difficulty | Unclear what types are expected |
| Data corruption risk | Wrong references can be stored |

---

## 📋 When to Use Specific Types

### Common Patterns

**Instead of:**
```
Attribute: ParentDocument (AnyRef)
```

**Use:**
```
Attribute: ParentDocument (Composite type)
  - DocumentRef.SalesOrder
  - DocumentRef.PurchaseOrder
  - DocumentRef.TransferOrder
```

### Type Selection Guidelines

| Scenario | Recommended Approach |
|----------|---------------------|
| Known related objects | List all specific types |
| Base document | Use `DefinedType` |
| Dynamic relationships | Consider register instead |
| Truly any object | Very rare - reconsider design |

---

## 📋 Using DefinedType Instead

### Create a Defined Type

For frequently used composite types, create a `DefinedType`:

```xml
<DefinedType>
    <Name>SalesDocument</Name>
    <Type>
        <Type>DocumentRef.Invoice</Type>
        <Type>DocumentRef.SalesOrder</Type>
        <Type>DocumentRef.Quotation</Type>
    </Type>
</DefinedType>
```

### Use Defined Type in Attributes

```xml
<Attribute>
    <Name>BaseDocument</Name>
    <Type>
        <Type>DefinedType.SalesDocument</Type>
    </Type>
</Attribute>
```

---

## 🔧 How to Fix

### Step 1: Identify affected attributes

Find all attributes with `AnyRef` type in:
- Catalogs
- Documents
- Information registers
- Other database objects

### Step 2: Analyze actual usage

Query the database to see what types are actually stored:

```bsl
Query = New Query;
Query.Text = 
    "SELECT DISTINCT
    |   VALUETYPE(RelatedObject) AS ObjectType
    |FROM
    |   Catalog.MyObject";

Result = Query.Execute().Unload();
```

### Step 3: Replace with specific types

Change attribute type from `AnyRef` to composite type with only needed types:

**Before:**
- Type: `AnyRef`

**After:**
- Type: Composite
  - `CatalogRef.Products`
  - `CatalogRef.Services`
  - `DocumentRef.Invoice`

### Step 4: Update related code

Update any code that works with the attribute:

```bsl
// May need type checking
If TypeOf(Object.RelatedObject) = Type("CatalogRef.Products") Then
    // Process product
ElsIf TypeOf(Object.RelatedObject) = Type("DocumentRef.Invoice") Then
    // Process invoice
EndIf;
```

---

## ⚠️ Migration Considerations

### Data Validation

Before changing the type, validate existing data:

```bsl
// Find objects with unexpected types
Query = New Query;
Query.Text = 
    "SELECT *
    |FROM Catalog.MyObject
    |WHERE NOT RelatedObject REFS Catalog.Products
    |  AND NOT RelatedObject REFS Catalog.Services
    |  AND NOT RelatedObject REFS Document.Invoice
    |  AND RelatedObject <> UNDEFINED";
```

### Handling Invalid Data

If invalid data exists:
1. Create a data migration procedure
2. Convert or clear invalid references
3. Then change the attribute type

---

## 🔍 Technical Details

### What Is Checked

1. Examines database object metadata
2. Finds attributes with AnyRef type
3. Reports each occurrence

### Check Implementation Class

```
com.e1c.v8codestyle.md.check.DbObjectAnyRefType
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 728](https://its.1c.ru/db/v8std/content/728/hdoc)
