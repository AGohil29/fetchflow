# Architecture Overview

This document ties together all the modules in `fetchflow` — what each one owns, how
they depend on each other, and the end-to-end flow of a single download. It's the starting point
for understanding the codebase; module-specific docs go deeper on individual pieces:

- [`fetchflow-network` module](../networking/fetchflow-network-module.md)
- [`DownloadStateMachine` tests](../testing/download-state-machine.md)
- [`DownloadTask` engine](../engine/download-task.md)
- [`fetchflow-runtime` module](../runtime/fetchflow-runtime-module.md)

## Module map

```
fetchflow
├── fetchflow-core         (domain models, state machine, DownloadTask engine, interfaces)
├── fetchflow-network      (Ktor-based NetworkTransport implementation)
├── fetchflow-filesystem   (Okio-based DownloadFileSystem implementation)
├── fetchflow-storage      (SQLDelight-based DownloadRepository implementation)
├── fetchflow-runtime      (multiplatform integration tests wiring core+network+filesystem+storage)
└── samples
    ├── sample-desktop      (Compose Desktop app for manual end-to-end testing)
    └── sample-android      (Compose Android app; see docs/samples/android-sample.md)
```

### Dependency direction

```mermaid
graph TD
    runtime[fetchflow-runtime] --> core[fetchflow-core]
    runtime --> network[fetchflow-network]
    runtime --> filesystem[fetchflow-filesystem]
    runtime --> storage[fetchflow-storage]

    network --> core
    filesystem --> core
    storage --> core
```

`fetchflow-core` is the innermost layer — it has **no** dependency on network, filesystem, or
storage. It only defines:

- **Domain models**: `DownloadRequest`, `DownloadRecord`, `DownloadState`, `DownloadError`,
  `DownloadId`, `DownloadPriority`, `ConflictStrategy`, `ChecksumValidation`,
  `NetworkRequest`/`NetworkResponse`.
- **Interfaces (ports)**: `DownloadRepository`, `DownloadFileSystem`, `NetworkTransport` — each
  implemented by a dedicated module (`fetchflow-storage`, `fetchflow-filesystem`,
  `fetchflow-network` respectively). This is a ports-and-adapters (hexagonal) design: the engine
  only ever talks to interfaces, never to Ktor/Okio/SQLDelight directly.
- **The state machine**: `DownloadStateMachine` / `DownloadStateEvent` — a pure, dependency-free
  state container.
- **The engine**: `DownloadTask` — orchestrates a single download's lifecycle by driving the state
  machine and calling the three interfaces above. See
  [`docs/engine/download-task.md`](../engine/download-task.md) for a deep dive.

`fetchflow-network`, `fetchflow-filesystem`, and `fetchflow-storage` are adapters: each
depends on `fetchflow-core` (to implement its interfaces / use its models) but not on each
other, and `fetchflow-core` never depends on them — keeping the dependency graph acyclic and
each adapter independently testable/swappable.

`fetchflow-runtime` sits on top of all four and previously existed as three near-empty,
platform-specific shells (`fetchflow-runtime-android`, `fetchflow-runtime-desktop`,
`fetchflow-runtime-ios`). They have been consolidated into a single `fetchflow-runtime` module.
See [`docs/runtime/fetchflow-runtime-module.md`](../runtime/fetchflow-runtime-module.md) for
details on that consolidation.

## End-to-end flow of a single download

```mermaid
sequenceDiagram
    participant Caller
    participant DownloadTask
    participant SM as DownloadStateMachine
    participant Repo as DownloadRepository (storage)
    participant Net as NetworkTransport (network)
    participant FS as DownloadFileSystem (filesystem)

    Caller->>Repo: insertOrUpdate(id, request, "QUEUED")
    Caller->>DownloadTask: execute()
    activate DownloadTask

    DownloadTask->>SM: transition(Enqueue)
    SM-->>DownloadTask: Queued

    DownloadTask->>SM: transition(AcquireSlot)
    SM-->>DownloadTask: Connecting
    DownloadTask->>Repo: updateState(CONNECTING)

    DownloadTask->>Net: execute(NetworkRequest)
    Net-->>DownloadTask: NetworkResponse (streaming body)

    DownloadTask->>SM: transition(HeaderReceived)
    SM-->>DownloadTask: Downloading
    DownloadTask->>Repo: updateConnectionHeaders(totalBytes, etag, lastModified)

    DownloadTask->>FS: sink(stagingPartFile)
    loop pump bytes
        DownloadTask->>Net: read chunk from bodyChannel
        DownloadTask->>FS: write chunk to staging sink
        DownloadTask->>SM: transition(ProgressUpdate) (throttled)
        DownloadTask->>Repo: updateProgress(downloadedBytes)
    end

    DownloadTask->>SM: transition(StartValidation)
    SM-->>DownloadTask: Validating
    DownloadTask->>DownloadTask: verify byte count / checksum

    DownloadTask->>FS: atomicMove(staging -> destination)
    DownloadTask->>SM: transition(Complete)
    SM-->>DownloadTask: Completed
    DownloadTask->>Repo: markCompleted(id, finalPath)
    deactivate DownloadTask
```

### Cancellation / pause path

If the caller cancels the coroutine running `execute()` (e.g. user pauses the download) mid
stream, `DownloadTask` catches `CancellationException` and performs cleanup **inside a
`NonCancellable` context** — this is important, see
[Cancellation and `NonCancellable` cleanup](../engine/download-task.md#cancellation-and-noncancellable-cleanup):

```mermaid
sequenceDiagram
    participant Caller
    participant DownloadTask
    participant SM as DownloadStateMachine
    participant Repo as DownloadRepository

    Caller->>DownloadTask: job.cancel()
    DownloadTask->>DownloadTask: CancellationException thrown from pumpBytes()
    DownloadTask->>DownloadTask: withContext(NonCancellable) { ... }
    DownloadTask->>SM: transition(Pause)
    SM-->>DownloadTask: Paused(downloadedBytes, totalBytes)
    DownloadTask->>Repo: updateState(id, "PAUSED")
    DownloadTask-->>Caller: rethrows CancellationException
```

### Failure path

Any other exception (`DownloadError` or unexpected `Throwable`) is caught, wrapped as a
`DownloadError` if necessary, and drives a `Fail` transition instead:

```mermaid
sequenceDiagram
    participant DownloadTask
    participant SM as DownloadStateMachine
    participant Repo as DownloadRepository

    DownloadTask->>DownloadTask: catch (e: Throwable)
    DownloadTask->>SM: transition(Fail(error, canRetry))
    SM-->>DownloadTask: Failed(error, canRetry, downloadedBytes)
    DownloadTask->>Repo: markFailed(id, error)
```

## State machine reference

The full state diagram and transition table are documented in
[`docs/testing/download-state-machine.md`](../testing/download-state-machine.md). The key rule
that `DownloadTask` must respect (and previously violated — see the engine doc) is that every
lifecycle **must start with `Enqueue` (`Idle -> Queued`) before `AcquireSlot` (`Queued ->
Connecting`)** — states cannot be skipped.

## Testing strategy across modules

| Module               | What's tested                                                                                                                                                                                                                   | Where                                |
|----------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------|
| `fetchflow-core`    | `DownloadStateMachine` transition rules (pure, no I/O)                                                                                                                                                                          | `fetchflow-core/src/commonTest`     |
| `fetchflow-network` | HTTP request/response mapping via `MockEngine` (no real network)                                                                                                                                                                | `fetchflow-network/src/commonTest`  |
| `fetchflow-storage` | SQLDelight queries against an in-memory SQLite DB (incl. headers/checksum round trip); upgrade of a legacy on-disk DB (`DatabaseDriverFactoryMigrationTest`); migrations `1.sqm`/`2.sqm` checked by `verifySqlDelightMigration` | `fetchflow-storage/src/desktopTest` |
| `fetchflow-runtime` | Full `DownloadTask` integration: real state machine + real repository (in-memory SQLite) + fake filesystem (`okio.fakefilesystem.FakeFileSystem`) + fake `NetworkTransport`                                                     | `fetchflow-runtime/src/desktopTest` |

`fetchflow-runtime`'s tests are the only ones that exercise the complete, wired-together engine
end-to-end (minus real network/disk I/O, which are faked), which is why `DownloadTaskTest` lives
there instead of in `fetchflow-core` — it depends on concrete implementations from all three
adapter modules.
