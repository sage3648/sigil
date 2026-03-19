# Sigil

**An AST-native, content-addressed, agent-authored programming language with verified capabilities, algebraic effects, and a knowledge-sharing registry.**

Sigil is a programming language designed from first principles for a world where AI agents are the primary authors of code. Unlike existing languages — designed for humans to express intent to machines — Sigil treats agents as first-class writers and humans as consumers of verified, composable capabilities.

```
fn sort<T: Comparable>(arr: List<T>) -> List<T>
  ensures is_sorted(result)
  ensures same_elements(result, arr)
{
  match arr {
    []  => [],
    [x] => [x],
    _   => {
      let pivot = arr[0]
      let less = arr.filter(|x| x < pivot)
      let equal = arr.filter(|x| x == pivot)
      let greater = arr.filter(|x| x > pivot)
      sort(less) ++ equal ++ sort(greater)
    }
  }
}
```

---

## Why Sigil?

Agent-generated code is exploding, but there is no coordination layer for it. Every agent session starts from scratch. The knowledge an agent discovers while solving one problem is lost when the session ends.

Sigil fixes this by making the language itself a knowledge-sharing system.

| Problem | How Sigil Solves It |
|---|---|
| Agents duplicate work across sessions | Content-addressed identity — identical functions hash to the same value globally |
| No trust between agent-authored components | Contracts (`requires`/`ensures`) are part of the hash, verified at compile time |
| Hidden side effects cause composition bugs | Algebraic effect system — every side effect is declared and tracked |
| Code rots as runtimes change | Target-agnostic AST is the canonical form; JVM/WASM/LLVM are pluggable backends |
| Naming conflicts across agents | Names are aliases — identity is structural, not nominal |
| No way to find reusable components | Registry with semantic search by type signature, effects, and contracts |

---

## Architecture

```mermaid
graph LR
    subgraph Input
        A[Source Text] --> B[Parser]
        C[AST JSON] --> D[Deserializer]
    end

    B --> E[Untyped AST]
    D --> E

    subgraph "Verification Pipeline"
        E --> F[Type Checker]
        F --> G[Effect Checker]
        G --> H[Contract Verifier]
        H --> I[Blake3 Hasher]
    end

    I --> J[Verified AST + Hash]

    subgraph "Code Generation"
        J --> K[JVM Codegen]
        J --> L[WASM Codegen]
        J --> M[LLVM Codegen]
    end

    subgraph "Registry"
        J --> R[Registry Store]
        R --> S[Semantic Index]
        R --> T[Dependency DAG]
        R --> U[SMT Verifier]
        S --> V[Search API]
    end

    K --> N[".class bytecode"]
    L --> O[".wasm module"]
    M --> P["native binary"]

    style A fill:#4a9eff,color:#fff
    style C fill:#4a9eff,color:#fff
    style J fill:#2ecc71,color:#fff
    style N fill:#e74c3c,color:#fff
    style O fill:#95a5a6,color:#fff
    style P fill:#95a5a6,color:#fff
    style R fill:#f39c12,color:#fff
    style V fill:#f39c12,color:#fff
```

> **Phase 1** implements the full compiler pipeline from source text through JVM bytecode generation. **Phase 2** adds the knowledge-sharing registry with semantic search, dependency tracking, SMT verification, and an HTTP API. WASM and LLVM backends are planned for future phases.

---

## Compiler Pipeline

Every Sigil program passes through a six-stage verification pipeline before code generation:

```mermaid
flowchart TD
    A["1. Parse"] -->|"Text → Untyped AST"| B["2. Type Check"]
    B -->|"Hindley-Milner inference"| C["3. Effect Check"]
    C -->|"Verify declared effects match usage"| D["4. Contract Verify"]
    D -->|"requires/ensures are well-typed"| E["5. Blake3 Hash"]
    E -->|"Content-addressed identity"| F["6. JVM Codegen"]
    F -->|"ASM bytecode generation"| G[".class files"]

    A -.->|"Blocking"| B
    B -.->|"Blocking"| C
    C -.->|"Blocking"| D
    D -.->|"Blocking"| E
    E -.->|"On demand"| F

    style A fill:#3498db,color:#fff
    style B fill:#9b59b6,color:#fff
    style C fill:#2ecc71,color:#fff
    style D fill:#e67e22,color:#fff
    style E fill:#1abc9c,color:#fff
    style F fill:#e74c3c,color:#fff
    style G fill:#c0392b,color:#fff
```

| Stage | Component | Input | Output |
|---|---|---|---|
| Parse | `SigilParser` | Source text or JSON | Untyped AST |
| Type Check | `TypeChecker` (Algorithm W) | Untyped AST | Typed AST |
| Effect Check | `EffectChecker` | Typed AST | Effect-verified AST |
| Contract Verify | `ContractVerifier` | Typed AST | Contract-verified AST |
| Hash | `Blake3` + `Hasher` | Verified AST | Content hash (256-bit) |
| Codegen | `JvmCodegen` (ASM) | Verified AST | `.class` bytecode |

---

## Core Concepts

### AST-Native

The canonical representation of Sigil code is a typed, verified Abstract Syntax Tree — not text. Text files are a *projection* (a rendering format for human consumption). Agents author AST nodes directly via the programmatic API.

```mermaid
graph TD
    subgraph "Canonical Form"
        AST["Typed AST<br/>(Kotlin data classes)"]
    end

    subgraph "Projections"
        TXT["Text Syntax<br/>(.sigil files)"]
        JSON["JSON<br/>(Agent API)"]
        VIS["Visual<br/>(IDE rendering)"]
    end

    AST <-->|"roundtrip-safe"| TXT
    AST <-->|"serialization"| JSON
    AST -->|"read-only"| VIS

    style AST fill:#2ecc71,color:#fff
    style TXT fill:#3498db,color:#fff
    style JSON fill:#e67e22,color:#fff
    style VIS fill:#95a5a6,color:#fff
```

### Content-Addressed Identity

Every definition is identified by the Blake3 hash of its structural content. Names are just aliases.

```kotlin
// Two agents independently write the same function:

// Agent A calls it "quicksort"
fn quicksort(arr: List<Int>) -> List<Int> { /* ... */ }

// Agent B calls it "sort_fast"
fn sort_fast(arr: List<Int>) -> List<Int> { /* identical body */ }

// Both produce the SAME hash: a7f3b2e...
// Names are excluded from the hash. Structure IS identity.
```

**What goes into the hash:**

```mermaid
graph LR
    subgraph "Hashed (Identity)"
        P[params]
        R[return_type]
        C[contract]
        E[effects]
        B[body]
    end

    subgraph "Not Hashed (Metadata)"
        N[name]
        M[metadata]
        V[verification records]
        T[timestamps]
    end

    P & R & C & E & B --> H["Blake3<br/>256-bit hash"]

    style H fill:#1abc9c,color:#fff
    style N fill:#95a5a6,color:#fff
    style M fill:#95a5a6,color:#fff
    style V fill:#95a5a6,color:#fff
    style T fill:#95a5a6,color:#fff
```

### Contract System

Contracts (`requires`/`ensures`) are not optional annotations — they are part of the function's hash and identity. The compiler uses them to prove composition safety.

```
fn binary_search(arr: List<Int>, target: Int) -> Option<Int>
  requires is_sorted(arr)
  requires arr.length > 0
  ensures match result {
    Some(i) => arr[i] == target,
    None => true
  }
```

**Contract chaining** is the key composition mechanism:

```mermaid
graph LR
    A["sort()"] -->|"ensures: is_sorted(result)"| B{"Compiler proves<br/>postcondition ⊇ precondition"}
    B -->|"satisfies: requires is_sorted(arr)"| C["binary_search()"]

    style A fill:#2ecc71,color:#fff
    style B fill:#f39c12,color:#fff
    style C fill:#3498db,color:#fff
```

When `sort`'s ensures clause (`is_sorted(result)`) satisfies `binary_search`'s requires clause (`is_sorted(arr)`), the compiler can prove the composition is safe — no runtime check needed at the boundary.

### Effect System

Every side effect must be declared in the function signature. Pure functions are the default.

```
// The ! declares which effects this function performs
fn fetch_user(id: UserId) -> Result<User, Error> ! Http, Db, Log {
  Log.info("Fetching user")
  let row = Db.query("SELECT * FROM users WHERE id = ?", [id])
  match row {
    [r] => Ok(User.from_row(r)),
    []  => Err(Error.NotFound),
    _   => Err(Error.Ambiguous)
  }
}
```

```mermaid
graph TD
    subgraph "Effect Declarations"
        HTTP["effect Http { get, post }"]
        DB["effect Db { query, execute }"]
        LOG["effect Log { info, error }"]
    end

    subgraph "Function"
        FN["fetch_user() ! Http, Db, Log"]
    end

    subgraph "Handlers (at service boundary)"
        RH["RealHttp → actual HTTP client"]
        MH["MockHttp → test stub"]
        RD["RealDb → PostgreSQL"]
        MD["MockDb → in-memory"]
    end

    HTTP --> FN
    DB --> FN
    LOG --> FN
    FN --> RH
    FN --> MH
    FN --> RD
    FN --> MD

    style FN fill:#9b59b6,color:#fff
    style RH fill:#2ecc71,color:#fff
    style MH fill:#3498db,color:#fff
    style RD fill:#2ecc71,color:#fff
    style MD fill:#3498db,color:#fff
```

Effects propagate through the call graph. If function A calls function B which has `Http`, then A also has `Http` unless it handles it with an effect handler.

---

## Registry

The registry is Sigil's knowledge-sharing layer. When an agent publishes a verified function, it becomes discoverable by all other agents — searchable by type signature, effects, contracts, and intent.

```mermaid
flowchart LR
    subgraph "Agent Publishes"
        SRC[Source Code] --> PIPE[Compiler Pipeline]
        PIPE --> VER{Verified?}
        VER -->|Yes| STORE[Registry Store]
        VER -->|No| ERR[Rejection + Errors]
    end

    subgraph "Registry Processing"
        STORE --> SEM[Semantic Extractor]
        STORE --> DEP[Dependency Tracker]
        STORE --> SMT[SMT Verifier]
        SEM --> IDX[Semantic Index]
    end

    subgraph "Agent Discovers"
        Q[Search Query] --> IDX
        IDX --> RES[Ranked Results]
        RES --> USE[Reference by Hash]
    end

    style STORE fill:#f39c12,color:#fff
    style IDX fill:#3498db,color:#fff
    style SMT fill:#2ecc71,color:#fff
    style ERR fill:#e74c3c,color:#fff
```

### Publishing

Submit source code to the registry. The full compiler pipeline runs automatically — type checking, effect checking, contract verification, hashing, semantic signature extraction, and dependency tracking.

```bash
# Publish via HTTP API
curl -X POST http://localhost:8080/registry/publish \
  -H "Content-Type: application/json" \
  -d '{
    "source": "fn abs(x: Int) -> Int { ensures result >= 0; if x < 0 then -x else x }",
    "metadata": {
      "aliases": ["absolute_value"],
      "intent": "returns the absolute value of an integer",
      "domain_tags": ["math", "numeric"]
    }
  }'

# Response:
# {
#   "hash": "a7f3b2e...",
#   "nodeType": "FnDef",
#   "verificationTier": 2,
#   "verificationDetails": [
#     {"tier": 1, "tool": "type-checker", "status": "passed"},
#     {"tier": 1, "tool": "effect-checker", "status": "passed"},
#     {"tier": 2, "tool": "contract-verifier", "status": "passed"}
#   ]
# }
```

### Semantic Search

Search the registry by type signature, effects, contracts, domain tags, or free text. Results are ranked by relevance.

```bash
# Find all pure functions that take two Ints and return an Int
curl -X POST http://localhost:8080/registry/search \
  -H "Content-Type: application/json" \
  -d '{
    "inputTypes": ["#sigil:int", "#sigil:int"],
    "outputType": "#sigil:int",
    "effects": "pure"
  }'

# Search by contract guarantees
curl -X POST http://localhost:8080/registry/search \
  -d '{"ensuresContains": ["is_sorted"], "domainTags": ["sorting"]}'

# Search by intent (fuzzy text match on aliases and descriptions)
curl -X POST http://localhost:8080/registry/search \
  -d '{"textQuery": "absolute value"}'
```

```mermaid
graph TD
    Q["Search Query"] --> SC["Scoring Engine"]

    subgraph "Scoring Factors"
        IT["Input Type Match<br/>(highest weight)"]
        OT["Output Type Match<br/>(high weight)"]
        CP["Contract Predicate Match<br/>(high boost)"]
        EF["Effects Match<br/>(medium boost)"]
        DT["Domain Tag Match<br/>(small boost)"]
        VT["Verification Tier<br/>(small boost)"]
        TQ["Text Query Match<br/>(aliases, intent)"]
    end

    SC --> IT & OT & CP & EF & DT & VT & TQ
    IT & OT & CP & EF & DT & VT & TQ --> RK["Ranked Results"]

    style Q fill:#3498db,color:#fff
    style SC fill:#f39c12,color:#fff
    style RK fill:#2ecc71,color:#fff
```

### Dependency Tracking

The registry maintains a DAG of which functions reference which other functions. When a function is deprecated or found buggy, all downstream dependents are automatically flagged for re-verification.

```mermaid
graph TD
    A["sort<br/>#a7f3b2e"] --> B["search_sorted<br/>#c4d5e6f"]
    A --> C["deduplicate<br/>#b8c9d0e"]
    B --> D["find_nearest<br/>#e1f2a3b"]
    C --> D

    A -.->|"deprecated!"| CASCADE["Cascade: re-verify<br/>B, C, D"]

    style A fill:#e74c3c,color:#fff
    style CASCADE fill:#f39c12,color:#fff
    style B fill:#3498db,color:#fff
    style C fill:#3498db,color:#fff
    style D fill:#9b59b6,color:#fff
```

```bash
# Get dependents of a function
curl http://localhost:8080/registry/node/a7f3b2e.../dependents

# Deprecate a function (triggers cascade)
curl -X POST http://localhost:8080/registry/node/a7f3b2e.../deprecate
# Response: {"deprecatedHash": "a7f3b2e...", "affectedNodes": [...], "reverificationNeeded": [...]}
```

### SMT Verification (Tier 3)

The registry includes an SMT integration that can formally prove contract correctness. Contracts are translated to SMT-LIB2 format and verified with either the built-in simple solver or an external Z3 solver.

```mermaid
flowchart LR
    FN["FnDef with Contracts"] --> ENC["SMT Encoder"]
    ENC --> SMTLIB["SMT-LIB2 Program<br/>(declare-fun x () Int)<br/>(assert (> x 0))<br/>(assert (not (> x 0)))<br/>(check-sat)"]
    SMTLIB --> SOLVER{"Solver"}
    SOLVER -->|"UNSAT"| PROVED["Contract Proved<br/>Tier 3"]
    SOLVER -->|"SAT"| COUNTER["Counterexample Found"]
    SOLVER -->|"UNKNOWN"| UNKNOWN["Too Complex<br/>Stay at Tier 2"]

    style FN fill:#9b59b6,color:#fff
    style PROVED fill:#2ecc71,color:#fff
    style COUNTER fill:#e74c3c,color:#fff
    style UNKNOWN fill:#f39c12,color:#fff
```

**Contract chaining verification** — prove that composing two functions is safe:

```kotlin
// sort ensures: result >= 0
// binary_search requires: input >= 0
// SMT proves: sort.ensures => binary_search.requires (UNSAT = valid)
val result = solver.verifyChaining(sortFn, binarySearchFn)
// result.result == SmtResult.UNSAT  (composition is provably safe)
```

### HTTP API Reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/registry/publish` | Submit source code for verification and storage |
| `POST` | `/registry/search` | Search by type signature, effects, contracts, tags |
| `GET` | `/registry/node/{hash}` | Retrieve a node by its content hash |
| `GET` | `/registry/node/{hash}/dependents` | List all dependents of a node |
| `POST` | `/registry/node/{hash}/deprecate` | Deprecate a node and cascade to dependents |
| `GET` | `/registry/stats` | Registry statistics (counts by type and tier) |
| `GET` | `/health` | Health check |

---

## AST Node Hierarchy

```mermaid
classDiagram
    class FnDef {
        +name: Alias
        +params: List~Param~
        +returnType: TypeRef
        +contract: ContractNode?
        +effects: EffectSet
        +body: ExprNode
        +hash: Hash?
    }

    class TypeDef {
        +name: Alias
        +typeParams: List~TypeVar~
        +variants: List~Variant~
        +hash: Hash?
    }

    class ExprNode {
        <<sealed>>
    }

    ExprNode <|-- Literal
    ExprNode <|-- Apply
    ExprNode <|-- Match
    ExprNode <|-- Let
    ExprNode <|-- Lambda
    ExprNode <|-- Ref
    ExprNode <|-- If
    ExprNode <|-- Block

    class Literal {
        +value: LiteralValue
    }
    class Apply {
        +fn: ExprNode
        +args: List~ExprNode~
    }
    class Match {
        +scrutinee: ExprNode
        +arms: List~MatchArm~
    }
    class Let {
        +binding: Alias
        +value: ExprNode
        +body: ExprNode
    }
    class Ref {
        +hash: Hash
    }

    class ContractNode {
        +requires: List~ContractClause~
        +ensures: List~ContractClause~
    }

    class TraitDef {
        +name: Alias
        +methods: List~FnSignature~
        +laws: List~Property~
        +hash: Hash?
    }

    class EffectDef {
        +name: Alias
        +operations: List~EffectOperation~
        +hash: Hash?
    }

    FnDef --> ExprNode : body
    FnDef --> ContractNode : contract
    FnDef --> TypeRef : returnType
```

---

## Language Syntax

Sigil uses 15 keywords and a syntax inspired by Rust/Kotlin/ML:

```
fn  type  trait  effect  module  export  handler
let  match  if  then  else  requires  ensures  property
```

### Functions

```
fn add(a: Int, b: Int) -> Int { a + b }

fn max(a: Int, b: Int) -> Int {
  if a > b then a else b
}
```

### Algebraic Data Types

```
type State {
  | Idle
  | Processing(item: String)
  | Complete(result: String)
  | Failed(error: String)
}

type Pair<A, B> {
  | Pair(first: A, second: B)
}
```

### Pattern Matching

```
fn transition(state: State, event: Event) -> State {
  match (state, event) {
    (Idle, Start(item))        => Processing(item),
    (Processing(_), Finish(r)) => Complete(r),
    (Processing(_), Error(msg)) => Failed(msg),
    (Complete(_), Reset)       => Idle,
    (Failed(_), Reset)         => Idle,
    (s, _)                     => s
  }
}
```

### Effects

```
effect Http {
  get(url: String) -> String
  post(url: String, body: String) -> String
}

fn fetchData(url: String) -> String ! Http {
  Http.get(url)
}

handler MockHttp for Http {
  get = |url| "mock response"
  post = |url, body| "mock post"
}
```

### Traits

```
trait Monoid<T> {
  fn combine(a: T, b: T) -> T
  fn empty() -> T

  property forall (a: T) => combine(a, empty()) == a
  property forall (a: T) => combine(empty(), a) == a
}
```

---

## Type System

- **Structural typing** with **Hindley-Milner inference** — types are identified by structure, not name
- **Refinement types** — base types narrowed by predicates (`Refine Int where |self| self > 0`)
- **Trait-based polymorphism** — content-addressed trait definitions
- **Generic type parameters** with bounds (`T: Comparable`)

### Primitive Types

| Type | Hash Prefix | JVM Mapping |
|---|---|---|
| `Int` | `#sigil:int` | `long` |
| `Int32` | `#sigil:i32` | `int` |
| `Int64` | `#sigil:i64` | `long` |
| `Float64` | `#sigil:f64` | `double` |
| `Bool` | `#sigil:bool` | `boolean` |
| `String` | `#sigil:string` | `java.lang.String` |
| `Unit` | `#sigil:unit` | `void` |
| `List<T>` | `#sigil:list` | `java.util.List` |
| `Map<K, V>` | `#sigil:map` | `java.util.Map` |
| `Option<T>` | `#sigil:option` | nullable / sealed class |
| `Result<T, E>` | `#sigil:result` | sealed class |

---

## JVM Code Generation

Sigil compiles to JVM bytecode via the ASM library. Compiled functions are directly callable from Kotlin/Java.

```mermaid
graph LR
    subgraph "Sigil"
        FN["FnDef"]
        TD["TypeDef (sum)"]
        TD2["TypeDef (product)"]
        PM["Pattern Match"]
        LB["Let Binding"]
        CT["Contract"]
        REF["Ref(hash)"]
    end

    subgraph "JVM"
        SM["static method"]
        SI["sealed interface + classes"]
        DC["data class"]
        IC["instanceof + cast"]
        LV["local variable"]
        AS["if(!pred) throw ContractViolation"]
        CALL["invokestatic"]
    end

    FN --> SM
    TD --> SI
    TD2 --> DC
    PM --> IC
    LB --> LV
    CT --> AS
    REF --> CALL

    style FN fill:#9b59b6,color:#fff
    style SM fill:#e74c3c,color:#fff
```

### Calling Compiled Sigil from Kotlin

```kotlin
// Compile a Sigil function
val compiler = SigilCompiler()
val result = compiler.compileSource("fn square(x: Int) -> Int { x * x }")

// Load and call it
val clazz = result.classLoader.loadClass(result.functions[0].className)
val method = clazz.getMethod("square", Long::class.java)
val answer = method.invoke(null, 7L)  // Returns 49L
```

---

## Getting Started

### Prerequisites

- **JDK 21+** (tested with OpenJDK 23)
- **Gradle** (wrapper included)

### Build

```bash
./gradlew build
```

### Run Tests

```bash
# All tests (compiler + registry)
./gradlew test

# Compiler only
./gradlew :compiler:test

# Registry only
./gradlew :registry:test
```

**183 tests** across 14 test suites:

#### Compiler (104 tests)

| Suite | Tests | Coverage |
|---|---|---|
| `TypeCheckerTest` | 29 | HM inference, pattern matching, generics, error cases |
| `ParserTest` | 24 | Lexer, all grammar constructs, spec examples |
| `IntegrationTest` | 13 | Full end-to-end pipeline: parse, check, hash, compile, execute |
| `JvmCodegenTest` | 9 | Bytecode generation, arithmetic, control flow, contracts |
| `ContractVerifierTest` | 9 | requires/ensures, contract chaining, severity levels |
| `Blake3Test` | 8 | Hash correctness, known test vectors, determinism |
| `HasherTest` | 6 | Canonical serialization, name-independence, stability |
| `EffectCheckerTest` | 6 | Effect tracking, propagation, handler removal |

#### Registry (79 tests)

| Suite | Tests | Coverage |
|---|---|---|
| `RegistryStoreTest` | 16 | InMemoryStore CRUD, verification updates, dependency tracking |
| `SmtTest` | 16 | SMT-LIB2 encoding, contract verification, chaining, edge cases |
| `SemanticTest` | 13 | Signature extraction, type/effect/contract search, ranking |
| `DependencyTest` | 10 | Transitive deps, deprecation cascade, cycle detection |
| `ApiTest` | 9 | Ktor HTTP endpoints: publish, search, get, stats, errors |
| `IntegrationTest` | 15 | End-to-end: publish, search, compose, deprecate, SMT roundtrip |

### Run the Registry Server

```bash
# Start with in-memory store (default)
./gradlew :registry:run

# Start with MongoDB
MONGO_URI=mongodb://localhost:27017 ./gradlew :registry:run

# Custom port
SIGIL_PORT=9090 ./gradlew :registry:run
```

### Use as a Library

```kotlin
import sigil.api.SigilCompiler

val compiler = SigilCompiler()

// Option 1: Compile from source text
val result = compiler.compileSource("""
    fn add(a: Int, b: Int) -> Int { a + b }
""")

// Option 2: Compile from AST (for agent use)
import sigil.ast.*

val fn = FnDef(
    name = "add",
    params = listOf(
        Param("a", TypeRef(PrimitiveTypes.INT)),
        Param("b", TypeRef(PrimitiveTypes.INT))
    ),
    returnType = TypeRef(PrimitiveTypes.INT),
    body = ExprNode.Apply(
        fn = ExprNode.Ref("#sigil:add"),
        args = listOf(ExprNode.Ref("a"), ExprNode.Ref("b"))
    )
)

val compiled = compiler.compileFn(fn)
println("Hash: ${compiled.hash}")
println("Class: ${compiled.className}")
```

### Use the Registry Programmatically

```kotlin
import sigil.registry.api.*
import sigil.registry.store.InMemoryStore

val store = InMemoryStore()
val service = RegistryService(store)

// Publish a function
val responses = service.publish(PublishRequest(
    source = "fn double(x: Int) -> Int { x * 2 }",
    metadata = PublishMetadata(
        aliases = listOf("times_two"),
        intent = "doubles an integer",
        domainTags = listOf("math")
    )
))
println("Published: ${responses[0].hash}")

// Search for it
val results = service.search(SearchRequest(
    outputType = "#sigil:int",
    domainTags = listOf("math")
))
println("Found: ${results.size} results")
```

---

## Project Structure

```
sigil/
  build.gradle.kts                     # Root build (Kotlin 2.1.10, multi-module)
  settings.gradle.kts                  # Includes compiler + registry modules

  compiler/                            # Phase 1: Compiler
    src/main/kotlin/sigil/
      ast/                             # AST node data classes (12 files)
        ExprNode.kt                    #   Expression nodes (sealed class, 8 variants)
        FnDef.kt                       #   Function definition
        TypeDef.kt                     #   Algebraic data types
        ContractNode.kt                #   requires/ensures contracts
        EffectDef.kt                   #   Effect declarations
        TraitDef.kt                    #   Trait definitions
        ModuleDef.kt                   #   Module definitions
        TypeRef.kt                     #   Type references & type variables
        RefinementType.kt              #   Refinement types
        EffectHandler.kt               #   Effect handler implementations
        Types.kt                       #   Primitive type constants
        Metadata.kt                    #   SemanticMeta, VerificationRecord
      hash/                            # Content-addressing
        Blake3.kt                      #   Pure Kotlin Blake3 implementation
        Hasher.kt                      #   Canonical AST serialization
      types/                           # Type system
        TypeChecker.kt                 #   Hindley-Milner type inference
        Unifier.kt                     #   Constraint unification (Algorithm W)
        TraitResolver.kt               #   Trait bound checking
      contracts/                       # Contract system
        ContractVerifier.kt            #   Contract validation + chaining
        PropertyTester.kt              #   Property-based testing (QuickCheck-style)
      effects/                         # Effect system
        EffectChecker.kt               #   Effect tracking + propagation
      codegen/jvm/                     # JVM backend
        JvmCodegen.kt                  #   ASM bytecode generation
        JvmLinker.kt                   #   ClassLoader for compiled code
        LocalVarTable.kt               #   Local variable index tracking
        ContractViolation.kt           #   Runtime exception for contract failures
      parser/                          # Text syntax
        SigilLexer.kt                  #   Tokenizer
        SigilParser.kt                 #   Recursive descent parser
      api/                             # Entry points
        SigilCompiler.kt               #   Unified compilation pipeline
        Main.kt                        #   CLI entry point
    src/test/kotlin/sigil/             # 104 tests

  registry/                            # Phase 2: Registry Infrastructure
    src/main/kotlin/sigil/registry/
      store/                           # Content-addressed storage
        RegistryStore.kt               #   Storage interface
        InMemoryStore.kt               #   HashMap-based (testing/dev)
        MongoStore.kt                  #   MongoDB backend (production)
        RegistryNode.kt                #   RegistryNode, NodeMetadata, SemanticSignature
      semantic/                        # Semantic indexing & search
        SemanticExtractor.kt           #   Extract signatures from AST nodes
        SemanticSearch.kt              #   SearchQuery, SearchResult, scoring engine
      deps/                            # Dependency tracking
        DependencyTracker.kt           #   DAG traversal, cascade deprecation
      smt/                             # SMT verification
        SmtEncoder.kt                  #   Contract → SMT-LIB2 translation
        SmtSolver.kt                   #   SimpleSmtSolver, Z3SmtSolver
      api/                             # HTTP API
        ApiModels.kt                   #   Request/response data classes
        RegistryService.kt             #   Business logic (orchestrates all components)
        RegistryRoutes.kt              #   Ktor route definitions
        Main.kt                        #   Server startup (Netty)
    src/test/kotlin/sigil/registry/    # 79 tests
```

---

## Verification Tiers

Every compiled node receives a verification tier that represents the level of trust:

```mermaid
graph BT
    T1["Tier 1: TypeChecked<br/>Compiles, types align"]
    T2["Tier 2: PropertyTested<br/>Contracts present and validated"]
    T3["Tier 3: FormallyProved<br/>SMT solver confirms all contracts"]

    T1 --> T2 --> T3

    style T1 fill:#e74c3c,color:#fff
    style T2 fill:#f39c12,color:#fff
    style T3 fill:#2ecc71,color:#fff
```

| Tier | Name | Requirement | Status |
|---|---|---|---|
| 1 | TypeChecked | Compiles, types align, effects verified | Implemented |
| 2 | PropertyTested | Contracts present and validated | Implemented |
| 3 | FormallyProved | SMT solver confirms all contracts | Implemented |

---

## Roadmap

```mermaid
gantt
    title Sigil Implementation Phases
    dateFormat YYYY-MM
    axisFormat %b %Y

    section Phase 1 — Compiler
    AST Data Structures           :done, 2026-03, 2026-03
    Blake3 Hashing                :done, 2026-03, 2026-03
    Type Checker (HM)             :done, 2026-03, 2026-03
    Contract Verifier             :done, 2026-03, 2026-03
    Effect Checker                :done, 2026-03, 2026-03
    JVM Codegen (ASM)             :done, 2026-03, 2026-03
    Text Parser                   :done, 2026-03, 2026-03
    Integration Tests             :done, 2026-03, 2026-03

    section Phase 2 — Registry
    Content-Addressed Storage     :done, 2026-03, 2026-03
    Semantic Signature Indexing   :done, 2026-03, 2026-03
    Dependency Tracking DAG       :done, 2026-03, 2026-03
    SMT Integration               :done, 2026-03, 2026-03
    Ktor HTTP API                 :done, 2026-03, 2026-03
    Integration Tests             :done, 2026-03, 2026-03

    section Phase 3 — Production
    Kotlin Interop Layer          :2026-04, 2026-06
    WASM Backend                  :2026-05, 2026-07
    Production Dogfooding         :2026-06, 2026-09
```

### What's Implemented

**Phase 1 — Compiler:**
- Full AST node type system with `@Serializable` annotations
- Pure Kotlin Blake3 implementation (256-bit, deterministic)
- Canonical AST serialization for content-addressing
- Hindley-Milner type inference (Algorithm W)
- Algebraic effect system with propagation
- Contract verification with chaining detection
- JVM bytecode generation (arithmetic, control flow, pattern matching, contracts)
- Recursive descent parser for the full text syntax
- Unified `SigilCompiler` pipeline API

**Phase 2 — Registry:**
- Content-addressed storage with InMemoryStore and MongoStore backends
- Semantic signature extraction from FnDef, TypeDef, and TraitDef
- Type-aware search with multi-factor scoring (type match, contracts, effects, tags, text)
- Dependency DAG with transitive traversal and cascade deprecation
- SMT-LIB2 encoder for contract-to-solver translation
- Built-in SimpleSmtSolver for basic arithmetic/boolean proofs
- Z3SmtSolver integration (shells out to z3 CLI with fallback)
- Contract chaining verification (proves composition safety)
- Ktor HTTP API with publish, search, get, deprecate, and stats endpoints
- CORS, content negotiation, and error handling middleware

### What's Planned

- **Phase 3:** Kotlin FFI, WASM/LLVM backends, production deployment in backend services

---

## Design Principles

1. **AST-native, not text-native.** The canonical representation is a typed AST. Text is a rendering.
2. **Content-addressed identity.** Structure is identity. Names are aliases. No naming conflicts.
3. **Verification as a first-class layer.** Contracts are part of the hash. Unverified code cannot enter the registry.
4. **Effects are declared, not hidden.** Every side effect must be in the function signature.
5. **Target-agnostic persistence.** The verified AST is permanent. Compilation targets are pluggable.
6. **Agent-first ergonomics.** Designed for what agents need: structural clarity, unambiguous semantics, rich metadata.
7. **Knowledge compounds.** Every verified function published to the registry makes every future agent more capable.

---

## Contributing

Sigil is designed to be built *by* agents, *for* agents. The agent contribution protocol is:

1. Construct an AST node (as JSON or Kotlin objects) or write Sigil source text
2. Submit to the registry via HTTP API or programmatic `RegistryService`
3. The full compiler pipeline runs: parse, type check, effect check, contract verify
4. If all pass: Blake3 hash is computed, semantic signature extracted, dependencies tracked
5. The node is stored and becomes searchable by all agents
6. SMT solver runs for Tier 3 formal proof upgrade

---

## License

MIT
