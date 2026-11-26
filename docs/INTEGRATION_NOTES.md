# GraphSpindle integration spike report (Boot 4.2.0 / Spring AI 2.1.0 / Neo4j 2026.10.2)

Spike project: `/tmp/claude-1000/-home-deepak-Documents-Projects-graph-spindle--claude-worktrees-new-session-da3476/2e398abb-1699-48ac-8d3f-2206e3360bf4/scratchpad/spike`
(the real worktree was not modified). Final `./mvnw -B clean verify`: **BUILD SUCCESS**. Surefire ran 6 tests, 0 failures. Failsafe ran 7 tests, 0 failures, 2 skipped (the opt-in live LLM tests).
Live LLM runs were done separately, see section 3.

## Outcome summary

| # | Check | Result | Evidence |
|---|-------|--------|----------|
| 1a | Boot 4.2 Neo4j support | PASS | Boot 4.2 provides `spring-boot-starter-neo4j`, which is driver only (neo4j-java-driver 6.3.0 + `Driver` autoconfig + health indicator), separate from `spring-boot-starter-data-neo4j` (SDN). **Recommend `spring-boot-starter-neo4j`**: plain `Driver` + hand-written parameterized Cypher. `Neo4jClient` comes from SDN, so you would need data-neo4j to get it. It is not needed. |
| 1b | neo4j-harness 2026.10.2 in-process IT (constraint, write/read, uniqueness violation, explicit rollback, managed-tx rollback) | PASS | `Neo4jHarnessIT`: 2/2 tests pass. Harness boot about 2.5 s, Spring context 1.9 s |
| 1c | Dependency conflicts | PASS with 1 exclusion | Only `org.neo4j:neo4j-slf4j-provider` (a second SLF4J provider) needs excluding. Jetty, Jersey, Scala and log4j-core all stay test-scoped and do no harm once `.withDisabledServer()` is set |
| 1d | JVM flags for harness on Java 21 | None needed | No `--add-opens` and no argLine. The only JVM warning is Mockito/byte-buddy's dynamic agent warning (from Boot test, not from the harness) |
| 1e | Context starts with no Neo4j running | PASS, with a caveat | The driver is lazy, so the context starts fine. **But `/actuator/health` becomes DOWN (503)** because of `Neo4jReactiveHealthIndicator`. Unit tests need `management.health.neo4j.enabled=false`. Verified with the packaged jar too: started in 1.9 s, health DOWN, MCP still served |
| 2a | MCP server webmvc, properties | PASS | Protocols are `SSE`, `STREAMABLE` (default), `STATELESS` and a new `MODERN` (2026-07-28 spec). Endpoint: `spring.ai.mcp.server.streamable-http.mcp-endpoint` (default `/mcp`) |
| 2b | Tool exposure | PASS, both styles | `@McpTool` on a `@Component` (annotation scanner, on by default) **and** `@Tool` via a `MethodToolCallbackProvider` bean. Both appear together: `MCP tools: [add, echo, whoami]` |
| 2c | MCP Java SDK client IT over HTTP (initialize, listTools, callTool) | PASS | `McpServerIT`: 3/3. SDK `io.modelcontextprotocol.sdk:mcp` 2.2.0 is already on the classpath through the server starter, so no extra test dependency is needed. Negotiated protocol is 2025-11-25 |
| 2d | API-key auth with a Spring Security filter on /mcp | PASS (after one fix) | No key or a wrong key gives raw HTTP **401**. The SDK client fails `initialize()`. `/actuator/health` stays open. The **ASYNC dispatch** pitfall is fixed (see Pitfalls) |
| 2e | Caller identity inside the tool | PASS | `whoami: transport=api-client;securityContext=api-client;thread=http-nio-...-exec-7`. With SYNC + STREAMABLE, the tool runs on the servlet thread, so `SecurityContextHolder` works. The transport-context route (a `contextExtractor` copies a request attribute) also works and is transport-independent. **Prefer the transport context.** |
| 3a | Select the active chat model | PASS | `spring.ai.model.chat=ollama\|openai\|anthropic\|none`. Any value that matches no provider (`none`) disables all three. Verified by `ChatModelSelectionTest` (4 nested contexts), each with exactly one `ChatModel` of the right class, or zero for `none` |
| 3b | `none` gives a context with no ChatModel and no API keys | PASS | Zero `ChatModel` beans and zero `EmbeddingModel` beans. The Anthropic and OpenAI contexts also start without real keys, because keys are only used at call time |
| 3c | OpenAI starter base-url override (OpenAI-compatible server) | PASS (live) | `spring.ai.openai.base-url=http://localhost:11434/v1` with `api-key=` (empty, so the SDK uses no-auth mode) against Ollama's OpenAI endpoint gave `Person[name=Bob Smith, employer=Initech, age=42]` in **1007 ms** |
| 3d | `ChatClient...call().entity(Record.class)` with a hand-written fake `ChatModel` | PASS | `FakeChatModelTest`: canned JSON, even inside a json code fence, is mapped into nested records. Temperature passes through `.options(ChatOptions.builder().temperature(0.0))`. Format instructions with the JSON schema are appended to the user message |
| 3e | Live Ollama `gemma4:e4b` with `entity()` | PASS | Cold: `Person[...age=42]` in **3210 ms** (885 ms on a second run). Warm: **132–172 ms**. Provider-native schema (`entity(Person.class, s -> s.useProviderStructuredOutput().validateSchema())`): **1629 ms**, correct |

---

## 1. pom.xml (exact working snippets)

```xml
<properties>
  <java.version>21</java.version>
  <spring-ai.version>2.1.0</spring-ai.version>
  <springdoc.version>3.2.1</springdoc.version>
  <neo4j-harness.version>2026.10.2</neo4j-harness.version>   <!-- latest 2026.10.x on Central -->
</properties>

<!-- dependencyManagement: unchanged (spring-ai-bom ${spring-ai.version} import) -->

<dependencies>
  <!-- existing: starter-webmvc, starter-validation, starter-actuator, springdoc ... -->
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-neo4j</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
  <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-mcp-server-webmvc</artifactId></dependency>
  <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-model-ollama</artifactId></dependency>
  <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-model-openai</artifactId></dependency>
  <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-model-anthropic</artifactId></dependency>

  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  <dependency>
    <groupId>org.neo4j.test</groupId><artifactId>neo4j-harness</artifactId>
    <version>${neo4j-harness.version}</version><scope>test</scope>
    <exclusions>
      <!-- second SLF4J provider: competes with Logback; provider choice becomes classpath-order dependent -->
      <exclusion><groupId>org.neo4j</groupId><artifactId>neo4j-slf4j-provider</artifactId></exclusion>
    </exclusions>
  </dependency>
</dependencies>
```

Build plugins: unchanged. `spring-boot-maven-plugin` + `maven-failsafe-plugin` (`integration-test` and `verify` goals) as already in the pom. **No surefire/failsafe argLine or JVM flags are required.** Optional: to silence Mockito's "agent loaded dynamically" warning, add `-XX:+EnableDynamicAgentLoading` to the argLine (cosmetic only).

Resolved versions worth knowing: neo4j-java-driver 6.3.0 (Boot BOM), MCP SDK `io.modelcontextprotocol.sdk:mcp`/`mcp-core` 2.2.0 + `mcp-json-jackson3`, `org.springframework.ai:mcp-spring-webmvc` 2.1.0 (the WebMVC transport now lives in Spring AI), openai-java-core 4.101.0, anthropic-java-core 3.8.0, Jackson 3.1.8, netty 4.2.20.

Exclusions are **not** needed for: Jetty 12 ee8 / Jersey (mixed 2.48 and 4.0.3 versions because Boot's BOM overrides them; harmless because `.withDisabledServer()` never loads them), Scala 2.13/3 libs, log4j-core (Boot still picks Logback), bouncycastle and parquet. All are test scope.

## 2. application.yml (exact, working)

```yaml
spring:
  application:
    name: graph-spindle
  autoconfigure:
    # own API-key filter; suppress generated in-memory user + password WARN
    exclude: org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
  neo4j:
    uri: ${GRAPHSPINDLE_NEO4J_URI:bolt://localhost:7687}
    authentication:
      username: ${GRAPHSPINDLE_NEO4J_USERNAME:neo4j}
      password: ${GRAPHSPINDLE_NEO4J_PASSWORD:}      # empty password -> AuthTokens.none()
  ai:
    model:
      chat: ${GRAPHSPINDLE_LLM_PROVIDER:none}        # ollama | openai | anthropic | none
      # every other model type is matchIfMissing=true in the starters -> switch off explicitly
      embedding: none
      image: none
      moderation: none
      decision: none
      audio:
        speech: none
        transcription: none
    ollama:
      base-url: ${GRAPHSPINDLE_OLLAMA_BASE_URL:http://localhost:11434}
      chat:
        model: ${GRAPHSPINDLE_OLLAMA_MODEL:gemma4:e4b}
        temperature: 0.0
    openai:
      base-url: ${GRAPHSPINDLE_OPENAI_BASE_URL:https://api.openai.com/v1}   # used verbatim; include /v1
      api-key: ${GRAPHSPINDLE_OPENAI_API_KEY:}       # empty string = no-auth mode (local compat servers)
      # model: spring.ai.openai.chat.model (flat; ".chat.options.*" is deprecated since 2.0)
    anthropic:
      api-key: ${GRAPHSPINDLE_ANTHROPIC_API_KEY:}
      # model: spring.ai.anthropic.chat.model
    mcp:
      server:
        name: graph-spindle
        version: 0.1.0
        type: SYNC
        protocol: STREAMABLE          # SSE | STREAMABLE (default) | STATELESS | MODERN
        streamable-http:
          mcp-endpoint: /mcp
graphspindle:
  security:
    api-key: ${GRAPHSPINDLE_API_KEY:}
server:
  address: ${GRAPHSPINDLE_BIND_ADDRESS:127.0.0.1}
  port: ${GRAPHSPINDLE_PORT:8080}
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

Property facts verified in 2.1.0 sources:
- `SpringAIModelProperties`: `spring.ai.model.chat`, `.embedding`, `.embedding.text`, `.embedding.multimodal`, `.image`, `.audio.transcription`, `.audio.speech`, `.moderation`, `.decision`. Provider ids (`SpringAIModels`) are `ollama`, `openai`, `anthropic`, among others. There is no `none` constant. Any non-matching value disables all providers.
- Chat options are **flat** in 2.x: `spring.ai.openai.chat.temperature`, `spring.ai.ollama.chat.model`, `spring.ai.anthropic.chat.max-tokens`. `...chat.options.*` is `@Deprecated(since="2.0.0", forRemoval=true)`.
- OpenAI 2.1 uses the official openai-java SDK. `spring.ai.openai.chat.api=chat-completions` (default) or `responses`. If `base-url` is unset, the `OPENAI_BASE_URL` env var is used, then api.openai.com. If `api-key` is unset, the `OPENAI_API_KEY` env var is used.
- MCP: `spring.ai.mcp.server.{enabled,stdio,name,version,instructions,type(SYNC|ASYNC),protocol,request-timeout(20s),capabilities.{tool,resource,prompt,completion}}`, `spring.ai.mcp.server.streamable-http.{mcp-endpoint,keep-alive-interval,disallow-delete}`, SSE: `spring.ai.mcp.server.{sse-endpoint=/sse,sse-message-endpoint=/mcp/message,base-url}`, `spring.ai.mcp.server.annotation-scanner.enabled=true`.

## 3. Code that worked

### 3.1 Harness IT base class (`src/test/java/.../AbstractNeo4jIT.java`)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractNeo4jIT {
    protected static final Neo4j NEO4J;
    static {
        NEO4J = Neo4jBuilders.newInProcessBuilder()
                .withWorkingDir(Path.of("target", "neo4j-harness"))   // default = random dir in java.io.tmpdir
                .withDisabledServer()                                 // bolt only; never loads Jetty/Jersey
                .withConfig(GraphDatabaseSettings.udc_enabled, false)                    // no usage-data reporting
                .withConfig(FleetManagementSettings.fleet_manager_enabled, false)        // org.neo4j.fleetmanagement
                .withConfig(BoltConnector.enable_discovery, false)                       // no LAN UDP broadcasts
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(NEO4J::close, "neo4j-harness-shutdown"));
    }

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry r) {
        r.add("spring.neo4j.uri", () -> NEO4J.boltURI().toString());
        r.add("spring.neo4j.authentication.username", () -> "neo4j");
        r.add("spring.neo4j.authentication.password", () -> "");   // harness auth disabled -> AuthTokens.none()
    }
}
```
Imports: `org.neo4j.configuration.GraphDatabaseSettings`, `org.neo4j.configuration.connectors.BoltConnector`, `org.neo4j.fleetmanagement.FleetManagementSettings`, `org.neo4j.harness.{Neo4j,Neo4jBuilders}`.

The test body (`Neo4jHarnessIT`) uses the plain `Driver`:
```java
try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
  session.run("CREATE CONSTRAINT entity_id IF NOT EXISTS FOR (e:Entity) REQUIRE e.id IS UNIQUE").consume();
  session.executeWrite(tx -> tx.run("MERGE (e:Entity {id: $id}) SET e.name = $name", Map.of("id","e1","name","Alice")).consume());
  String name = session.executeRead(tx -> tx.run("MATCH (e:Entity {id: $id}) RETURN e.name AS name", Map.of("id","e1")).single().get("name").asString());
  // duplicate -> Neo4jException "...already exists..."
  try (var tx = session.beginTransaction()) { tx.run("CREATE (:Entity {id: $id})", Map.of("id","rolled-back")).consume(); tx.rollback(); }
  // exception thrown inside executeWrite callback -> automatic rollback
}
```
Health with the harness running reports `"status":"UP"` (asserted).

### 3.2 MCP server: tools, header to tool context, security

```java
@Component
public class EchoTools {
    @McpTool(name = "echo", description = "Echo back the given text")
    public String echo(@McpToolParam(description = "Text to echo") String text) { return "echo:" + text; }

    @McpTool(name = "whoami", description = "Return the caller identity seen by the tool")
    public String whoami(McpSyncRequestContext ctx) {               // injected, not part of the input schema
        Object p = ctx.transportContext().get(ApiKeyAuthFilter.PRINCIPAL_ATTR);
        ...
    }
}

// classic Spring AI tools also exposed over MCP:
public class SpringAiTools { @Tool(name="add", description="Add two integers") public int add(@ToolParam(description="a") int a, @ToolParam(description="b") int b) { return a + b; } }

@Configuration(proxyBeanMethods = false)
public class McpConfig {
    @Bean ToolCallbackProvider springAiTools() {
        return MethodToolCallbackProvider.builder().toolObjects(new SpringAiTools()).build();
    }
    /** Copies the principal resolved by the security filter into the MCP transport context. */
    @Bean McpServerCustomizer<WebMvcStreamableServerTransportProvider.Builder> mcpContextExtractor() {
        return b -> b.contextExtractor(req -> req.attribute(ApiKeyAuthFilter.PRINCIPAL_ATTR)
                .map(p -> McpTransportContext.create(Map.of(ApiKeyAuthFilter.PRINCIPAL_ATTR, p)))
                .orElse(McpTransportContext.EMPTY));
    }
}
```
Packages: `org.springframework.ai.mcp.annotation.{McpTool,McpToolParam}`, `org.springframework.ai.mcp.annotation.context.McpSyncRequestContext`, `org.springframework.ai.mcp.customizer.McpServerCustomizer`, `org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider`, `io.modelcontextprotocol.common.McpTransportContext`. For STATELESS use `McpServerCustomizer<WebMvcStatelessServerTransport.Builder>`, which has the same `contextExtractor` method.

Security (`ApiKeyAuthFilter extends OncePerRequestFilter` compares `X-API-Key` with `MessageDigest.isEqual`, sets an authenticated token in the `SecurityContextHolder` and a request attribute):
```java
http.csrf(c -> c.disable())
    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .httpBasic(b -> b.disable()).formLogin(f -> f.disable())
    .addFilterBefore(new ApiKeyAuthFilter(apiKey), AnonymousAuthenticationFilter.class)
    .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
    .authorizeHttpRequests(a -> a
        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()   // REQUIRED, see pitfalls
        .requestMatchers("/mcp", "/mcp/**").authenticated()
        .anyRequest().permitAll());
```

### 3.3 MCP client IT (SDK 2.2.0, no extra dependency)

```java
var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
        .endpoint("/mcp")
        .httpRequestCustomizer((builder, method, uri, body, ctx) -> builder.header("X-API-Key", "test-key"))
        .build();
try (McpSyncClient client = McpClient.sync(transport).build()) {
    client.initialize();
    var names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();   // [add, echo, whoami]
    var r = client.callTool(new McpSchema.CallToolRequest("echo", Map.of("text", "hi"), null));
    ((McpSchema.TextContent) r.content().get(0)).text();                                  // "echo:hi"
}
```
`@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {"graphspindle.security.api-key=test-key", "management.health.neo4j.enabled=false"})`. Without a key, `client.initialize()` throws `RuntimeException: Client failed to initialize by explicit API call`, and a raw POST to `/mcp` returns 401. Checked against the packaged jar with curl: no key gives 401. With the key, the response is 200 with an `Mcp-Session-Id` header.

### 3.4 Fake ChatModel (deterministic offline double)

```java
static final class FakeChatModel implements ChatModel {
    final List<Prompt> prompts = new ArrayList<>();
    private final String cannedJson;
    FakeChatModel(String cannedJson) { this.cannedJson = cannedJson; }
    @Override public ChatResponse call(Prompt prompt) {          // the only abstract method
        prompts.add(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(cannedJson))));
    }
}

Extraction result = ChatClient.create(fake).prompt()
        .system("You extract knowledge-graph triples.")
        .user("Alice works at Acme.")
        .options(ChatOptions.builder().temperature(0.0))          // generic, provider-neutral options
        .call()
        .entity(Extraction.class);                                // record Extraction(List<Triple> triples)
// fake.prompts.get(0).getOptions().getTemperature() == 0.0 ; user message contains JSON schema instructions
```
Generic options: `.options(B extends ChatOptions.Builder<?>)` takes the builder, not a built `ChatOptions`. For JSON enforcement, use `entity(T.class, spec -> spec.useProviderStructuredOutput())` (native schema at the API level for providers whose options implement `StructuredOutputChatOptions`) and/or `.validateSchema()` (validate and retry up to 3 times, no streaming). The global equivalent is `.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)`. The default (neither option) is prompt-based format instructions plus `BeanOutputConverter`, which tolerates code fences.

### 3.5 Live smoke (`LiveLlmSmokeIT`, opt-in via `-Dgraphspindle.live-llm=ollama|openai-compat`)

```
./mvnw -B verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=LiveLlmSmokeIT -Dgraphspindle.live-llm=ollama
LIVE[warmup] OllamaChatModel -> Person[name=Bob Smith, employer=Initech, age=42] in 3210 ms   (cold)
LIVE[ollama] OllamaChatModel -> Person[name=Bob Smith, employer=Initech, age=42] in 172 ms
LIVE[ollama-native-schema] -> Person[name=Bob Smith, employer=Initech, age=42] in 1629 ms
LIVE[openai-compat] OpenAiChatModel -> Person[name=Bob Smith, employer=Initech, age=42] in 1007 ms
```
Maven `-D` user properties do reach the forked failsafe JVM, so `@EnabledIfSystemProperty` works without extra config.

## 4. Pitfalls found

1. **Spring Security + MCP streamable/SSE = AuthorizationDeniedException on ASYNC dispatch.** Every MCP response logged `AuthorizationDeniedException: Access Denied` / "response is already committed" / `IllegalStateException: Missing result context`, while the tests still passed. The cause: with a STATELESS session policy and a `OncePerRequestFilter` (which skips async dispatches), the async re-dispatch has no authentication and `AuthorizationFilter` checks it again. Fix: `.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()`. This is safe because the REQUEST dispatch was already authorized. Verified: 0 occurrences after the fix.
2. **Adding `spring-boot-starter-security` locks every endpoint** (basic auth + generated password) until you declare a `SecurityFilterChain`. Exclude `UserDetailsServiceAutoConfiguration` to drop the generated-password WARN.
3. **Neo4j health makes `/actuator/health` DOWN (503) when Neo4j is absent.** The context still starts because the driver is lazy. Unit tests without a DB need `management.health.neo4j.enabled=false`. That is now done in the existing `GraphSpindleApplicationTests`, which otherwise fails with 503. The reactive indicator is used because reactor is on the classpath via the Ollama starter.
4. **All provider starters default to ON** (`matchIfMissing = true` on chat, embedding, image, moderation, decision and audio for Ollama, OpenAI and Anthropic). If `spring.ai.model.chat` is missing, you get three `ChatModel` beans, and an injected `ChatClient.Builder` or `ChatModel` becomes ambiguous. Always set `spring.ai.model.chat` (default `none`) and set the other model types to `none`.
5. **The `ChatClient.Builder` bean is prototype-scoped and needs a `ChatModel`.** With `chat=none`, inject `ObjectProvider<ChatModel>` (or a port interface with a "disabled" implementation), not `ChatClient.Builder` directly.
6. **neo4j-harness brings a second SLF4J provider** (`neo4j-slf4j-provider`), which triggers "multiple SLF4J providers" and order-dependent binding. Exclude it (done). Logback stays in charge.
7. **Harness defaults leak and phone home.** The working dir is a random `/tmp/<hex>` folder, left behind if `close()` is never called (seen in the first run). Its log also showed "Anonymous Usage Data is being sent to Neo4j" and "Fleet discovery broadcasts have been enabled for ... 10.0.0.255". Fixed with `withWorkingDir(target/...)`, a shutdown-hook `close()`, `udc_enabled=false`, `fleet_manager_enabled=false` and `BoltConnector.enable_discovery=false`. Verified the log is clean afterwards. `BoltConnectorInternalSettings.discovery_network_masks=[]` is rejected ("needs to be greater than size of 1"), so do not use it.
8. **Harness HTTP connector would bring javax-era Jetty ee8 + Jersey with mixed versions** (Boot's BOM forces Jersey 4.0.3 while `jersey-container-servlet-core` resolves to 2.48). Never start it: `.withDisabledServer()`. Use bolt only.
9. **Jackson 2 is on the compile classpath** (com.fasterxml 2.21.8 + Kotlin stdlib, via openai-java-core) alongside Boot 4's Jackson 3 (`tools.jackson`). App code must import `tools.jackson.*`. Watch IDE auto-imports. Do not add Jackson-2 Spring config.
10. **OpenAI base-url is used verbatim.** Include `/v1` for compatible servers. An empty `api-key` switches the SDK to no-auth mode, while a *missing* key falls back to the `OPENAI_API_KEY` env var.
11. **The MCP SDK's server-side `McpSchema.LATEST_PROTOCOL_VERSION` is 2026-07-28 (MODERN)**, but STREAMABLE + SDK client negotiated 2025-11-25. Stay on STREAMABLE until clients support MODERN. MODERN has its own `spring.ai.mcp.server.modern.*` properties and transport (`WebMvcModernServerTransport`), and a header-based `McpTransportContextExtractor<ServerRequest>` bean is picked up automatically there. This was not exercised.
12. Thread note: with SYNC + STREAMABLE, tools ran on the Tomcat request thread, so `SecurityContextHolder` was populated. Do not rely on that across protocol or ASYNC changes. Pass identity through `McpTransportContext` (shown above).

## 5. Files in the spike

- `spike/pom.xml`, `spike/src/main/resources/application.yml`
- main: `spike/src/main/java/io/graphspindle/spike/{EchoTools,SpringAiTools,McpConfig,ApiKeyAuthFilter,SecurityConfig}.java`
- test: `spike/src/test/java/io/graphspindle/spike/{AbstractNeo4jIT,Neo4jHarnessIT,McpServerIT,FakeChatModelTest,ChatModelSelectionTest,LiveLlmSmokeIT}.java`, plus the modified `spike/src/test/java/io/graphspindle/GraphSpindleApplicationTests.java` (adds `management.health.neo4j.enabled=false`)
- logs: `scratchpad/final.log` (clean verify), `scratchpad/live1.log`, `scratchpad/live2.log`, `scratchpad/live3.log` (live runs), `scratchpad/tree.txt` (dependency tree)
