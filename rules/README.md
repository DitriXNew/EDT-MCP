# EDT-MCP Rules Pack

Образцовый комплект правил для AI-ассистента (Claude Code, Cursor, GitHub Copilot, Gemini CLI), помогающий эффективно работать с MCP-сервером EDT-MCP в проектах 1С:EDT.

Sample rule pack for AI assistants (Claude Code, Cursor, GitHub Copilot, Gemini CLI) for effective use of the EDT-MCP MCP server in 1C:EDT projects.

---

## Русский

### Что это и кому нужно

Комплект — это **шаблон**, который вы копируете в свой 1С-проект и адаптируете под себя. Он состоит из двух языковых веток (`ru/` и `en/`), каждая со своим навигатором `INDEX.md` и 8 тематическими файлами правил.

Когда AI-клиент читает корневой файл инструкций (`CLAUDE.md` для Claude, `.cursorrules` для Cursor и т. д.), он получает указание читать `rules/<язык>/INDEX.md`, а оттуда — нужные модули.

### Как подключить

1. Скопируйте папку `rules/` в свой 1С-проект. Рекомендуемые места:
   - в корень проекта (`<project>/rules/`),
   - либо в `<project>/.claude/rules/` (тогда поправьте пути в `INDEX.md`).
2. Возьмите `rules/CLAUDE.md.example`, переименуйте в нужное имя для своего клиента и положите в корень проекта (см. таблицу ниже).
3. Откройте `rules/ru/INDEX.md` (или `en/INDEX.md`) и удалите строки на файлы, которых нет в вашем процессе. По умолчанию опционален только `bsl-ssl.md` (если проект не использует БСП).
4. Допишите в свой корневой файл свои проектные правила: название конфигурации, версия БСП, договорённости команды.
5. Перезапустите AI-клиент или откройте новый чат — правила подхватятся.

### Подключение по клиентам

| Клиент | Куда класть содержимое `CLAUDE.md.example` |
|---|---|
| Claude Code | `CLAUDE.md` в корне проекта |
| Cursor IDE | `.cursorrules` (старый формат) или `.cursor/rules/index.md` (новый) |
| GitHub Copilot | `.github/copilot-instructions.md` |
| Gemini CLI | `GEMINI.md` в корне проекта |
| Cline / Continue / Antigravity | см. документацию клиента; обычно файл с инструкциями в корне проекта |

Содержание во всех файлах одинаковое — это просто markdown с одной директивой «всегда читай `rules/ru/INDEX.md`» и блоком для ваших проектных дополнений.

### Поддержка

Когда в плагине EDT-MCP появляется новый инструмент или меняется поведение существующего — этот комплект тоже обновляется. Если вы скопировали папку в свой проект, периодически сверяйтесь с актуальной версией в репозитории `DitriXNew/EDT-MCP`.

### Лицензия

Файлы наследуют лицензию репозитория EDT-MCP. См. файл `LICENSE` в корне.

---

## English

### What it is and who needs it

This pack is a **template** that you copy into your 1C project and tailor to your setup. It consists of two language branches (`ru/` and `en/`), each with its own `INDEX.md` navigator and 8 thematic rule files.

When an AI client reads the root instruction file (`CLAUDE.md` for Claude, `.cursorrules` for Cursor, etc.), it is told to read `rules/<lang>/INDEX.md`, which in turn points to the relevant modules.

### How to install

1. Copy the `rules/` folder into your 1C project. Recommended locations:
   - project root (`<project>/rules/`),
   - or `<project>/.claude/rules/` (then fix the paths in `INDEX.md`).
2. Take `rules/CLAUDE.md.example`, rename it for your client (see table below), and place it at the project root. **Important:** by default this template references the Russian `rules/ru/INDEX.md`. If you work in English, edit the line accordingly to point to `rules/en/INDEX.md` (the file itself contains a note about this).
3. Open `rules/en/INDEX.md` (or `ru/INDEX.md`) and remove lines referencing files you do not need. By default only `bsl-ssl.md` is optional (skip it if your project does not use SSL/БСП).
4. Add your project-specific rules into the root file: configuration name, SSL version, team conventions.
5. Restart the AI client or start a new chat — the rules will be picked up.

### Per-client installation

| Client | Where to put the content of `CLAUDE.md.example` |
|---|---|
| Claude Code | `CLAUDE.md` in the project root |
| Cursor IDE | `.cursorrules` (legacy) or `.cursor/rules/index.md` (new format) |
| GitHub Copilot | `.github/copilot-instructions.md` |
| Gemini CLI | `GEMINI.md` in the project root |
| Cline / Continue / Antigravity | see the client's documentation; usually a root instructions file |

The content is identical across clients — just markdown with a single directive ("always read `rules/en/INDEX.md`") and a slot for your project-specific rules.

### Maintenance

When EDT-MCP introduces new tools or changes existing behavior, this pack is updated as well. If you have copied the folder into your project, periodically sync with the upstream version in `DitriXNew/EDT-MCP`.

### License

Files inherit the license of the EDT-MCP repository. See `LICENSE` in the repository root.