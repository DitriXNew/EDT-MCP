# form-list-ref-use-always-flag-disabled-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `form-list-ref-use-always-flag-disabled-check` |
| **Title** | UseAlways flag is disabled for the Ref field |
| **Description** | Checks that the UseAlways flag is enabled for the Ref field in dynamic lists |
| **Severity** | `MAJOR` |
| **Type** | `ERROR` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies when the **UseAlways** flag is **disabled** for the **Ref** field in a dynamic list table. When disabled, the Ref value may not be loaded for all rows, causing issues with row operations.

### Why This Is Important

- **Data availability**: Ref must always be available
- **Operations**: Selected row operations need Ref
- **Reliability**: Prevents "Undefined" Ref errors
- **User experience**: All rows work consistently

---

## ❌ Error Example

### Error Message

```
UseAlways flag is disabled for the Ref field
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Noncompliant: UseAlways is false for Ref field -->
<Form.Form xmlns="http://g5.1c.ru/v8/dt/form">
  <items>
    <FormTable name="List">
      <columns>
        <FormField>
          <name>Ref</name>
          <id>10</id>
          <visible>false</visible>
          <userVisible>
            <common>false</common>
          </userVisible>
          <dataPath>List.Ref</dataPath>
          <useAlways>false</useAlways>           <!-- ❌ Should be true -->
        </FormField>
        <FormField>
          <name>Description</name>
          <id>11</id>
          <dataPath>List.Description</dataPath>
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
        └── Ref
            ├── Visible: False
            └── UseAlways: False     ❌ Should be True
```

### Code Problem

```bsl
// ❌ Ref may be Undefined for some rows
&AtClient
Procedure ProcessSelected(Command)
    For Each SelectedRow In Items.List.SelectedRows Do
        RowData = Items.List.RowData(SelectedRow);
        
        // ❌ May be Undefined if UseAlways = False!
        If RowData.Ref = Undefined Then
            // Error: cannot process this row
        EndIf;
        
        ProcessItem(RowData.Ref);
    EndDo;
EndProcedure
```

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: UseAlways is true for Ref field -->
<Form.Form xmlns="http://g5.1c.ru/v8/dt/form">
  <items>
    <FormTable name="List">
      <columns>
        <FormField>
          <name>Ref</name>
          <id>10</id>
          <visible>false</visible>
          <userVisible>
            <common>false</common>
          </userVisible>
          <dataPath>List.Ref</dataPath>
          <useAlways>true</useAlways>            <!-- ✅ Enabled -->
        </FormField>
        <FormField>
          <name>Description</name>
          <id>11</id>
          <dataPath>List.Description</dataPath>
        </FormField>
      </columns>
    </FormTable>
  </items>
</Form.Form>
```

### Enable UseAlways Flag

```
Form: CatalogListForm
└── Items
    └── List (DynamicList)
        └── Ref
            ├── Visible: False
            └── UseAlways: True      ✅ Enabled
```

### Reliable Code

```bsl
// ✅ Ref is always available
&AtClient
Procedure ProcessSelected(Command)
    For Each SelectedRow In Items.List.SelectedRows Do
        RowData = Items.List.RowData(SelectedRow);
        
        // ✅ Always has value
        ProcessItem(RowData.Ref);
    EndDo;
EndProcedure
```

---

## 📋 Understanding UseAlways

### What UseAlways Does

| UseAlways | Behavior |
|-----------|----------|
| `True` | Field data is always loaded from database |
| `False` | Field data loaded only when visible or explicitly requested |

### Why Ref Needs UseAlways

```
Dynamic List Loading:
├── User scrolls list
├── Platform loads visible fields
├── Hidden fields with UseAlways=False may not load
└── Ref without UseAlways → may be Undefined

With UseAlways=True:
├── User scrolls list
├── Platform loads visible fields
├── Ref is ALWAYS loaded
└── All operations work reliably
```

---

## 📋 Common Issues Without UseAlways

### Issue 1: Opening Form Fails

```bsl
// Without UseAlways on Ref
&AtClient
Procedure ListSelection(Item, SelectedRow, Field, StandardProcessing)
    StandardProcessing = False;
    
    CurrentData = Items.List.CurrentData;
    // ❌ CurrentData.Ref may be Undefined
    OpenForm("Catalog.Products.ObjectForm", 
        New Structure("Key", CurrentData.Ref));
EndProcedure
```

### Issue 2: Bulk Operations Fail

```bsl
// Without UseAlways on Ref
&AtClient
Procedure DeleteSelected(Command)
    RefsToDelete = New Array;
    
    For Each RowID In Items.List.SelectedRows Do
        RowData = Items.List.RowData(RowID);
        // ❌ Some Refs may be Undefined
        If RowData.Ref <> Undefined Then
            RefsToDelete.Add(RowData.Ref);
        EndIf;
    EndDo;
    
    // Some items not deleted because Ref was Undefined!
EndProcedure
```

### Issue 3: Printing Selected Items

```bsl
// Without UseAlways on Ref
&AtClient
Procedure Print(Command)
    SelectedRefs = New Array;
    
    For Each RowID In Items.List.SelectedRows Do
        RowData = Items.List.RowData(RowID);
        // ❌ Partial selection printed
        SelectedRefs.Add(RowData.Ref);
    EndDo;
    
    PrintDocuments(SelectedRefs);
EndProcedure
```

---

## 📋 Correct Ref Field Configuration

### Complete Settings

```
Ref Field Properties:
├── Visible = False              // Hide column from display
├── UserVisible = False          // Prevent user showing it
└── UseAlways = True             // Always load Ref data
```

### Why Each Setting Matters

| Setting | Purpose |
|---------|---------|
| Visible = False | User doesn't need to see it |
| UserVisible = False | User can't accidentally show it |
| UseAlways = True | Data always available in code |

---

## 🔧 How to Fix

### Step 1: Open form in Designer

Navigate to the form with the dynamic list.

### Step 2: Find Ref field

Locate the Ref field in the table.

### Step 3: Enable UseAlways

Set UseAlways = True in properties.

### Step 4: Verify

Test bulk operations work for all rows.

---

## 📋 Performance Considerations

### Does UseAlways Affect Performance?

```
Minimal Impact:
├── Ref is a small value (UUID)
├── Database always has Ref indexed
├── Platform likely fetches it anyway
└── Reliability > minor performance gain
```

### When NOT to Use UseAlways

Only disable UseAlways for:
- Large text fields not needed in code
- Binary data fields
- Fields only used for display

```
// OK to have UseAlways=False:
├── Description (large text)
├── Photo (binary)
├── FullText (very large)

// Should have UseAlways=True:
├── Ref (always needed)
├── Key identifiers
├── Fields used in selection processing
```

---

## 📋 Related Checks

| Check | Ensures |
|-------|---------|
| `form-list-field-ref-not-added-check` | Ref field exists |
| `form-list-ref-use-always-flag-disabled-check` | UseAlways is True |
| `form-list-ref-user-visibility-enabled-check` | UserVisible is False |

All three should pass for proper Ref field configuration.

---

## 🔍 Technical Details

### What Is Checked

1. Ref field in dynamic list tables
2. UseAlways property value
3. Flag must be True

### Check Implementation Class

```
com.e1c.v8codestyle.form.check.FormListRefUseAlwaysFlagDisabledCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.form/src/com/e1c/v8codestyle/form/check/
```

---

## 📚 References

- [Form List Field Ref Not Added Check](form-list-field-ref-not-added-check.md)
- [Form List Ref User Visibility Enabled Check](form-list-ref-user-visibility-enabled-check.md)
