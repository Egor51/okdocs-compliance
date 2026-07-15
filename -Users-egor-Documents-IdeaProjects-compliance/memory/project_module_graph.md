---
name: project-module-graph
description: Граф зависимостей модулей compliance-проекта — утверждённая схема
metadata:
  type: project
---

Утверждённый граф зависимостей (стрелки = "зависит от", сверху вниз):

```
compliance-app
    ├── compliance-api
    │       ├── compliance-contracts
    │       └── compliance-persistence
    │
    └── compliance-worker
            ├── compliance-contracts
            ├── compliance-persistence
            └── compliance-rules
                    └── compliance-contracts
```

**Why:** api и worker — параллельные ветки, не знают друг о друге. app — единственная точка сборки. contracts — единственный общий язык (Kafka events, DTO, enums).

**How to apply:** при добавлении зависимостей в pom.xml строго следить чтобы api не зависел от worker и наоборот. rules зависит только от contracts — никакого Spring JPA/Kafka внутри rules.
