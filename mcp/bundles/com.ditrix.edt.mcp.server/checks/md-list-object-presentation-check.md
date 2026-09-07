# md-list-object-presentation-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `md-list-object-presentation-check` |
| **Title** | Neither Object presentation nor List presentation is not filled |
| **Description** | Checks that metadata objects have Object presentation or List presentation filled |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `TRIVIAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies metadata objects (catalogs, documents, etc.) where neither **Object presentation** nor **List presentation** properties are filled.

### Why This Is Important

- **User experience**: Presentations appear in UI, reports, dialogs
- **Localization**: Presentations can be localized
- **Clarity**: Users understand what they're working with
- **Professional look**: Default names may be unclear

---

## ❌ Error Example

### Error Message

```
Neither Object presentation nor List presentation is not filled
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Wrong: Neither objectPresentation nor listPresentation is filled -->
<mdclass:Catalog uuid="..." name="Products">
  <synonym>
    <key>en</key>
    <value>Products</value>
  </synonym>
  <!-- Missing objectPresentation -->          <!-- ❌ Should be filled -->
  <!-- Missing listPresentation -->            <!-- ❌ Should be filled -->
</mdclass:Catalog>

<mdclass:Document uuid="..." name="SalesOrder">
  <synonym>
    <key>en</key>
    <value>Sales Order</value>
  </synonym>
  <objectPresentation/>                        <!-- ❌ Empty element -->
  <listPresentation/>                          <!-- ❌ Empty element -->
</mdclass:Document>
```

### UI Impact

```
// Without presentations, user sees:
"Products" (generic)
"Sales Order" (from synonym)

// Instead of clear:
"Product" (object)
"Products" (list)
"Sales Order" (object)
"Sales Orders" (list)
```

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: Both presentations are filled -->
<mdclass:Catalog uuid="..." name="Products">
  <synonym>
    <key>en</key>
    <value>Products</value>
  </synonym>
  <objectPresentation>
    <key>en</key>
    <value>Product</value>                     <!-- ✅ Singular form -->
  </objectPresentation>
  <listPresentation>
    <key>en</key>
    <value>Products</value>                    <!-- ✅ Plural form -->
  </listPresentation>
</mdclass:Catalog>

<!-- ✅ With multiple languages -->
<mdclass:Document uuid="..." name="SalesOrder">
  <synonym>
    <key>en</key>
    <value>Sales Order</value>
  </synonym>
  <objectPresentation>
    <key>en</key>
    <value>Sales Order</value>
  </objectPresentation>
  <objectPresentation>
    <key>ru</key>
    <value>Заказ покупателя</value>
  </objectPresentation>
  <listPresentation>
    <key>en</key>
    <value>Sales Orders</value>
  </listPresentation>
  <listPresentation>
    <key>ru</key>
    <value>Заказы покупателей</value>
  </listPresentation>
</mdclass:Document>
```

### Fill Object and List Presentations

```
Catalog: Products
├── Name: Products
├── Synonym: Products
├── Object presentation: Product                 ✅
└── List presentation: Products                  ✅

Document: SalesOrder
├── Name: SalesOrder
├── Synonym: Sales Order
├── Object presentation: Sales Order             ✅
└── List presentation: Sales Orders              ✅
```

### With Localization

```
Catalog: Products
├── Object presentation:
│   ├── en: Product
│   └── ru: Товар
├── List presentation:
│   ├── en: Products
│   └── ru: Товары
```

---

## 📋 Understanding Presentations

### Object Presentation

```
Used when referring to a single item:
├── Form titles: "Product: Chair"
├── Messages: "Product saved"
├── Selection: "Select product"
└── References: "Linked product"
```

### List Presentation

```
Used when referring to multiple items:
├── Menu items: "Products"
├── Navigation: "Open Products"
├── Reports: "Products report"
└── Lists: "List of Products"
```

### Where They Appear

| Location | Presentation Used |
|----------|-------------------|
| Object form title | Object |
| List form title | List |
| New object command | Object ("Create Product") |
| Open list command | List ("Products") |
| Selection dialog | Object |
| Report header | List |

---

## 📋 Common Patterns

### Singular/Plural

```
Catalog: Customers
├── Object presentation: Customer        (singular)
└── List presentation: Customers          (plural)

Catalog: Companies
├── Object presentation: Company          (singular)
└── List presentation: Companies          (plural)
```

### Abbreviations

```
// Avoid abbreviations in presentations
❌ Object presentation: Cust
✅ Object presentation: Customer

❌ List presentation: Prods
✅ List presentation: Products
```

### Descriptive Names

```
Catalog: ItemCategories
├── Object presentation: Item Category    (clear meaning)
└── List presentation: Item Categories

// Not just:
├── Object presentation: Category         (too generic)
└── List presentation: Categories
```

---

## 📋 Localization Best Practices

### Multiple Languages

```
Document: Invoice
├── Object presentation:
│   ├── en: Invoice
│   ├── ru: Счёт
│   └── de: Rechnung
├── List presentation:
│   ├── en: Invoices
│   ├── ru: Счета
│   └── de: Rechnungen
```

### Using NStr Format

```
Object presentation = NStr("en='Invoice'; ru='Счёт'")
List presentation = NStr("en='Invoices'; ru='Счета'")
```

---

## 📋 When Both May Be Same

### Simple Cases

```
// For some objects, singular == plural
Catalog: Information
├── Object presentation: Information
└── List presentation: Information

Catalog: News
├── Object presentation: News
└── List presentation: News
```

### Technical Objects

```
// Objects not shown to users
InformationRegister: Settings
├── Object presentation: Settings Entry
└── List presentation: Settings

// But still fill them for consistency
```

---

## 🔧 How to Fix

### Step 1: Open metadata object

Find the catalog, document, or other object.

### Step 2: Fill Object presentation

Enter the singular form of the name.

### Step 3: Fill List presentation

Enter the plural form of the name.

### Step 4: Add translations

If multi-language, add all translations.

---

## 📋 Checking Existing Configuration

### Find Missing Presentations

```
All objects to check:
├── Catalogs
├── Documents
├── BusinessProcesses
├── Tasks
├── ChartsOfCharacteristicTypes
├── ChartsOfAccounts
├── ChartsOfCalculationTypes
├── ExchangePlans
└── Enumerations (may not need)
```

---

## 📋 Related Properties

### Other Presentation Properties

| Property | Purpose |
|----------|---------|
| Object presentation | Singular item reference |
| List presentation | Collection reference |
| Extended object presentation | Detailed singular |
| Extended list presentation | Detailed collection |
| Explanation | Help text |

### Priority

```
Platform uses in order:
1. Extended presentation (if set)
2. Regular presentation (if set)
3. Synonym (fallback)
4. Name (last resort)
```

---

## 🔍 Technical Details

### What Is Checked

1. Catalogs
2. Documents
3. Other list-based objects
4. Object presentation property
5. List presentation property

### Check Implementation Class

```
com.e1c.v8codestyle.md.check.MdListObjectPresentationCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/check/
```

---

## 📚 References

- [UI Standards](https://its.1c.ru/db/v8std)
