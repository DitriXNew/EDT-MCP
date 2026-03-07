# form-list-ref-user-visibility-enabled-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `form-list-ref-user-visibility-enabled-check` |
| **Title** | User visibility is not disabled for the Ref field |
| **Description** | Checks that user visibility is disabled for the Ref field in dynamic lists |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `TRIVIAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies when the **UserVisible** (user visibility) setting is **enabled** for the **Ref** field in a dynamic list. The Ref field is technical and should not be shown to users.

### Why This Is Important

- **User experience**: Technical fields confuse users
- **Clean UI**: Only meaningful data should be visible
- **Security**: Internal identifiers should be hidden
- **Standards**: Ref is for internal use only

---

## ❌ Error Example

### Error Message

```
User visibility is not disabled for the Ref field (Ref) of the List table
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Noncompliant: UserVisible is not disabled for Ref field -->
<Form.Form xmlns="http://g5.1c.ru/v8/dt/form">
  <items>
    <FormTable name="List">
      <columns>
        <FormField>
          <name>Ref</name>
          <id>10</id>
          <visible>false</visible>
          <userVisible>
            <common>true</common>                 <!-- ❌ Should be false -->
          </userVisible>
          <dataPath>List.Ref</dataPath>
          <useAlways>true</useAlways>
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
            ├── UseAlways: True
            └── UserVisible: True     ❌ Should be False
```

### UI Problem

When UserVisible = True, user can:
1. Right-click on table header
2. Select "Configure list..."
3. Add Ref column to display
4. See ugly UUID values like "e8f1a2b3-4c5d-6e7f-8a9b-0c1d2e3f4a5b"

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: UserVisible is disabled for Ref field -->
<Form.Form xmlns="http://g5.1c.ru/v8/dt/form">
  <items>
    <FormTable name="List">
      <columns>
        <FormField>
          <name>Ref</name>
          <id>10</id>
          <visible>false</visible>
          <userVisible>
            <common>false</common>                <!-- ✅ Disabled -->
          </userVisible>
          <dataPath>List.Ref</dataPath>
          <useAlways>true</useAlways>
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

### Disable User Visibility

```
Form: CatalogListForm
└── Items
    └── List (DynamicList)
        └── Ref
            ├── Visible: False
            ├── UseAlways: True
            └── UserVisible: False    ✅ Disabled
```

### Clean UI

User cannot add Ref column because:
- It's not in the list of available columns
- "Configure list..." doesn't show Ref option

---

## 📋 Understanding UserVisible

### What UserVisible Controls

| UserVisible | User Can |
|-------------|----------|
| `True` | Add/remove column in list settings |
| `False` | Cannot see or configure this column |

### Visible vs UserVisible

| Setting | Effect |
|---------|--------|
| `Visible = False` | Column hidden by default |
| `UserVisible = False` | User cannot make it visible |

### Combined Settings

```
// Complete hiding:
Visible = False          // Not shown by default
UserVisible = False      // User cannot show it

// Hideable by user:
Visible = True           // Shown by default
UserVisible = True       // User can hide it

// For Ref field, use:
Visible = False          // Always hidden
UserVisible = False      // Cannot be shown
```

---

## 📋 Why Ref Should Be Hidden from Users

### Technical Data

```
Ref values look like:
├── e8f1a2b3-4c5d-6e7f-8a9b-0c1d2e3f4a5b
├── a1b2c3d4-e5f6-7890-abcd-ef1234567890
└── (meaningless to users)
```

### No Business Value

```
Users care about:
├── Product Name: "Office Chair"
├── Code: "CH-001"
├── Price: 299.99

Users don't care about:
└── Ref: e8f1a2b3-4c5d-6e7f-8a9b-0c1d2e3f4a5b
```

### Security Consideration

```
Internal identifiers can:
├── Reveal database structure
├── Be used in injection attempts
├── Confuse support communications
└── Clutter exports and reports
```

---

## 📋 Complete Ref Field Configuration

### Recommended Settings

```
Ref Field:
├── Visible = False              ✓ Hide from display
├── UserVisible = False          ✓ Prevent user access
├── UseAlways = True             ✓ Always load data
├── Title = ""                   ✓ No title needed
└── ToolTip = ""                 ✓ No tooltip needed
```

### Related Checks

| Check | Verifies |
|-------|----------|
| `form-list-field-ref-not-added-check` | Ref is added |
| `form-list-ref-use-always-flag-disabled-check` | UseAlways = True |
| `form-list-ref-user-visibility-enabled-check` | UserVisible = False |

---

## 🔧 How to Fix

### Step 1: Open form in Designer

Navigate to the form with the dynamic list.

### Step 2: Find Ref field

Locate the Ref field in the table.

### Step 3: Disable UserVisible

Set UserVisible = False in properties.

### Step 4: Verify

Check that Ref doesn't appear in "Configure list..." dialog.

---

## 📋 Other Fields to Consider

### Technical Fields to Hide

Similar treatment for other technical fields:

| Field | Visible | UserVisible | UseAlways |
|-------|---------|-------------|-----------|
| Ref | False | False | True |
| RowKey (for tabular) | False | False | True |
| InternalID | False | False | Depends |
| DataVersion | False | False | False |

### User-Configurable Fields

| Field | Visible | UserVisible |
|-------|---------|-------------|
| Code | True | True |
| Name | True | True |
| Date | True | True |
| Amount | True | True |
| Comment | False | True |

---

## 📋 Form Designer Steps

### Setting UserVisible = False

1. Open form in Designer
2. Find dynamic list table in Items tree
3. Expand table to see columns
4. Select Ref column
5. In Properties panel, find "User visible" or "UserVisible"
6. Set to "False" or uncheck

### Keyboard Shortcut

In EDT:
- F4 opens Properties view
- Navigate to UserVisible property

---

## 🔍 Technical Details

### What Is Checked

1. Ref field in dynamic list tables
2. UserVisible property
3. Value must be False

### Check Implementation Class

```
com.e1c.v8codestyle.form.check.FormListRefUserVisibilityEnabledCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.form/src/com/e1c/v8codestyle/form/check/
```

---

## 📚 References

- [Form List Field Ref Not Added Check](form-list-field-ref-not-added-check.md)
- [Form List Ref Use Always Flag Disabled Check](form-list-ref-use-always-flag-disabled-check.md)
