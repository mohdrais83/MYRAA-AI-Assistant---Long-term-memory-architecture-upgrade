# MYRAA memory upgrade — integration guide

This package is based on the uploaded `server_memory.ts`, `server.ts`, `memoryTypes.ts`, and `server_paths.ts`.

## Compatibility

The replacement keeps the existing `Memory` shape (`id`, `category`, `text`, `createdAt`, `updatedAt`) and the existing exports used by `server.ts`: `loadMemories`, `saveMemories`, `formatSystemInstructionsWithMemories`, and `processConversationSlice`.

## Verify the type-file path first

The project server imports `./src/lib/memoryTypes`. The supplied type file was named `memoryTypes.ts`. Before using the replacement, verify that the repository actually contains `src/lib/memoryTypes.ts`. If it is elsewhere, correct the relative import in `server_memory.hardened.ts`; do not create duplicate type definitions just to silence an import error.

## Required `server.ts` changes

Add `addMemory` and `deleteMemory` to the existing imports from `./server_memory`:

```ts
import {
  loadMemories,
  saveMemories,
  addMemory,
  deleteMemory,
  formatSystemInstructionsWithMemories,
  processConversationSlice
} from "./server_memory";
```

Replace the existing `POST /api/memories` handler with:

```ts
app.post("/api/memories", async (req, res) => {
  try {
    const { category, text } = req.body ?? {};
    const allowed = ["identity", "preference", "goal", "project", "relationship", "emotional", "behavior"];
    if (!allowed.includes(category) || typeof text !== "string" || !text.trim()) {
      return res.status(400).json({ error: "A valid category and non-empty text are required." });
    }
    const memory = await addMemory(category, text);
    res.status(201).json(memory);
  } catch (e: any) {
    res.status(500).json({ error: e.message });
  }
});
```

Replace the existing `DELETE /api/memories/:id` handler with:

```ts
app.delete("/api/memories/:id", async (req, res) => {
  try {
    const removed = await deleteMemory(req.params.id);
    if (!removed) return res.status(404).json({ error: "Memory not found." });
    res.json({ success: true });
  } catch (e: any) {
    res.status(500).json({ error: e.message });
  }
});
```

The existing GET handler can remain unchanged.

## Improvements

- Corrupt JSON is reported and preserved, not silently interpreted as an empty memory list.
- Records and IDs are validated.
- Writes use a temporary file followed by rename.
- Read/modify/write operations are serialized within one Node process.
- Duplicate normalized text in the same category is not re-added.
- Unknown UPDATE/REMOVE IDs are ignored; UPDATE never silently becomes ADD.
- New IDs use `randomUUID()`.

## Limitations and rollout requirements

1. The queue is process-local, not a cross-process lock. Do not run multiple MYRAA backend processes against the same JSON file.
2. Verify rename behavior on the actual Windows runtime and target filesystem.
3. Gemini extraction can make semantic mistakes. Ambiguous or sensitive memory proposals should eventually have user review/consent controls.
4. JSON is an incremental hardening step, not the final high-scale semantic-memory architecture. A later migration should use SQLite, schema migrations, indexes/FTS, and provenance/confidence/consent metadata.
5. No GitHub repository was changed, and no lint, build, runtime, Windows, or data-migration test has been run.

Safe rollout: resolve the Git branch divergence; verify the type path; back up `memories.json` and `settings.json`; apply the replacement and route changes; run `npm run lint` and `npm run build`; test with a separate temporary `MYRAA_DATA_DIR`; then test the packaged Windows app. Never experiment on the only live memory file.
