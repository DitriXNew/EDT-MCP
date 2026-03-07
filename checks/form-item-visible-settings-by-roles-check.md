# form-item-visible-settings-by-roles-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `form-item-visible-settings-by-roles-check` |
| **Title** | Use role-based settings for form item |
| **Description** | Checks for form items with visibility settings controlled by roles |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies form items that use **role-based visibility**, **editability**, or **usage** settings. While these settings work, they create maintenance issues and tight coupling with roles.

### Why This Is Important

- **Maintainability**: Role changes require form updates
- **Flexibility**: Hard to change access rules
- **Testing**: Role-based visibility is hard to test
- **Clarity**: Access logic scattered across forms

---

## ❌ Error Example

### Error Messages

```
Use role-based setting "Visible" for form item "ItemName"
Use role-based setting "Edit" for form item "ItemName"
Use role-based setting "Use" for form item "ItemName"
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Wrong: Using role-based visibility settings in form -->
<Form.Form xmlns="...">
  <items>
    <FormField>
      <name>Amount</name>
      <userVisible>
        <common>true</common>
        <roles>
          <role name="Accountant">true</role>    <!-- ❌ Role-based setting -->
          <role name="Manager">false</role>      <!-- ❌ Role-based setting -->
        </roles>
      </userVisible>
    </FormField>
    
    <FormField>
      <name>SecretField</name>
      <editMode>
        <common>true</common>
        <roles>
          <role name="Admin">true</role>         <!-- ❌ Role-based setting -->
          <role name="User">false</role>         <!-- ❌ Role-based setting -->
        </roles>
      </editMode>
    </FormField>
    
    <FormButton>
      <name>SpecialButton</name>
      <enabled>
        <common>false</common>
        <roles>
          <role name="SuperUser">true</role>     <!-- ❌ Role-based setting -->
        </roles>
      </enabled>
    </FormButton>
  </items>
</Form.Form>
```

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: No role-based settings in form XML, control in code -->
<Form.Form xmlns="...">
  <items>
    <FormField>
      <name>Amount</name>
      <userVisible>
        <common>true</common>                    <!-- ✅ No role-specific settings -->
      </userVisible>
    </FormField>
    
    <FormField>
      <name>SecretField</name>
      <editMode>
        <common>true</common>                    <!-- ✅ No role-specific settings -->
      </editMode>
    </FormField>
    
    <FormButton>
      <name>SpecialButton</name>
      <enabled>
        <common>true</common>                    <!-- ✅ No role-specific settings -->
      </enabled>
    </FormButton>
  </items>
</Form.Form>
```

### Use Functional Options

```bsl
// ✅ Control visibility through functional options
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // Check functional option instead of role
    If Not GetFunctionalOption("UseAdvancedFeatures") Then
        Items.SecretField.Visible = False;
    EndIf;
EndProcedure
```

### Use Access Rights Check

```bsl
// ✅ Check access rights in code
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // Check access right
    HasEditRight = AccessRight("Edit", Metadata.Documents.Order);
    Items.Amount.ReadOnly = Not HasEditRight;
    
    // Check role-based permission through access management
    CanViewSecret = AccessManagement.HasRight("ViewSecretData");
    Items.SecretField.Visible = CanViewSecret;
EndProcedure
```

### Use Common Module for Access Logic

```bsl
// ✅ Centralize access logic
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // Use common module
    SetFormItemsVisibility();
EndProcedure

&AtServer
Procedure SetFormItemsVisibility()
    // Centralized visibility control
    Items.Amount.Visible = AccessControl.CanViewAmounts();
    Items.SecretField.Visible = AccessControl.CanViewSecretData();
    Items.SpecialButton.Visible = AccessControl.CanUseSpecialFeatures();
EndProcedure
```

---

## 📋 Why Avoid Role-Based Form Settings

### Problems with Role-Based Settings

| Problem | Description |
|---------|-------------|
| Tight Coupling | Forms depend on specific role names |
| Maintenance | Role changes require form updates |
| Scalability | Many roles = complex configuration |
| Testing | Hard to test all role combinations |
| Clarity | Access logic scattered across forms |

### Benefits of Code-Based Approach

| Benefit | Description |
|---------|-------------|
| Centralized | Access logic in one place |
| Flexible | Easy to change conditions |
| Testable | Can unit test access logic |
| Clear | Explicit code is readable |
| Maintainable | One change affects all forms |

---

## 📋 Alternative Approaches

### Functional Options

```bsl
// Define functional option: "UseAdvancedPricing"
// Control it through constant or user settings

&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    If Not GetFunctionalOption("UseAdvancedPricing") Then
        Items.PricingGroup.Visible = False;
    EndIf;
EndProcedure
```

### Access Rights

```bsl
// ✅ Check metadata-level access rights
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // Check document access
    CanPost = AccessRight("Posting", Metadata.Documents.Order);
    Items.PostButton.Visible = CanPost;
    
    // Check attribute access
    CanViewCost = AccessRight("View", Metadata.Documents.Order.Attributes.Cost);
    Items.Cost.Visible = CanViewCost;
EndProcedure
```

### Access Management Subsystem

```bsl
// ✅ Use BSP Access Management (SSL)
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // Check access through subsystem
    Items.SecretData.Visible = AccessManagement.HasRight("ViewSecretData");
    Items.AdminButton.Visible = AccessManagement.IsFullUser();
EndProcedure
```

### IsInRole (If Necessary)

```bsl
// If role check is truly needed, do it in code
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // ✅ At least it's visible and centralized
    IsAdmin = IsInRole("Administrator");
    Items.AdminTools.Visible = IsAdmin;
EndProcedure
```

---

## 📋 Migration Steps

### Step 1: Identify Role-Based Settings

Find form items with role-based Visible/Edit/Use settings.

### Step 2: Create Access Functions

Create common module functions for access checks.

### Step 3: Remove Role-Based Settings

Clear role-based settings in form designer.

### Step 4: Add Code-Based Control

Set visibility/editability in OnCreateAtServer.

---

## 📋 Example Migration

### Before: Role-Based Setting

```
Form Designer:
└── Items.SalaryField
    └── Visible by Role:
        ├── HRManager: True
        └── Employee: False
```

### After: Code-Based Setting

```bsl
// Form module
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    Items.SalaryField.Visible = CanViewSalaryData();
EndProcedure

&AtServerNoContext
Function CanViewSalaryData()
    // Centralized check
    Return AccessManagement.HasRight("ViewSalaryData");
EndFunction
```

---

## 🔧 How to Fix

### Option 1: Clear Role-Based Settings

1. Open form in Designer
2. Find item with role-based settings
3. Clear the role-specific visibility/edit/use settings
4. Add visibility control code in OnCreateAtServer

### Option 2: Use Functional Options

1. Create functional option for the feature
2. Clear role-based settings
3. Check functional option in code

### Option 3: Use Access Rights

1. Define proper access rights in configuration
2. Clear role-based settings
3. Check access rights in code

---

## 🔍 Technical Details

### What Is Checked

1. Form item role-based Visible settings
2. Form item role-based Edit settings
3. Form item role-based Use settings

### Check Implementation Class

```
com.e1c.v8codestyle.form.check.FormItemVisibleSettingsByRolesCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.form/src/com/e1c/v8codestyle/form/check/
```

---

## 📚 References

- [Is In Role Check](is-in-role-check.md)
