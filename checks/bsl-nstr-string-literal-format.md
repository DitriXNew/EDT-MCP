# bsl-nstr-string-literal-format

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `bsl-nstr-string-literal-format` |
| **Title** | NStr string literal format |
| **Description** | NStr string literal format validation |
| **Severity** | `MINOR` |
| **Type** | `CODE_STYLE` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check validates the format of string literals passed to the `NStr()` function (or `НСтр()` in Russian). The `NStr` function is used for localization/internationalization of user interface strings.

### What Is Validated

1. **First parameter must be a string literal** (not a variable or expression)
2. **String content cannot be empty**
3. **Format must be correct** (parseable key-value format: `"langCode = 'message'"`)
4. **Language codes must be valid** (known in the project)
5. **Messages cannot be empty** for each language code
6. **Messages cannot end with whitespace** (space or line break)
7. **Optionally**: each project language must have a translation

### Why This Is Important

- **Localization quality**: Ensures proper internationalization of the application
- **Runtime errors prevention**: Incorrect format may cause runtime errors
- **User experience**: Empty or malformed messages confuse users
- **Code quality**: Trailing spaces in messages are often unintentional mistakes

---

## ❌ Error Examples

### Error 1: First parameter is not a string

```
NStr method should accept string as first parameter
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    MessageTemplate = "en = 'Hello'";
    Message = NStr(MessageTemplate);  // ❌ Variable, not string literal
    
EndProcedure
```

### Error 2: Empty NStr message

```
NStr message is empty
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    Message = NStr("");  // ❌ Empty string
    
EndProcedure
```

### Error 3: Incorrect format

```
NStr format is incorrect {error details}
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    // ❌ Missing quotes around message
    Message = NStr("en = User message");
    
EndProcedure
```

### Error 4: Unknown language code

```
NStr contains unknown language code {code}
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    // ❌ "en2" is not a valid language code
    Message = NStr("en2 = 'User message'");
    
EndProcedure
```

### Error 5: Empty message for language

```
NStr message for language code {code} is empty
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    // ❌ Empty message (only quotes)
    Message = NStr("en = ''");
    
EndProcedure
```

### Error 6: Message ends with space

```
NStr message for code {code} ends with space
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    // ❌ Trailing space after "message "
    Message = NStr("en = 'User message '");
    
EndProcedure
```

### Error 7: Message ends with line break

```
NStr message for code {code} ends with space
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    // ❌ Message ends with newline
    Message = NStr("en = 'User message
    |'");
    
EndProcedure
```

### Error 8: String concatenation

```
NStr method should accept string as first parameter
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    // ❌ String concatenation is not allowed
    Message = NStr("en = 'User message'" + Chars.LF);
    
EndProcedure
```

---

## ✅ Compliant Solution

### Correct NStr Format

```bsl
// ✅ Simple single-language message
Message = NStr("en = 'User message'");

// ✅ Multi-language message
Message = NStr("en = 'User message'; ru = 'Сообщение пользователю'");

// ✅ Multi-line format for better readability
Message = NStr("en = 'The operation completed successfully.
              |Please proceed to the next step.';
              |ru = 'Операция успешно завершена.
              |Пожалуйста, перейдите к следующему шагу.'");

// ✅ Message with parameters (use StringWithParameters after NStr)
MessageTemplate = NStr("en = 'Document %1 has been saved'");
Message = StringFunctionsClientServer.SubstituteParametersToString(
    MessageTemplate, DocumentNumber);
```

### Complete Example

```bsl
Procedure ShowUserMessage()
    
    // ✅ Correct: string literal, proper format, no trailing spaces
    SuccessMessage = NStr("en = 'Operation completed successfully'");
    
    // ✅ Correct: multi-language support
    ErrorMessage = NStr("en = 'An error occurred. Please try again.';
                        |ru = 'Произошла ошибка. Попробуйте еще раз.'");
    
    // ✅ Correct: with second parameter for specific language
    GermanMessage = NStr("en = 'Hello'; de = 'Hallo'", "de");
    
    ShowMessageBox(, SuccessMessage);
    
EndProcedure
```

---

## 📖 NStr Format Reference

### Basic Syntax

```
"languageCode = 'message'"
```

### Multiple Languages

```
"langCode1 = 'message1'; langCode2 = 'message2'"
```

### Common Language Codes

| Code | Language |
|------|----------|
| `en` | English |
| `ru` | Russian |
| `de` | German |
| `fr` | French |
| `es` | Spanish |
| `it` | Italian |
| `pl` | Polish |
| `uk` | Ukrainian |
| `tr` | Turkish |
| `zh` | Chinese |

### Multi-line Messages

Use the `|` (pipe) character at the beginning of continuation lines:

```bsl
Message = NStr("en = 'First line
              |Second line
              |Third line'");
```

---

## 🔧 How to Fix

### Step 1: Identify the error type

Read the error message to understand what's wrong:
- Empty message → Add content
- Unknown language code → Use correct code (en, ru, etc.)
- Format error → Check quotes and syntax
- Trailing space → Remove spaces at the end

### Step 2: Fix the format

**Empty message:**
```bsl
// Before
Message = NStr("");
// After
Message = NStr("en = 'Your message here'");
```

**Unknown language code:**
```bsl
// Before
Message = NStr("en2 = 'Message'");
// After
Message = NStr("en = 'Message'");
```

**Missing quotes:**
```bsl
// Before
Message = NStr("en = Message");
// After
Message = NStr("en = 'Message'");
```

**Trailing space:**
```bsl
// Before
Message = NStr("en = 'Message '");
// After
Message = NStr("en = 'Message'");
```

**String concatenation:**
```bsl
// Before (incorrect)
Message = NStr("en = 'Message'" + SomeVariable);
// After (correct - use substitution)
MessageTemplate = NStr("en = 'Message %1'");
Message = StringFunctionsClientServer.SubstituteParametersToString(
    MessageTemplate, SomeVariable);
```

---

## ⚙️ Check Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `checkEmptyInterface` | Boolean | `false` | If enabled, checks that each language defined in the project has a translation in NStr |

---

## 📁 File Structure

This check applies to:

| File Type | Description |
|-----------|-------------|
| `*.bsl` | Any BSL module file |
| Common modules | Shared business logic |
| Object modules | Catalog, Document modules |
| Manager modules | Manager modules |
| Form modules | Form module code |
| Command modules | Command modules |

---

## 🔍 Technical Details

### How the Check Works

1. Finds all `Invocation` nodes where method name is `NStr` or `НСтр`
2. Validates the first parameter is a `StringLiteral`
3. Joins multi-line strings and parses as key-value format
4. Validates each language code against project languages
5. Checks message content for each language code
6. Reports issues for empty messages or trailing whitespace

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.NstrStringLiteralFormatCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/NstrStringLiteralFormatCheck.java
```

---

## 📚 References

- [1C:Enterprise Development Standards - Localization](https://its.1c.ru/db/v8std)
