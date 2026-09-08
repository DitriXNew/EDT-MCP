# md-object-name-unallowed-letter-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `md-object-name-unallowed-letter-check` |
| **Title** | In Russian locale name, synonym or comment of metadata object contain the unallowed letter |
| **Description** | Checks for unallowed letters in Russian locale metadata names |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `TRIVIAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies **metadata object names, synonyms, and comments** in Russian locale that contain **unallowed letters**. These are typically Latin letters that look similar to Cyrillic letters or other characters that shouldn't be used.

### Why This Is Important

- **Consistency**: All text should use correct alphabet
- **Searchability**: Mixed alphabets break search
- **Localization**: Proper locale-specific characters
- **Avoid confusion**: Similar-looking letters cause bugs

---

## ❌ Error Example

### Error Message

```
In Russian locale, name, synonym or comment of metadata object contain the unallowed letter
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Wrong: Latin 'o' instead of Cyrillic 'о' in synonym -->
<mdclass:Catalog uuid="..." name="Products">
  <synonym>
    <key>ru</key>
    <value>Тoвары</value>              <!-- ❌ Latin 'o' (U+006F) instead of Cyrillic 'о' (U+043E) -->
  </synonym>
</mdclass:Catalog>

<!-- ❌ Wrong: Latin 'a' instead of Cyrillic 'а' in comment -->
<mdclass:Catalog uuid="..." name="Settings">
  <comment>Нaстройки системы</comment>  <!-- ❌ Latin 'a' (U+0061) instead of Cyrillic 'а' (U+0430) -->
</mdclass:Catalog>

<!-- ❌ Wrong: Mixed alphabet in name -->
<mdclass:Catalog uuid="..." name="Продуктy">  <!-- ❌ Latin 'y' at the end -->
</mdclass:Catalog>
```

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: All Cyrillic letters in Russian synonym -->
<mdclass:Catalog uuid="..." name="Products">
  <synonym>
    <key>ru</key>
    <value>Товары</value>              <!-- ✅ All Cyrillic letters -->
  </synonym>
</mdclass:Catalog>

<!-- ✅ Correct: Proper Cyrillic in comment -->
<mdclass:Catalog uuid="..." name="Settings">
  <comment>Настройки системы</comment> <!-- ✅ Correct Cyrillic 'а' -->
</mdclass:Catalog>

<!-- ✅ Correct: Pure Cyrillic name -->
<mdclass:Catalog uuid="..." name="Продукты">  <!-- ✅ Correct Cyrillic 'ы' -->
</mdclass:Catalog>
```

### Use Correct Cyrillic Letters

```
// Correct Cyrillic spelling
Catalog: Продукты                    ✅ Correct Cyrillic
Catalog: Товары                      ✅ Correct Cyrillic
Catalog: Категории                   ✅ Correct Cyrillic

// Correct synonyms
Catalog: Products
├── Synonym: Товары                  ✅ Correct

// Correct comments
Catalog: Settings
├── Comment: "Настройки системы"     ✅ Correct
```

---

## 📋 Common Confused Letters

### Cyrillic vs Latin Look-Alikes

| Cyrillic | Latin | Unicode |
|----------|-------|---------|
| А (а) | A (a) | 0410 vs 0041 |
| В (в) | B (b) | 0412 vs 0042 |
| Е (е) | E (e) | 0415 vs 0045 |
| К (к) | K (k) | 041A vs 004B |
| М (м) | M (m) | 041C vs 004D |
| Н (н) | H (h) | 041D vs 0048 |
| О (о) | O (o) | 041E vs 004F |
| Р (р) | P (p) | 0420 vs 0050 |
| С (с) | C (c) | 0421 vs 0043 |
| Т (т) | T (t) | 0422 vs 0054 |
| У (у) | Y (y) | 0423 vs 0059 |
| Х (х) | X (x) | 0425 vs 0058 |

---

## 📋 How Confusion Happens

### Copy-Paste Issues

```
// Text copied from different sources may have wrong encoding
Web page (UTF-8) → Editor (different encoding) → Wrong characters
```

### Keyboard Layout Switch

```
// Typing in wrong layout:
User types "Продукт" but keyboard was in English layout
Result: "Ghjlerbn" (wrong) or mixed letters
```

### OCR/Scanning

```
// OCR often confuses similar letters:
Scanned document → OCR → "Тoвары" (mixed 'o')
```

---

## 📋 Detection Methods

### Visual Inspection

```
// Hard to see visually:
Товары  ← Correct?
Тoвары  ← With Latin 'o'?

// They look identical!
```

### Character Code Check

```bsl
// Check character codes:
For Index = 1 To StrLen(String) Do
    Char = Mid(String, Index, 1);
    Code = CharCode(Char);
    // Cyrillic: 1024-1279 (0x0400-0x04FF)
    // Latin: 65-122 (0x0041-0x007A)
EndDo;
```

---

## 🔧 How to Fix

### Step 1: Identify wrong characters

Use IDE tools or character analysis.

### Step 2: Retype the text

Delete and retype using correct keyboard layout.

### Step 3: Verify character codes

Check that all characters are from correct alphabet.

---

## 📋 IDE Detection

### EDT/Designer Features

```
// EDT may highlight mixed alphabets
// Use "Find and Replace" with regex:

// Find Latin in Cyrillic text:
[A-Za-z] within Cyrillic context

// Find Cyrillic in Latin text:
[А-Яа-яЁё] within Latin context
```

### Character Analysis

```bsl
// Function to check string alphabet:
Function ContainsLatinInCyrillic(Text)
    For Index = 1 To StrLen(Text) Do
        Code = CharCode(Mid(Text, Index, 1));
        If Code >= 65 And Code <= 122 Then // Latin
            // Check if surrounded by Cyrillic
            If HasCyrillicNeighbors(Text, Index) Then
                Return True;
            EndIf;
        EndIf;
    EndDo;
    Return False;
EndFunction
```

---

## 📋 Prevention

### Best Practices

```
1. Use consistent keyboard layout
2. Don't copy-paste from unknown sources
3. Check character codes for suspicious text
4. Use IDE spell-checking
5. Review before commit
```

### Keyboard Layout Indicator

```
// Use OS keyboard layout indicator
// Verify layout before typing names
```

---

## 📋 Affected Properties

### What Is Checked

| Property | Checked |
|----------|---------|
| Object Name | Yes |
| Object Synonym | Yes |
| Object Comment | Yes |
| Attribute Names | Yes |
| Attribute Synonyms | Yes |

---

## 📋 Special Cases

### Intentional Latin in Russian Context

```
// Some terms are written in Latin (trademarks):
Synonym: "Товар iPhone"        ← Latin "iPhone" is OK
Synonym: "Продукт Microsoft"   ← Latin "Microsoft" is OK

// But not mixed within words:
❌ Тoвар (mixed letters)
✅ Товар (pure Cyrillic)
```

### Technical Names

```
// Object names (identifiers) often use Latin:
Catalog: Products              ← Latin OK for identifiers
├── Synonym: Товары            ← Cyrillic for display

// Don't mix within one string
```

---

## 🔍 Technical Details

### What Is Checked

1. Russian locale configurations
2. Metadata object names
3. Synonyms in Russian
4. Comments in Russian
5. Character code analysis

### Check Implementation Class

```
com.e1c.v8codestyle.md.check.MdObjectNameUnallowedLetterCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/check/
```

---

## 📚 References

- [Unicode Cyrillic Block](https://en.wikipedia.org/wiki/Cyrillic_script_in_Unicode)
- [1C Naming Conventions](https://its.1c.ru/db/v8std/content/485/hdoc)
- [Homoglyph Characters](https://en.wikipedia.org/wiki/Homoglyph)
