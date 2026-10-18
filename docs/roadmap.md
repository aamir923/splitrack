# Roadmap (gave)

Capacity: about 1.5 h/day on weekdays + 4 h on weekends = ~15 h/week. Target: scoped, deployed MVP in ~8-10 weeks (10-12 if DSA/interview prep also takes weekday time).
Goal: resume-ready Java/Spring Boot microservices project with a live demo, README, architecture diagram, tests and CI.

| Weeks | Focus | Deliverable |
|---|---|---|
| 1 | Finish auth: refresh token, logout, CSRF, global error handler; invites | user-service complete |
| 2 | API gateway, Docker Compose, first deployment of the skeleton | Live URL (gateway + user-service) |
| 3 | group-service: groups, members, invitations | Groups API |
| 4-5 | expense-service: expenses, split types, rounding, validation, unit tests | Core logic tested |
| 6 | Balances and settle up | Correct balances, integration tests |
| 7-8 | Frontend: login, groups, add expense, balances, settle up | Usable UI |
| 9 | AI features (natural-language expense entry, receipt scan) with quotas | Two AI features live |
| 10 | Polish: README, architecture diagram, CI, final deployment, demo data | Resume-ready |

Scope cuts: four services (user, group, expense incl. balances/settlements, AI); no notification service, Kafka, Kubernetes or multi-currency conversion for now.
Deploy early and keep deploying every week. Backend depth (transactions, security, SQL, tests) over feature count.
Frontend: Angular recommended (already known; saves ~2 weeks); React optional for learning.

## Job-targeting notes (general pattern knowledge, verify per posting)
- **Product companies (e.g. Adobe, Samsung R&D):** DSA is the main filter (medium-level problems, OA + 2-3 coding rounds), plus CS fundamentals (OS, DBMS/SQL, networking, OOP), low-level design (SOLID, patterns), increasingly high-level design, and Java core depth (collections, concurrency, JVM, streams). The project helps get interviews; DSA passes them.
- **Gulf employers (banks, telecom, government programs, consulting/outsourcing, growing startups):** more practical and stack-focused: Spring Boot, REST, microservices, SQL, cloud basics, Docker/CI. A deployed project you can explain end to end matters a lot. Years of experience and visa feasibility are often hard filters; referrals, recruiters, and services/consulting firms work better than cold applications. Communication and notice period matter.
- **Both:** explain-aloud mastery of Java/Spring internals (docs folder = study notes), SQL, system design basics, a live project you can walk through, clear English.
- **Suggested weekly split (~15 h):** ~6-7 h gave, ~5-6 h DSA in Java, ~2 h spoken review of concepts. Project timeline then stretches to ~12-14 weeks.

## Target: banking / fintech (also e-commerce) in Dubai / Saudi
- Apply to both Java AND .NET roles: many Gulf banks run .NET too, and the user has 1.5 yrs of .NET 8; gave proves cross-stack ability.
- Build "banking-grade correctness" into expense/balance services:
  1. `Money` value object (`BigDecimal` + currency, fixed scale, explicit rounding; never `double`).
  2. Ledger-style balances (entries that sum to zero) with tests proving balances net to zero.
  3. Idempotency keys on write endpoints (add expense, settle up).
  4. Optimistic locking (`@Version`) on expenses/balances.
  5. Audit/history table; no silent deletes (soft delete).
  6. Atomic multi-row writes with `@Transactional`; explain isolation levels.
  7. Strong unit tests on split and balance logic; Testcontainers for DB integration tests.
  8. Observability: structured logs, global error handler, actuator health.
- E-commerce parallels: orders, inventory consistency, idempotent checkout, payment flows, event-driven design.
- Optional reading for interviews: basic payments concepts, KYC/AML terms, PCI/data protection awareness, open banking in the region. Certifications (AWS/Azure, Oracle Java) are optional extras.
