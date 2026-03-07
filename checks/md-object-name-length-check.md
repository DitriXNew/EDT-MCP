# md-object-name-length-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `md-object-name-length-check` |
| **Title** | Metadata object name length |
| **Description** | Checks that metadata object names do not exceed maximum length |
| **Severity** | `MAJOR` |
| **Type** | `ERROR` |
| **Complexity** | `TRIVIAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies **metadata object names** that exceed the **maximum allowed length**. Excessively long names cause issues with database column names and code readability.

### Why This Is Important

- **Database limits**: SQL column names have length limits
- **Readability**: Long names are hard to read/type
- **Maintenance**: Shorter names are easier to work with
- **Platform limits**: Some operations may fail with long names

---

## ❌ Error Example

### Error Message

```
Metadata object name should be less than 80
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Wrong: Object name exceeds 80 characters -->
<mdclass:Catalog uuid="..." 
  name="VeryLongCatalogNameThatExceedsMaximumAllowedLengthForMetadataObjectsAndCausesProblems">
  <!-- ❌ Name is too long (88 characters) -->
</mdclass:Catalog>

<mdclass:Document uuid="..." 
  name="ThisIsAnExtremelyLongDocumentNameThatWillCauseIssuesWithDatabaseAndMaintainability">
  <!-- ❌ Name is too long (85 characters) -->
</mdclass:Document>

<mdclass:InformationRegister uuid="..." 
  name="SuperLongInformationRegisterNameThatNoOneShouldEverUseInProductionEnvironments">
  <!-- ❌ Name is too long (80+ characters) -->
</mdclass:InformationRegister>
```

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: Concise and clear names within limit -->
<mdclass:Catalog uuid="..." name="Products">
  <!-- ✅ 8 characters - short and clear -->
</mdclass:Catalog>

<mdclass:Document uuid="..." name="SalesInvoice">
  <!-- ✅ 12 characters - descriptive but concise -->
</mdclass:Document>

<mdclass:InformationRegister uuid="..." name="ExchangeRates">
  <!-- ✅ 13 characters - appropriate length -->
</mdclass:InformationRegister>

<mdclass:Catalog uuid="..." name="CustomerPaymentSchedule">
  <!-- ✅ 23 characters - longer but still readable -->
</mdclass:Catalog>
```

### Use Concise Names

```
Catalog: Products                          ✅ Short and clear
Catalog: CustomerOrders                    ✅ Descriptive but concise

Document: SalesInvoice                     ✅ Standard length
Document: ProductionOrder                  ✅ Clear meaning

InformationRegister: ExchangeRates         ✅ Appropriate length
InformationRegister: UserSettings          ✅ Understandable
```

---

## 📋 Maximum Name Length

### Default Limit

| Parameter | Default Value |
|-----------|---------------|
| Maximum Name Length | 80 characters |

### Why 80 Characters

```
Database considerations:
├── SQL Server: 128 character limit for identifiers
├── PostgreSQL: 63 character limit by default
├── Platform adds prefixes: _Document_, _InfoRg_, etc.
├── Leaves room for attribute names
└── 80 is safe across all scenarios
```

---

## 📋 Naming Best Practices

### Good Names

```
// Clear, concise, meaningful
✅ Products
✅ SalesOrder
✅ CustomerPayment
✅ InventoryBalance
✅ ProductPrices
```

### Names to Avoid

```
// Too long
❌ ProductCatalogWithAllDetailsAndPricesAndDescriptions
❌ SalesOrderDocumentForCustomerPurchases
❌ InformationAboutCustomerPaymentHistory

// Too abbreviated
❌ Prd
❌ SO
❌ CstPmt
```

### Balanced Approach

```
// Find balance between length and clarity
ProductCatalogDetails → ProductDetails
SalesOrderDocument → SalesOrder
CustomerPaymentInformation → CustomerPayments
```

---

## 📋 Impact of Long Names

### Database Issues

```
// Table name in database:
_Document_VeryLongDocumentName... → may be truncated

// Column names:
_Document_VeryLongName_AttributeName... → may exceed limits
```

### Code Readability

```bsl
// Hard to read:
Query.Text = "SELECT * FROM Document.VeryLongDocumentNameThatIsHardToReadAndType AS D
              |WHERE D.Attribute = &Value";

// Better:
Query.Text = "SELECT * FROM Document.SalesOrder AS SO
              |WHERE SO.Customer = &Customer";
```

### Autocomplete Issues

```
// In IDE, long names:
├── Harder to find in autocomplete
├── Truncated in dropdown
└── More typing required
```

---

## 📋 Renaming Considerations

### Before Renaming

```
1. Check all code references
2. Check form data paths
3. Check query table names
4. Check web service operations
5. Check integration points
6. Plan data migration
```

### Using IDE Refactoring

```
// EDT Rename:
1. Right-click object
2. Refactor → Rename
3. Enter shorter name
4. Review changes
5. Apply
```

---

## 📋 Configuration Parameter

### Customizing Maximum Length

```
// Default
maximumNameLength = 80

// Can be adjusted per project
maximumNameLength = 60  // Stricter
maximumNameLength = 100 // More lenient (not recommended)
```

---

## 📋 Object Types Affected

### All Metadata Object Types

| Type | Example Max Length Name |
|------|-------------------------|
| Catalogs | CustomerClassification (22 chars) |
| Documents | SalesReturnOrder (16 chars) |
| InformationRegisters | CurrencyExchangeRates (21 chars) |
| AccumulationRegisters | InventoryBalance (16 chars) |
| CommonModules | CommonFunctionsServer (21 chars) |
| Reports | SalesAnalysis (13 chars) |
| DataProcessors | DataImportWizard (16 chars) |

---

## 🔧 How to Fix

### Step 1: Identify long names

Find objects with names exceeding limit.

### Step 2: Create shorter alternatives

Think of concise but clear replacements.

### Step 3: Rename objects

Use IDE refactoring to rename.

### Step 4: Update documentation

Update any external references.

---

## 📋 Abbreviation Guidelines

### Acceptable Abbreviations

```
// Well-known abbreviations OK:
ID (Identifier)
VAT (Value Added Tax)
HR (Human Resources)
CRM (Customer Relationship Management)
```

### Avoid Custom Abbreviations

```
// Don't invent abbreviations:
❌ CustOrd (Customer Order)
❌ InvBal (Inventory Balance)
❌ ProdCat (Product Category)

// Use full words:
✅ CustomerOrder
✅ InventoryBalance
✅ ProductCategory
```

---

## 📋 Compound Names

### CamelCase Best Practices

```
// Each word provides meaning:
✅ SalesInvoice (2 words)
✅ CustomerPaymentHistory (3 words)
✅ ProductInventoryLevel (3 words)

// Limit to 3-4 words when possible:
✅ SalesOrderLineItem (4 words - OK)
❌ SalesOrderLineItemDiscountCalculation (too long)
```

---

## 🔍 Technical Details

### What Is Checked

1. All metadata object names
2. Name length in characters
3. Comparison with maximum limit

### Check Implementation Class

```
com.e1c.v8codestyle.md.check.MdObjectNameLength
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/check/
```

---

## 📚 References

- [1C Naming Conventions](https://its.1c.ru/db/v8std/content/485/hdoc)
- [Database Identifier Limits](https://learn.microsoft.com/en-us/sql/relational-databases/databases/database-identifiers?view=sql-server-ver17)
- [Code Style Guidelines](https://its.1c.ru/db/v8std)
