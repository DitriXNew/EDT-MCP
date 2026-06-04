# Реестр планов (plans_ai)

Сопоставление: GitHub issue ↔ файл плана ↔ обзор для человека ↔ статус.

## Соглашения

- Имя файла плана включает номер issue: `<issue>-<краткое-имя>.md` (например `129-form-tooling.md`).
- В начале файла плана — frontmatter с полями `issue`, `issue_url`, `title`, `overview`, `todos`, и опционально `status`.
- Краткий обзор для человека — в поле `overview` frontmatter плана или в описании GitHub issue.
- Статусы: `planned` → `in-progress` → `done` (или `on-hold` / `dropped`).

## Планы

| Issue | План | Статус |
|---|---|---|
| [#129](https://github.com/DitriXNew/EDT-MCP/issues/129) — Генерация форм и редактирование формы (и её элементов) | [129-form-tooling.md](129-form-tooling.md) | planned |
