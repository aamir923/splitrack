# Java and OOD Principles (gave)

(Entries get added as we use each concept in code.)

## 1. Maven multi-module project
A parent POM (`packaging=pom`) lists child `<modules>` and pins dependency versions in one place. Each child is its own buildable app. Children inherit from the parent.

## 2. Separation of concerns / layered architecture
Controller (HTTP) -> Service (business logic) -> Repository (database). Each layer has one job and only talks to the layer below.

## 3. Single Responsibility Principle (SRP)
A class should have one reason to change. Example: identity linking logic should not live in the controller.

## 4. DTOs vs. entities
Entities map to DB tables; DTOs are what the API sends and receives. Keeping them separate stops internal schema changes from breaking the API, and avoids leaking fields (for example `deleted_at`).

## 5. Package naming
Reverse-domain convention: `io.gave.<service>`. Packages group code by feature.

## 6. Naming conventions
- Classes (controllers, services, entities): PascalCase, e.g. `UserController`.
- Methods and variables: camelCase, e.g. `findByEmail()`.
- Constants: UPPER_SNAKE_CASE. Packages: lowercase (`io.gave.user`).
- Names should say what a thing is or does; avoid abbreviations.

## 7. JPA entities
- `@Entity` + `@Table(name=...)` map a class to a table; `@Column` maps fields (name, nullable, length). `ddl-auto: validate` checks entities against the Flyway schema at startup.
- IDs: `@Id @GeneratedValue @UuidGenerator(style = VERSION_7)`, app-generated, time-ordered UUIDs.
- `@Enumerated(EnumType.STRING)`, never ORDINAL (reordering the enum would corrupt data).
- `Instant` for `TIMESTAMPTZ`. `@CreationTimestamp` / `@UpdateTimestamp` fill timestamps; `updatable = false` protects `created_at`.
- `@JdbcTypeCode(SqlTypes.CHAR)` for `CHAR(n)` columns so validation matches.
- Relationships: `@ManyToOne` is the owning side (holds the foreign key; `fetch = LAZY`). `@OneToMany(mappedBy=...)` is the inverse side. Keep both sides in sync with a helper (`addIdentity`).
- `cascade = ALL` + `orphanRemoval = true`: children are saved/deleted with the parent.
- JPA needs a no-arg constructor; make it `protected` and expose a public constructor with the required fields.
- Encapsulation: no setters for immutable fields (id, email, createdAt); package-private setters for internal wiring.

## 8. Where logic lives
Business rules live in the Java service layer (testable, versioned with the code). The database holds data and constraints.

## 9. Enums and package structure
- Store enums as strings (`@Enumerated(EnumType.STRING)` + VARCHAR + CHECK), never ORDINAL: readable data, safe to reorder/insert values. Numbers only save a few bytes; a lookup table is only for statuses that carry extra data.
- Package by feature, not by type: an enum used by one entity lives beside it in `domain`, not in an `enum` package.
- Layout within a service: `io.gave.<service>.{domain, repository, service, web}`.

## 10. Spring Data repositories
- A repository is an interface; Spring generates the implementation at startup. `JpaRepository<Entity, IdType>` gives save/find/delete/paging for free.
- Derived queries: the method name is parsed into a query (`findByProviderAndProviderSubject`). Names use Java field names, not column names; typos fail at startup.
- Return `Optional<T>` for lookups that may find nothing; callers must handle absence.
- `@EntityGraph(attributePaths = ...)` loads a LAZY association in the same query, avoiding `LazyInitializationException` (we set `open-in-view: false`) and N+1 queries. Keep LAZY as the default; opt in per query.
- Repository layer = data access only, no business rules. Layers: Controller -> Service -> Repository.

## 11. One-to-many mapping in detail
```java
@OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
private List<UserIdentity> identities = new ArrayList<>();
```
- **Two worlds:** in the DB only the child table has the foreign key (`user_identities.user_id`). In Java both objects can point at each other, so JPA needs to know which field is the real one.
- **Owning side:** the field with `@ManyToOne` + `@JoinColumn` in the child. Whoever has the FK column owns the relationship, and only the owning side is written to the DB.
- **Inverse side:** the `@OneToMany` list. `mappedBy = "user"` means "the relationship is already stored by the child's `user` field; create no column or join table." Omitting `mappedBy` makes JPA create an extra join table.
- **Keep both sides in sync** with a helper (`addIdentity` sets the list and `identity.setUser(this)`). Adding to the list alone writes nothing.
- **`cascade = ALL`:** save/merge/remove on the parent applies to children (`save(user)` also inserts its identities).
- **`orphanRemoval = true`:** removing a child from the parent's list deletes its row. Right when the child cannot exist without the parent.
- **`= new ArrayList<>()`:** a new entity built in code would otherwise have a null list. Declare the type as `List` (interface), not `ArrayList`.
- **Pitfalls:** the list is LAZY (reading it outside a transaction throws `LazyInitializationException`); exposing the raw list lets callers mutate it (consider returning an unmodifiable view and forcing changes through `addIdentity`).

## 12. Persistence testing with @DataJpaTest
- `@DataJpaTest` loads only the JPA slice (entities, repositories, Hibernate); each test runs in a transaction that is rolled back afterwards, so no data is left behind.
- By default it swaps in an in-memory H2 database. Use `@AutoConfigureTestDatabase(replace = NONE)` to test against real PostgreSQL (H2 differs on UUID, CHAR, TIMESTAMPTZ, constraints).
- Hibernate delays writes until a flush/commit. `saveAndFlush` forces the SQL to run inside the test; without a flush a rolled-back test sends no SQL.
- `spring.jpa.show-sql=true` prints SQL. Statements use bind parameters (`?`), which prevents SQL injection.
- In Spring Boot 4 the JPA test support is a separate dependency: `spring-boot-starter-data-jpa-test` (test scope).
- Observed: `addIdentity` runs no SQL; on flush Hibernate inserts the parent `users` row, then the child `user_identities` row (with `user_id` from `identity.setUser(this)`).
- Better isolation: Testcontainers (throwaway Postgres container) so tests never use the dev DB.

## 13. Owning side rules
- Each relationship has exactly one owning side: the side with the foreign key column. The other side declares `mappedBy` (inverse).
- No `mappedBy` on either side of a bidirectional pair = two independent relationships: an unwanted join table is created, and the two sides can disagree. `mappedBy` on both sides fails at startup.
- One entity may own many relationships (e.g. `Expense` with `@ManyToOne` group and payer); each is independent, and `mappedBy` names the specific field.
- `@ManyToMany`: either side may own the join table (`@JoinTable`); the other uses `mappedBy`. A link that needs extra data (role, joined_at) should be its own entity (e.g. `GroupMember`) with two `@ManyToOne`s, not `@ManyToMany`.
- Across microservices there are no JPA relationships: store the other service's id (a plain UUID), since a foreign key cannot cross databases.

## 14. Many tables referencing one entity (aggregates)
- Any number of child tables can have a foreign key to `users`; each child owns its own `@ManyToOne`.
- The inverse `@OneToMany` list on the parent is optional. Add it only for children that are part of the parent (created/loaded/saved with it, e.g. `identities`).
- Aggregate idea (DDD): a cluster treated as one unit with a root (`User` + `UserIdentity`). Other things reference the root by id, not by holding object lists. Avoid a god-object `User` with devices, preferences, expenses, groups...
- Load related data from the other side with a repository query (`findByUserId`), explicit and only when needed.
- Across services: store the id as a plain UUID, no FK, no `@ManyToOne`. Integrity becomes application-level (validate via API, or keep a local copy updated by events).

## 15. Rich vs. anemic domain model
- Anemic: entities are only getters/setters; all logic sits in services, so invariants can be bypassed.
- Rich: behavior lives in the entity next to the data it protects. `addIdentity` keeps both sides in sync; later `markDeleted()` will anonymize and set the status. Services orchestrate; entities enforce their own rules.
- Value objects (`Money`, `Email`): immutable, validated at construction, so invalid values cannot exist.

## 16. Service layer: UserProvisioningService
- **Layering:** Controller -> Service -> Repository. The service holds business flow; it takes a plain `ProviderProfile` (a Java `record`), so it does not depend on Spring Security or OAuth2 classes. Any provider can feed it.
- **`record`:** compact immutable data carrier; auto constructor, accessors (`profile.email()`), equals/hashCode/toString.
- **Constructor injection:** dependencies are `final` fields set via the constructor; Spring injects them (single constructor needs no `@Autowired`). Explicit, testable with plain `new`, never half-initialized.
- **`@Service`:** registers a Spring bean and marks its role.
- **`@Transactional`:** the whole method is one DB transaction (all-or-nothing, rolls back on RuntimeException) and keeps the Hibernate session open so lazy loading works inside it.
- **`Optional` chaining:** `.map(...)` transforms a present value, `.orElseGet(supplier)` runs the fallback only when empty (`orElse(x)` always evaluates x).
- **Fail fast:** reject bad input (unverified email) before doing any work.
- **Small private methods**, each with one job; the public method reads like the business flow.
- **Custom unchecked exceptions** (`extends RuntimeException`) per business failure; a global `@RestControllerAdvice` will map them to HTTP responses later.
- **Logging:** SLF4J (`LoggerFactory.getLogger`), `{}` placeholders, log ids not personal data.
- **Behavior on entities:** `User.activate(...)` and `User.isDeleted()` keep rules next to the data.
- **Known gap:** two simultaneous first logins can hit the unique constraint at commit (`DataIntegrityViolationException`); the DB is the safety net, handle with retry at the web layer.

## 17. Record vs. DTO
- `record` = a Java language feature (how a class is written). DTO = a design role (what the class is for: carrying data between layers, no business logic). Records are a convenient way to write DTOs.
- `ProviderProfile` is an internal input DTO (command object): it moves data from the security layer into the service so the service does not depend on OAuth2 classes. It is not an entity (no `@Entity`, no id, never persisted).
- API DTOs (`UserResponse`, request bodies) cross the HTTP boundary and will also be records: built once, never edited.
- Entities cannot be records: JPA needs a no-arg constructor and mutable fields.

## 18. What belongs in which package
- `domain`: business concepts true regardless of how the app is used (entities, their enums, value objects, rules protecting them).
- `service`: use cases and their input contracts (`UserProvisioningService`, `ProviderProfile`). `ProviderProfile` reflects what the login flow needs from a token, so it changes for different reasons than `User` does (Single Responsibility applied to packages).
- `repository`: data access. `web`: controllers and API request/response DTOs. `exception`: business exceptions.
- Dependency direction points inward: `web -> service -> domain`, `service -> repository -> domain`. `domain` never imports `service` or `web`.
- Keeping use-case-specific DTOs out of `domain` stops it becoming a dumping ground. Layouts differ between teams (`dto`, `model`, hexagonal `application/port`); consistency and inward dependencies matter most.

## 19. Why repositories are interfaces
- You declare what queries you need; Spring Data generates the implementation (a runtime proxy) at startup. Inherited methods (`save`, `findById`) run on `SimpleJpaRepository`; declared methods (`findByEmail`) are parsed from the method name into queries. The proxy is the bean injected into services.
- Writing the class by hand would mean `EntityManager`, JPQL, parameter binding and result mapping per method: boilerplate and bug-prone.
- Interface = a contract without implementation. Dependency Inversion: services depend on the abstraction (`UserRepository`), not on JPA, so they can be unit-tested with a mock/fake and persistence can be swapped.
- Escape hatches when a method name is not enough: `@Query` (JPQL/native) on the interface method, or a custom fragment interface with an `Impl` class merged into the repository.

## 20. How a repository finds its table
- `JpaRepository<User, UUID>`: Spring Data reads this generic declaration by reflection (type arguments in a declaration survive erasure) to learn the entity class and id type.
- `@Entity` + `@Table(name = "users")` + `@Column` give Hibernate a metamodel (class -> table, field -> column), built at startup from the entity scan.
- Derived queries become JPQL against the entity (`select u from User u where u.email = ?1`); Hibernate translates it to SQL using the metamodel.
- Division of labor: Spring Data pairs repository <-> entity (generics) and generates the repository implementation; Hibernate pairs entity <-> table and generates SQL. Hibernate does not know repositories exist.
- Method names refer to Java field names of the repository's own entity; typos fail at startup. Other tables are reached through mapped relationships and cascades, not by the repository.

## 21. Generics
- A Java feature that lets a class/interface work with a type chosen later. `<...>` is where the type is chosen; `T` (or `ID`) is a type parameter, a variable standing for a type.
```java
public class Box<T> { private T item; public void put(T i) {...} public T get() {...} }
Box<String> a = new Box<>();   // a.put(42) is a compile error
```
- Benefit: the compiler enforces the type, so no casts and mistakes are caught at compile time instead of crashing at runtime. One definition serves every type.
- Seen already: `List<UserIdentity>`, `Optional<User>`, `new ArrayList<>()` (empty `<>` = infer the type), `JpaRepository<User, UUID>`.
- `JpaRepository<T, ID>` is declared once by Spring; `extends JpaRepository<User, UUID>` fills in the blanks, so `findById(UUID)` returns `Optional<User>`, `save(User)` etc. exist without being written.
- Type erasure: generic types of objects are mostly gone at runtime (`List<String>` and `List<Integer>` are both `List`), but the type arguments in a class/interface declaration (`extends JpaRepository<User, UUID>`) are kept in the class file. Spring reads them by reflection to learn the entity type.

## 22. Spring beans, the container, and dependency injection
- **Bean:** an object created, configured and managed by Spring instead of by `new`.
- **Container (ApplicationContext):** holds all beans; creates them at startup and keeps them for the app's lifetime.
- **Dependency injection (DI):** a bean declares what it needs (constructor parameters); the container builds those first and passes them in. **Inversion of Control (IoC):** the framework, not your code, creates and wires dependencies.
- **How classes become beans:** stereotype annotations found by component scanning (`@Service`, `@Component`, `@RestController`, `@Repository`); `@Bean` methods inside a `@Configuration` class (for objects you don't own); framework-generated beans (Spring Data repository proxies).
- **Component scanning** starts at the package of the `@SpringBootApplication` class (`io.gave.user`) and covers everything below it; classes outside that package are not found.
- **Benefits:** loose coupling (depend on interfaces, the container picks the implementation), easy testing (pass fakes with plain `new`), lifecycle and proxy-based features.
- **Singleton by default:** one shared instance per app, so beans must not hold per-request mutable state in fields (ours only hold `final` dependencies).
- **Proxies:** features like `@Transactional` work by Spring wrapping the bean in a proxy. An object created with `new` is not a bean, so `@Transactional` would silently do nothing.

## 23. Spring beans vs. .NET dependency injection
- Same concept: the container creates objects and injects dependencies via constructors. Spring: bean / ApplicationContext. .NET: service / `IServiceProvider` (`Microsoft.Extensions.DependencyInjection`).
- Registration: Spring scans for `@Service`/`@Component`/`@RestController` (less typing, more magic). .NET registers explicitly in `Program.cs` (`AddScoped/AddSingleton/AddTransient`); scanning needs a library like Scrutor.
- Lifetimes: Spring defaults to **singleton** (so beans must be stateless; per-request state lives in method parameters and the transaction). .NET services are usually **scoped** (per request); `AddSingleton` and `AddTransient` also exist. Spring equivalents: `@RequestScope`, `@Scope("prototype")` (rare).
- Unit of work: EF Core `DbContext` is scoped and `SaveChanges()` commits. In Spring, `@Transactional` sets the boundary and the Hibernate `EntityManager` is bound to the transaction via a proxy.
- Rough mapping: `@Service` ~ `AddScoped` class; `@RestController` ~ `[ApiController]`; Hibernate ~ EF Core; Spring Data repository ~ repository over `DbSet<T>`; Flyway ~ migrations (SQL files, like DbUp); `application.yml` ~ `appsettings.json`.

## 24. Singleton beans and concurrent requests
- .NET has no default lifetime; you pick one per registration. *Scoped* = one instance per request, *Singleton* = one for the whole app (shared by all requests, as in Spring), *Transient* = new each time. Convention in .NET: most services and the `DbContext` are scoped. Convention in Spring: nearly everything is a singleton.
- In Spring one instance (e.g. `UserProvisioningService`) is shared by the whole microservice. The web server runs each request on its own thread, so several threads can execute the same method on the same object at once.
- Therefore singleton beans must be stateless: fields hold only `final` dependencies. Per-request data lives in method parameters and local variables (each thread has its own stack). Never keep request state in a field.
- Per-request state in Spring: parameters/locals; the transaction and its Hibernate session (a shared repository proxy uses the current transaction's session); the security context (stored per thread by Spring Security); rarely `@RequestScope` beans.
- Why singleton: cheap, one object created at startup and no per-request allocation.

## 25. Thread safety: heap vs. stack
- Heap (objects and their fields) is shared by all threads. Each thread has its own stack holding method parameters and local variables. Method code is shared, but every call gets a fresh stack frame on the calling thread.
- A singleton bean is safe when its fields are only `final` dependencies/constants: nothing changes, so threads cannot overwrite each other. All request-specific data (`profile`, `email`, `user`) lives on each thread's own stack.
- Danger = shared mutable state, e.g. a `private User currentUser` or `private int counter` field in a bean. Threads interleave and overwrite or lose updates (race condition; `counter++` is not atomic).
- Shared dependencies are designed to be safe: the repository proxy uses the `EntityManager` bound to the current thread's transaction; the connection pool (HikariCP) lends each thread its own connection; `final` fields assigned in a constructor are safely visible to all threads.
- Rules: (1) fields in singletons are only `final` dependencies/constants; (2) changing data goes in parameters/locals; (3) deliberately shared mutable data uses thread-safe tools (`ConcurrentHashMap`, `AtomicInteger`) or the database.

## 26. Why .NET has scoped services and how Spring avoids needing them
- EF Core `DbContext` is stateful (change tracker, a connection) and not thread-safe. Sharing one across requests would be the shared-mutable-state problem, so it gets a per-request lifetime (scoped = unit of work per request).
- Anything depending on the `DbContext` must also be scoped (repository, then services above it). A singleton depending on a scoped service is a "captive dependency". Stateless helpers with no scoped dependency can be singletons.
- Scoped instances are small and cheap; scoped also gives a home for request context (current user, tenant, correlation id).
- Spring gets the same per-request isolation differently: repositories/services stay singletons; the Hibernate session is bound to the current thread's transaction (`@Transactional`) and the singleton repository proxy finds it on each call. Security context is thread-bound too.
- Trade-off: .NET scoped = easier to reason about but you must respect lifetimes when registering. Spring singleton = leaner but needs stateless discipline and relies on proxies.

## 27. JPA/Hibernate is stateful (persistence context)
- The Spring beans (repositories, services) are stateless; the Hibernate `Session`/`EntityManager` is stateful. Its state lives in the **persistence context** (first-level cache), one per transaction, comparable to EF Core's change tracker.
- **Identity map:** within a transaction, loading the same row twice returns the same object (no second SQL).
- **Dirty checking:** loaded (managed) entities are snapshotted; at commit Hibernate compares and issues UPDATEs for changes. In a `@Transactional` method, `user.setDisplayName(x)` is persisted without calling `save`.
- **Write-behind:** inserts/updates are queued and sent on flush/commit (hence `saveAndFlush` in tests).
- The singleton repository proxy finds the session bound to the current transaction/thread on each call: stateless bean -> proxy -> stateful per-transaction session.
- With `open-in-view: false` the session ends with the transaction; entities become detached and lazy loading then throws `LazyInitializationException`. Do not keep entities in fields or caches across requests.
- `StatelessSession` (no persistence context, dirty checking or cascades) exists for bulk/batch work where tracking only wastes memory.

## 28. DbContext vs. Hibernate session: who sets the boundary
- Both are stateful (identity map, change tracking, delayed writes).
- EF Core `DbContext`: lifetime = one request (DI scope). Transactions happen inside it: each `SaveChanges()` is its own implicit transaction, or explicit via `BeginTransaction`. One request can call `SaveChanges()` several times on the same context.
- Hibernate in Spring: session lifetime = one transaction (`@Transactional`). This is Spring's integration choice, not a Hibernate rule; a raw `Session` lives as long as it is kept open.
- `spring.jpa.open-in-view: true` (Boot's default if unset) keeps the session open for the whole request, giving .NET-like request scope and lazy loading everywhere. We set it to `false`: connections are not held during slow non-DB work, queries must be loaded inside the service (no hidden queries from controllers/JSON serialization), and lazy-loading mistakes show up in development.
- EF Core does not lazy-load by default (`Include` = our `@EntityGraph`), and the context stays alive for the request, so `LazyInitializationException` is mostly a Spring/Hibernate experience.

## 29. Why Spring services can be singletons: access by lookup, not by field
- It is not the scope length (transaction vs. request) that decides this; it is how the service reaches the stateful object.
- .NET default: the service holds the `DbContext` in a field set at construction. A singleton service would pin one context for all requests, so services are scoped (new service + new context per request).
- Spring: the service holds a repository, a singleton proxy that stores no session. On each call it looks up the session bound to the current thread's transaction. The stateful object is found at call time, so the service holds nothing that changes and can be a singleton.
- Proof: with `open-in-view: true` Spring has a request-level session and the beans are still singletons. Conversely, .NET's `IDbContextFactory<T>` lets a singleton service create a short-lived `DbContext` inside each method (lookup-at-call-time), as used in Blazor Server and background workers.
- Summary: field-held stateful dependency -> needs scoped owner. Lookup-at-call-time (proxy, factory) -> owner can be singleton.

## 30. Singleton default vs. scoped default: which is better
- Neither wins in general; it is safety vs. leanness. Singleton default (Spring): fastest, but one mutable field shared across threads causes intermittent race conditions that appear only under concurrent load. Scoped default (.NET): mutable fields belong to one request, and captive dependencies (singleton holding a scoped service) are caught by validation in development.
- Performance difference is negligible: a few small objects per request cost microseconds; one database call costs milliseconds.
- The safer default suits learners and mixed-experience teams. The real answer in both worlds is the same discipline: stateless services (only `final` dependencies, changing data in parameters/locals); then singleton is optimal and scoped is unneeded overhead.
- Review habit: reject any non-`final` field on a bean. In Spring, use `@RequestScope` for the rare bean that truly needs per-request state.

## 31. Spring Security basics
- Adding `spring-boot-starter-security` locks down every endpoint by default (default deny); you then open what is public. Keep `anyRequest().authenticated()` last so anything forgotten stays protected.
- Spring Security is a chain of servlet filters every request passes through before reaching controllers. A `SecurityFilterChain` bean describes the rules (lambda DSL: `authorizeHttpRequests(auth -> auth.requestMatchers(...).permitAll().anyRequest().authenticated())`).
- `oauth2Login(...)` adds the login filters and endpoints: `/oauth2/authorization/google` starts the flow, `/login/oauth2/code/google` receives Google's callback. For Google, Spring already knows the endpoints; we provide `client-id`, `client-secret`, `scope`.
- `@Configuration` + `@Bean` method: creates a bean from the return value, the way to register objects you do not own (like `SecurityFilterChain`).
- `${GOOGLE_CLIENT_ID}` in `application.yml` reads an environment variable; unresolved placeholders fail startup (so tests need the variables set).

## 32. SecurityConfig explained line by line
- `@Configuration` + `@Bean SecurityFilterChain`: the returned chain is what Spring Security uses. `HttpSecurity` is a builder Spring supplies; other beans (our success handler) can be injected as `@Bean` method parameters.
- `authorizeHttpRequests`: rules evaluated top to bottom, first match wins; `anyRequest().authenticated()` last = default deny.
- `oauth2Login(...)` adds the login filters and the `/oauth2/authorization/google` and `/login/oauth2/code/google` endpoints; `successHandler(...)` replaces the default redirect with our handler.
- Filters involved: `OAuth2AuthorizationRequestRedirectFilter` (starts the flow), `OAuth2LoginAuthenticationFilter` (code exchange, token validation, builds `Authentication`), and the authorization filter (applies the rules).
- `Authentication` holds `principal` (the user, here an `OidcUser`), `credentials` (cleared after login) and `authorities`. `OidcUser` exposes claims through getters (`getSubject()`, `getEmail()`, `getEmailVerified()`, `getFullName()`, `getPicture()`, `getClaims()`).
- `authentication.getPrincipal()` returns `Object`, so the handler casts to `OidcUser`; safe because only the OIDC login path reaches it.
- Boundary pattern: the handler is the only place where Spring Security types meet our code; it converts to `ProviderProfile` so the service stays framework-free.

## 33. How SecurityConfig lines map to filters (observed from the real filter chain)
- Chain at startup (DEBUG log "Will secure any request with filters"): DisableEncodeUrlFilter, WebAsyncManagerIntegrationFilter, SecurityContextHolderFilter, HeaderWriterFilter, CsrfFilter, LogoutFilter, **OAuth2AuthorizationRequestRedirectFilter**, **OAuth2LoginAuthenticationFilter**, DefaultResourcesFilter, DefaultLoginPageGeneratingFilter, DefaultLogoutPageGeneratingFilter, RequestCacheAwareFilter, SecurityContextHolderAwareRequestFilter, AnonymousAuthenticationFilter, ExceptionTranslationFilter, **AuthorizationFilter**. Enable with `logging.level.org.springframework.security=DEBUG`.
- `.oauth2Login(...)`: at `http.build()` the OAuth2 login configurer creates and inserts the two OAuth2 filters, wiring in the client registrations (from `application.yml`, `registration.google` -> id `google`) and our success handler.
- `.authorizeHttpRequests(...)`: configures the LAST filter (`AuthorizationFilter`) with the rules; it only judges requests that earlier filters did not handle.
- URL matching lives inside each filter, not in our config. The redirect filter's built-in matcher is `/oauth2/authorization/{registrationId}`; the callback filter's is `/login/oauth2/code/{registrationId}`.
- Each request walks the filters in order; a non-matching filter calls `chain.doFilter(...)` to pass it on; a filter that handles the request returns without doing so, ending the chain. That is why the login URLs need no `permitAll`.
- Observed behaviour: `/oauth2/authorization/google` -> 302 to Google (with scope, state, nonce, redirect_uri and a PKCE `code_challenge`); `/api/health` -> reaches the controller as anonymous (`permitAll`); any other URL as anonymous -> `ExceptionTranslationFilter` redirects to `/oauth2/authorization/google` (single registration, so the login page is skipped).
- PKCE: Spring adds `code_challenge`/`code_verifier` automatically so a stolen authorization code cannot be redeemed without the original verifier.

## 34. Spring Security JWT pieces (stage 1)
- `@ConfigurationProperties(prefix = "gave.jwt")` on a **record** binds YAML into a typed immutable object (Spring converts `file:...pem` paths into `RSAPrivateKey`/`RSAPublicKey`); register with `@EnableConfigurationProperties(JwtProperties.class)`. Better than scattered `@Value` strings.
- `JwtEncoder` (`NimbusJwtEncoder` over an `ImmutableJWKSet` of an `RSAKey`) signs; `JwtDecoder` (`NimbusJwtDecoder.withPublicKey(...)`) verifies. Resource-server support picks up the `JwtDecoder` bean.
- Building a token: `JwtClaimsSet.builder().issuer().subject().issuedAt().expiresAt().build()`, `JwsHeader.with(SignatureAlgorithm.RS256).build()`, `jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue()`.
- `ResponseCookie.from(name, value).httpOnly(true).secure(..).sameSite("Lax").path("/").maxAge(..).build()`; add via `response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())`.
- `oauth2ResourceServer(rs -> rs.bearerTokenResolver(...).jwt(...))`: by default the token is read from `Authorization: Bearer`; a custom `BearerTokenResolver` (lambda `request -> token or null`) reads it from the cookie instead. The same machinery then validates signature/expiry and builds an `Authentication` with a `Jwt` principal.
- `@AuthenticationPrincipal Jwt jwt` injects the current principal into a controller method.
- `exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(UNAUTHORIZED), matcher("/api/**")))`: API paths get 401 instead of the login redirect. In Spring Security 7 the matcher is `org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher`.
- DTO mapping: `UserResponse.from(user)` (record + static factory) exposes only chosen fields; entities never leave the service layer. `@ResponseStatus(HttpStatus.NOT_FOUND)` on an exception is a stopgap until a global `@RestControllerAdvice`.
- `@Transactional(readOnly = true)` for read-only service methods.
- Layout now: `config` (bean wiring), `security` (JWT/handler), `web` (controllers + response DTOs), `service`, `repository`, `domain`, `exception`.
