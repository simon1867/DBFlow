# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DBFlow is an annotation-processing ORM for Android/SQLite. All SQL boilerplate is generated at compile-time by `dbflow-processor`; the runtime has near-zero reflection overhead. Models can be plain POJOs or extend `BaseModel`, and multiple SQLite databases per app are supported natively.

## Module Organization

| Module | Type | Purpose |
|--------|------|---------|
| `dbflow-core` | Java library | Core annotations (`@Database`, `@Table`, `@Column`, `@ForeignKey`, etc.) and interfaces |
| `dbflow` | Android library | Runtime: `FlowManager`, query DSL, model adapters, transactions |
| `dbflow-processor` | Java library | Annotation processor (`DBFlowProcessor.kt`) and all code generation |
| `dbflow-sqlcipher` | Android library | SQLCipher encryption support |
| `dbflow-kotlinextensions` | Android library | Kotlin DSL and extension functions |
| `dbflow-rx` / `dbflow-rx2` | Android library | RXJava 1 & 2 integration |
| `dbflow-rx-kotlinextensions` / `dbflow-rx2-kotlinextensions` | Android library | Kotlin extensions for RX modules |
| `dbflow-tests` | Android app | All tests (unit via Robolectric + instrumented) |

**SDK/tool versions** (gradle.properties): minSdk 21, targetSdk 34, Kotlin 1.9.24, AGP 8.2.2, Java 8.

## Architecture

### Annotation Processing Pipeline

`DBFlowProcessor` (extends `AbstractProcessor`) runs during `compileKotlin`/`compileJava`. For each annotated class it generates:

- `${Model}_Table` — static column name constants
- `${Model}_Adapter` — handles cursor loading, insert, update, delete, caching
- `${Database}_HolderImpl` — registry of all tables for a given `@Database`

Generated sources land in `build/generated/ap_generated_sources/`. Any annotation error fails the build entirely.

### FlowManager — Central Registry

`FlowManager` is the single entry point at runtime. It loads the generated `GeneratedDatabaseHolder` via a single reflection call during `FlowManager.init(FlowConfig)`. After that, all operations go through type-safe generated adapters — no further reflection. Required initialization:

```kotlin
FlowManager.init(FlowConfig.Builder(context).build())
```

### Query DSL

Queries are built lazily and executed only when `.query()`, `.querySingle()`, or similar terminal methods are called:

```kotlin
select(User_Table.name, User_Table.email)
    .from(User::class.java)
    .where(User_Table.id.eq(42))
    .querySingle()
```

Models serve double duty as table references. Column constants come from the generated `${Model}_Table` class.

### Multiple Databases

Each `@Database`-annotated class is a separate SQLite file. Models declare their database via `@Table(database = MyDatabase::class)`. Migrations are scoped per database version.

## Build Commands

```bash
# Full build
./gradlew build

# Skip tests
./gradlew build -x test -x connectedAndroidTest

# Single module
./gradlew :dbflow-processor:build

# Clean
./gradlew clean build
```

## Test Commands

All tests live in `:dbflow-tests`. Unit tests use Robolectric (no emulator needed); instrumented tests require a connected device.

```bash
# All unit tests
./gradlew :dbflow-tests:test

# Single test class
./gradlew :dbflow-tests:testDebugUnitTest --tests "*.ConfigIntegrationTest"

# Single test method
./gradlew :dbflow-tests:testDebugUnitTest --tests "*.ConfigIntegrationTest.testSimpleConfig"

# Instrumented tests (device/emulator required)
./gradlew :dbflow-tests:connectedAndroidTest
```

Custom test rules: `DBFlowTestRule` (unit), `DBFlowInstrumentedTestRule` (instrumented). Base class `BaseUnitTest` sets up a test database and context.

## Lint

```bash
./gradlew lint           # All modules (abortOnError = false)
./gradlew :dbflow:lint   # Single module
```

## KSP Migration (current branch: `ksp-support`)

KSP migration is complete. `dbflow-tests/build.gradle` has KSP enabled and KAPT disabled. `DBFlowSymbolProcessor` implements `SymbolProcessor` and handles all annotation types. All 164 unit tests pass.

**Key KSP implementation notes:**
- `DBFlowSymbolProcessor` runs two rounds: round 1 processes all annotations and writes non-holder files; if `@ManyToMany` join tables were generated, round 2 picks them up and writes the database registry + holder.
- Database registry files (`${Database}_Database.java`) are written **after** all table adapters so that `hasGlobalTypeConverters` is accurate (it's populated during `checkNeedsReferences()` in `onWriteDefinition`).
- KSP type mapping lives in `KspExtensions.kt` — Kotlin primitives, arrays (`ByteArray` → `byte[]`), and class types all have explicit mappings.
- `@MultiCacheField`/`@ModelCacheField` fields live in companion objects; the KSP path scans companion object declarations explicitly.
- `@OneToMany` methods are scanned in `createColumnDefinitionsFromKsp` via `KSFunctionDeclaration`; `OneToManyDefinition.kspInit()` initializes from KSP data.
- Java-origin fields (e.g. generated join table classes) use `VisibleScopeColumnAccessor` directly; Kotlin-origin fields without `@JvmField` use `PrivateScopeColumnAccessor` with getter/setter generation.

## Key Files for Architecture

- `dbflow/src/main/java/com/raizlabs/android/dbflow/config/FlowManager.java` — central registry
- `dbflow/src/main/java/com/raizlabs/android/dbflow/structure/BaseModel.java` — base model implementation
- `dbflow-processor/src/main/java/com/raizlabs/android/dbflow/processor/DBFlowProcessor.kt` — main annotation processor entry point
- `dbflow-core/src/main/java/com/raizlabs/android/dbflow/annotation/` — all annotations
