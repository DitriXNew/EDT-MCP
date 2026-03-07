# input-field-list-choice-mode-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `input-field-list-choice-mode-check` |
| **Title** | Form input field list choice mode |
| **Description** | Checks that input field has correct list choice mode when choice list is not empty |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `TRIVIAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies **input fields** that have a filled **ChoiceList** but the **ListChoiceMode** property is not properly set. When a choice list is defined, the field should be configured to use it.

### Why This Is Important

- **User experience**: User sees choice list correctly
- **Data consistency**: User selects from predefined values
- **UI behavior**: Dropdown works as expected
- **Configuration completeness**: All related settings aligned

---

## ❌ Error Example

### Error Message

```
Form input field the "list choice mode" not set with filled choice list
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Wrong: ChoiceList filled but ListChoiceMode not set to true -->
<Form.Form xmlns:xsi="..." xmlns="...">
  <items>
    <FormField>
      <name>StatusField</name>
      <type>InputField</type>
      <choiceList>
        <item>
          <presentation><key>en</key><value>New</value></presentation>
          <value xsi:type="core:StringValue"><value>New</value></value>
        </item>
        <item>
          <presentation><key>en</key><value>In Progress</value></presentation>
          <value xsi:type="core:StringValue"><value>InProgress</value></value>
        </item>
        <item>
          <presentation><key>en</key><value>Completed</value></presentation>
          <value xsi:type="core:StringValue"><value>Completed</value></value>
        </item>
      </choiceList>
      <listChoiceMode>false</listChoiceMode>     <!-- ❌ Should be true -->
    </FormField>
  </items>
</Form.Form>
```

### UI Problem

When ListChoiceMode = False:
- User may type any value
- Choice list might not appear
- Dropdown button may not show
- User can enter invalid values

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: ListChoiceMode enabled when ChoiceList is filled -->
<Form.Form xmlns:xsi="..." xmlns="...">
  <items>
    <FormField>
      <name>StatusField</name>
      <type>InputField</type>
      <choiceList>
        <item>
          <presentation><key>en</key><value>New</value></presentation>
          <value xsi:type="core:StringValue"><value>New</value></value>
        </item>
        <item>
          <presentation><key>en</key><value>In Progress</value></presentation>
          <value xsi:type="core:StringValue"><value>InProgress</value></value>
        </item>
        <item>
          <presentation><key>en</key><value>Completed</value></presentation>
          <value xsi:type="core:StringValue"><value>Completed</value></value>
        </item>
      </choiceList>
      <listChoiceMode>true</listChoiceMode>      <!-- ✅ Enabled -->
    </FormField>
  </items>
</Form.Form>
```

### Enable ListChoiceMode

```
Form: DocumentForm
└── Items
    └── StatusField (InputField)
        ├── ChoiceList:
        │   ├── "New"
        │   ├── "InProgress"
        │   └── "Completed"
        │
        └── ListChoiceMode: True      ✅ Enabled
```

### Correct UI Behavior

When ListChoiceMode = True:
- Dropdown button appears
- User clicks to see list
- Only listed values can be selected
- No free text input allowed

---

## 📋 Understanding ListChoiceMode

### What ListChoiceMode Controls

| ListChoiceMode | Behavior |
|----------------|----------|
| `True` | Input restricted to choice list values |
| `False` | Free text input allowed |

### When to Use ListChoiceMode

| Scenario | ListChoiceMode |
|----------|----------------|
| Fixed set of values | True |
| Suggestions with free input | False |
| Dropdown selection | True |
| Autocomplete | Depends |

---

## 📋 Choice List Types

### Static Choice List

```
InputField.ChoiceList:
├── Value: "Active", Presentation: "Active"
├── Value: "Inactive", Presentation: "Inactive"
└── Value: "Pending", Presentation: "Pending"
```

### With Presentations

```
InputField.ChoiceList:
├── Value: 1, Presentation: "Low Priority"
├── Value: 2, Presentation: "Normal Priority"
└── Value: 3, Presentation: "High Priority"
```

---

## 📋 Complete Field Configuration

### Recommended Settings

```
InputField for Choice List:
├── ChoiceList: (filled with values)
├── ListChoiceMode: True              ✓ Enable
├── DropListButton: True              ✓ Show dropdown
├── ChoiceButton: False               ✓ Usually not needed
├── EditTextUpdate: (optional)
└── OpenButton: False                 ✓ Not needed for list
```

### Visual Elements

```
┌─────────────────────┬──┐
│ Selected Value      │▼ │  ← Dropdown button
└─────────────────────┴──┘
         │
         ▼ (click)
┌─────────────────────────┐
│ ○ New                   │
│ ● InProgress (selected) │
│ ○ Completed             │
└─────────────────────────┘
```

---

## 📋 Code Examples

### Setting Up in Code

```bsl
// ✅ Configure choice list in code with correct mode
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // Fill choice list
    Items.Priority.ChoiceList.Add(1, "Low");
    Items.Priority.ChoiceList.Add(2, "Normal");
    Items.Priority.ChoiceList.Add(3, "High");
    
    // Enable list choice mode
    Items.Priority.ListChoiceMode = True;
EndProcedure
```

### Dynamic Choice List

```bsl
// ✅ Dynamic list with mode enabled
&AtServer
Procedure FillStatusChoiceList()
    Items.Status.ChoiceList.Clear();
    
    For Each Status In GetAvailableStatuses() Do
        Items.Status.ChoiceList.Add(Status.Value, Status.Name);
    EndDo;
    
    // Important: enable list mode
    Items.Status.ListChoiceMode = True;
EndProcedure
```

---

## 📋 Related Properties

### DropListButton

```
// Show dropdown button for choice list
Items.Field.DropListButton = True;
```

### ChoiceButton

```
// Show "..." button for complex selection (usually not needed with choice list)
Items.Field.ChoiceButton = False;
```

### QuickChoice

```
// Enable quick choice for small lists
Items.Field.QuickChoice = True;
```

---

## 🔧 How to Fix

### Step 1: Open form in Designer

Navigate to the form with the input field.

### Step 2: Find field with choice list

Locate input field with ChoiceList filled.

### Step 3: Enable ListChoiceMode

Set ListChoiceMode = True.

### Step 4: Verify dropdown button

Ensure DropListButton = True for visual indication.

---

## 📋 Alternative Approaches

### Using Enumeration Instead

If values are fixed, consider using Enumeration:

```
// Instead of ChoiceList:
Metadata:
└── Enumerations
    └── OrderStatuses
        ├── New
        ├── InProgress
        └── Completed

// Form attribute uses enumeration type
// No need for ChoiceList - dropdown automatic
```

### Using Reference Selection

For dynamic values, consider catalog:

```
// Instead of ChoiceList:
Metadata:
└── Catalogs
    └── Statuses
        ├── New
        ├── InProgress
        └── Completed

// Form attribute is CatalogRef.Statuses
// Selection form handles choices
```

---

## 📋 When ChoiceList Is Appropriate

| Use Case | Approach |
|----------|----------|
| Fixed values, few options | ChoiceList ✓ |
| Fixed values, many options | Enumeration |
| Dynamic values | Catalog |
| Context-dependent | ChoiceList in code |
| Simple number/string choice | ChoiceList ✓ |

---

## 🔍 Technical Details

### What Is Checked

1. Input field with ChoiceList property
2. ChoiceList has values
3. ListChoiceMode property value

### Check Implementation Class

```
com.e1c.v8codestyle.form.check.InputFieldListChoiceMode
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.form/src/com/e1c/v8codestyle/form/check/
```

