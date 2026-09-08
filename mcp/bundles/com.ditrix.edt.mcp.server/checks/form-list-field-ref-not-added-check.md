# form-list-field-ref-not-added-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `form-list-field-ref-not-added-check` |
| **Title** | The Ref attribute of dynamic list is not added to the table on the form |
| **Description** | Checks that the Ref field is added to dynamic list tables |
| **Severity** | `MAJOR` |
| **Type** | `ERROR` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies **dynamic list tables** that are missing the **Ref** field. The Ref field is essential for identifying selected rows and performing operations on them.

### Why This Is Important

- **Row identification**: Ref is needed to know which object is selected
- **Operations**: Actions on selected rows require Ref
- **Opening forms**: Double-click opens object using Ref
- **Performance**: Platform requests Ref data anyway

---

## ❌ Error Example

### Error Message

```
The Ref attribute of dynamic list is not added to the table on the form
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Noncompliant: Ref field is not added to FormTable columns -->
<Form.Form xmlns="http://g5.1c.ru/v8/dt/form">
  <items>
    <FormTable name="List">
      <columns>
        <!-- ❌ Missing: <FormField><name>Ref</name>...</FormField> -->
        <FormField>
          <name>Code</name>
          <id>11</id>
          <dataPath>List.Code</dataPath>
        </FormField>
        <FormField>
          <name>Description</name>
          <id>12</id>
          <dataPath>List.Description</dataPath>
        </FormField>
        <FormField>
          <name>Price</name>
          <id>13</id>
          <dataPath>List.Price</dataPath>
        </FormField>
      </columns>
    </FormTable>
  </items>
</Form.Form>
```

### Noncompliant Configuration

```
Form: CatalogListForm
└── Items
    └── List (DynamicList)
        ├── Code                      ✓ Added
        ├── Description               ✓ Added
        ├── Price                     ✓ Added
        └── (Ref is missing!)         ❌ Not added
```

### Code Problem

```bsl
// ❌ Cannot get current item reference
&AtClient
Procedure OpenItem(Command)
    CurrentData = Items.List.CurrentData;
    If CurrentData = Undefined Then
        Return;
    EndIf;
    
    // ❌ ERROR: Ref property doesn't exist!
    OpenForm("Catalog.Products.ObjectForm", 
        New Structure("Key", CurrentData.Ref));
EndProcedure
```

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: Ref field is added to FormTable columns -->
<Form.Form xmlns="http://g5.1c.ru/v8/dt/form">
  <items>
    <FormTable name="List">
      <columns>
        <FormField>
          <name>Ref</name>                       <!-- ✅ Ref is added -->
          <id>10</id>
          <visible>false</visible>               <!-- Can be hidden -->
          <userVisible>
            <common>false</common>
          </userVisible>
          <dataPath>List.Ref</dataPath>
          <useAlways>true</useAlways>
        </FormField>
        <FormField>
          <name>Code</name>
          <id>11</id>
          <dataPath>List.Code</dataPath>
        </FormField>
        <FormField>
          <name>Description</name>
          <id>12</id>
          <dataPath>List.Description</dataPath>
        </FormField>
        <FormField>
          <name>Price</name>
          <id>13</id>
          <dataPath>List.Price</dataPath>
        </FormField>
      </columns>
    </FormTable>
  </items>
</Form.Form>
```

### Add Ref Field to List

```
Form: CatalogListForm
└── Items
    └── List (DynamicList)
        ├── Ref                       ✅ Added (can be hidden)
        ├── Code                      ✓ Added
        ├── Description               ✓ Added
        └── Price                     ✓ Added
```

### Working Code

```bsl
// ✅ Ref is available
&AtClient
Procedure OpenItem(Command)
    CurrentData = Items.List.CurrentData;
    If CurrentData = Undefined Then
        Return;
    EndIf;
    
    // ✅ Works correctly
    OpenForm("Catalog.Products.ObjectForm", 
        New Structure("Key", CurrentData.Ref));
EndProcedure
```

---

## 📋 Why Ref Field Is Required

### Use Cases Requiring Ref

| Use Case | Needs Ref |
|----------|-----------|
| Open object form | ✅ Yes |
| Delete object | ✅ Yes |
| Copy object | ✅ Yes |
| Get object data | ✅ Yes |
| Pass to reports | ✅ Yes |
| Print selected | ✅ Yes |

### Standard Operations

```bsl
// All standard operations need Ref
CurrentData = Items.List.CurrentData;
Ref = CurrentData.Ref;

// Open
OpenForm("Catalog.Products.ObjectForm", New Structure("Key", Ref));

// Delete
ObjectToDelete = Ref.GetObject();
ObjectToDelete.Delete();

// Copy
OpenForm("Catalog.Products.ObjectForm", 
    New Structure("CopyingValue", Ref));

// Get attribute
Value = Ref.SomeAttribute;
```

---

## 📋 Proper Ref Field Configuration

### Add Ref with Correct Settings

```
Form: CatalogListForm
└── Items
    └── List (DynamicList)
        └── Ref
            ├── Visible: False           (hide from user)
            ├── UserVisible: False       (prevent user from showing)
            └── UseAlways: True          (always load data)
```

### Related Checks

| Check | Purpose |
|-------|---------|
| `form-list-field-ref-not-added-check` | Ref is added |
| `form-list-ref-use-always-flag-disabled-check` | UseAlways is True |
| `form-list-ref-user-visibility-enabled-check` | UserVisible is False |

---

## 📋 How to Add Ref Field

### In Form Designer

1. Open form in Designer
2. Find the dynamic list table
3. Right-click → Add field
4. Select "Ref" from available fields
5. Configure visibility settings

### Programmatically (If Needed)

```bsl
// Dynamic forms: add Ref field
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // Usually done in designer, not code
    // But for dynamic forms:
    NewField = Items.Add("Ref", Type("FormField"), Items.List);
    NewField.DataPath = "List.Ref";
    NewField.Visible = False;
EndProcedure
```

---

## 📋 Hiding Ref from User

The Ref field should typically be:
- **Added** to the list ✅
- **Hidden** from display ✅
- **Not user-visible** ✅

```
Ref Field Properties:
├── Visible = False          // Don't show column
├── UserVisible = False      // User can't show it
└── UseAlways = True         // Always retrieve data
```

---

## 📋 Performance Consideration

### Why UseAlways Matters

```bsl
// Without UseAlways, Ref may not be loaded
CurrentData = Items.List.CurrentData;

// If user didn't scroll to load this row fully:
Ref = CurrentData.Ref; // ❌ May be Undefined!

// With UseAlways = True:
Ref = CurrentData.Ref; // ✅ Always available
```

---

## 🔧 How to Fix

### Step 1: Open form in Designer

Navigate to the form with the dynamic list.

### Step 2: Add Ref field

Add the Ref field to the table.

### Step 3: Configure field settings

- Set Visible = False
- Set UserVisible = False
- Set UseAlways = True

### Step 4: Verify

Test that operations using Ref work correctly.

---

## 📋 Common Mistakes

### Mistake 1: Ref in DynamicList but not in Table

```
// ❌ Ref exists in DynamicList data but not added to form table
DynamicList.MainTable has Ref → but table Items doesn't include it
```

### Mistake 2: Ref Removed by Accident

```
// ❌ Ref was removed during form cleanup
// User thought hidden field was unnecessary
```

### Mistake 3: Custom Query Without Ref

```
// ❌ Custom query for DynamicList doesn't select Ref
SELECT Code, Description FROM Catalog.Products
// Should be: SELECT Ref, Code, Description FROM Catalog.Products
```

---

## 🔍 Technical Details

### What Is Checked

1. Dynamic list form items
2. Presence of Ref field
3. Table column configuration

### Check Implementation Class

```
com.e1c.v8codestyle.form.check.FormListFieldRefNotAddedCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.form/src/com/e1c/v8codestyle/form/check/
```

---

## 📚 References

- [Form List Ref Use Always Flag Disabled Check](form-list-ref-use-always-flag-disabled-check.md)
- [Form List Ref User Visibility Enabled Check](form-list-ref-user-visibility-enabled-check.md)
