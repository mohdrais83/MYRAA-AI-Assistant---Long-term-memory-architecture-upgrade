# MYRAA recovered source bundle

This archive contains source/configuration/data files recovered from the user's ChatGPT Library on 2026-11-21. It is a recovery collection, NOT a verified complete or directly buildable project. Files are kept mostly at the archive root to preserve their recovered names; restore them into the original project layout only after comparing imports and the original repository.

## Recovered groups
- Backend: `server.ts`, `server_memory.ts`, `server_memory.hardened.ts`, `server_paths.ts`
- Node/Electron config: `package.json`, `electron-builder.yml`, `main (1).cjs`
- React/UI: `App.tsx`, `audio.ts`, `MemoryDashboard.tsx`, `SettingsPanel.tsx`, `settingsStore.ts`, `memoryTypes.ts`
- Python agent: `main.py`, `registry.py`, `run_agent.py`, `requirements.txt`, `desktop_agent.spec`, and recovered `tools_*.py`
- Data/docs: `memories.json`, `settings.json`, `INTEGRATION_GUIDE.md`, `TEST_CHECKLIST.md`, and prior MYRAA chat-history export

## Known gaps and layout issues
- `server.ts` imports `./src/lib/memoryTypes`, but the recovered `memoryTypes.ts` was uploaded at the Library root. The import path must be reconciled with the actual project layout before building.
- `App.tsx` imports modules under `./lib/` and `./components/`, including `components/MyraaCoreVisualizer`, `components/BrowserAgent`, and `lib/wakeWord`; those source files were not present in the recovered set.
- `registry.py` expects additional Python modules such as `tools_windows`, `tools_system`, and `tools_startup`; these were not present in the recovered set. Some imports may therefore fail.
- Several files are duplicates with numbered names. The package keeps the primary copies and selected hardened memory implementation separately; do not overwrite one with another without diffing.
- The `.exe`, `.pyc`, `.toc`, and `.pyz` artifacts found in the Library are compiled/build artifacts, not substitutes for missing editable source. They are not included here.

## Safe next step
Upload/extract this archive into a separate recovery directory or branch. Do not overwrite the synchronized `main` branch. Reconstruct the expected directory layout and resolve missing imports before running install/build commands. Keep secrets and local settings out of Git.
