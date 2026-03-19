# Contributing to Sigil

## Prerequisites

- **JDK 23+** (tested with OpenJDK 23)
- **Gradle** (wrapper included -- use `./gradlew`)

## Building

```bash
./gradlew build
```

## Running Tests

```bash
# All modules
./gradlew test

# Per-module
./gradlew :compiler:test
./gradlew :registry:test
./gradlew :examples:test
```

## Project Structure

```
sigil/
  compiler/    Core language compiler (parser, type checker, codegen)
  registry/    Knowledge-sharing registry (storage, search, HTTP API)
  examples/    Dogfooding examples and integration tests
```

### Compiler (`compiler/`)

| Directory | Purpose |
|---|---|
| `ast/` | AST node data classes (FnDef, TypeDef, ExprNode, etc.) |
| `parser/` | Lexer and recursive descent parser |
| `types/` | Hindley-Milner type inference (Algorithm W) |
| `effects/` | Algebraic effect checker |
| `contracts/` | Contract verifier and property tester |
| `hash/` | Blake3 hasher and canonical serialization |
| `codegen/jvm/` | ASM bytecode generation |
| `interop/` | Kotlin interop layer (SigilModule) |
| `api/` | Unified compiler pipeline and CLI |

### Registry (`registry/`)

| Directory | Purpose |
|---|---|
| `store/` | Content-addressed storage (InMemory, MongoDB) |
| `semantic/` | Semantic signature extraction and search |
| `deps/` | Dependency DAG and cascade deprecation |
| `smt/` | SMT-LIB2 encoder and solver integration |
| `api/` | Ktor HTTP API (publish, search, get, deprecate) |

### Examples (`examples/`)

Dogfooding demonstrations of Sigil called from Kotlin via `SigilModule`.

## Architecture

The compilation pipeline processes source code through six stages:

```
Source Text --> Lexer --> Parser --> Type Checker --> Effect Checker
  --> Contract Verifier --> Blake3 Hasher --> JVM Codegen
```

Each stage is blocking -- a failure at any stage prevents progression. The verified AST with its content hash is the canonical artifact; bytecode is generated on demand.

## Key Concepts

- **Content-addressed identity**: Every definition is identified by its Blake3 hash. Names are aliases excluded from the hash, so structurally identical functions produce the same hash regardless of naming.
- **Structural equality**: Two independently-authored functions with the same params, return type, contracts, effects, and body are considered identical.
- **Contracts**: `requires` (preconditions) and `ensures` (postconditions) are part of the function's identity. The compiler can prove composition safety when one function's ensures satisfies another's requires.
- **Effects**: Side effects are declared in function signatures with `!` syntax (e.g., `fn fetch() -> String ! Http`). Pure functions are the default. Effect handlers provide implementations.

## Code Style

- **Language**: Kotlin (version 2.1.10)
- **No wildcard imports** -- use explicit imports
- **Tests**: JUnit 5 with `@Test` annotations
- **Naming**: Standard Kotlin conventions (camelCase for functions/properties, PascalCase for classes)
- **Formatting**: Follow existing patterns in the codebase

## Pull Request Process

1. Fork the repository
2. Create a feature branch from `main`
3. Make your changes
4. Ensure all tests pass: `./gradlew test`
5. Submit a PR against `main`

### What to include in a PR

- A clear description of the change and its motivation
- Tests for new functionality
- No unrelated changes or reformatting

### Verification tiers

Code entering the registry is assigned a verification tier:

| Tier | Name | Requirement |
|---|---|---|
| 1 | TypeChecked | Compiles, types align, effects verified |
| 2 | PropertyTested | Contracts present and validated |
| 3 | FormallyProved | SMT solver confirms all contracts |

## Running the Registry

```bash
# In-memory store (default, for development)
./gradlew :registry:run

# With MongoDB
MONGO_URI=mongodb://localhost:27017 ./gradlew :registry:run

# Custom port
SIGIL_PORT=9090 ./gradlew :registry:run
```

## License

MIT
