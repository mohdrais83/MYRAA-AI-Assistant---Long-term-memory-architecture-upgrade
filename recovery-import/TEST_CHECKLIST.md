# MYRAA memory engine — verification checklist

Run only after integration in a separate test workspace.

- [ ] Existing valid memories load with IDs and timestamps unchanged.
- [ ] Missing `memories.json` returns an empty list.
- [ ] Invalid JSON and non-array roots produce errors without modifying the file.
- [ ] Invalid records and duplicate IDs are rejected.
- [ ] New memories receive UUIDs and ISO timestamps.
- [ ] Duplicate normalized text in the same category is not re-added.
- [ ] Identical text in a different category is allowed.
- [ ] Delete removes exactly the requested record; missing IDs return API 404.
- [ ] Unknown UPDATE IDs never create new records.
- [ ] Concurrent POST/delete requests do not overwrite one another in one process.
- [ ] Restarting MYRAA preserves memories.
- [ ] Gemini extraction failure leaves existing data unchanged.
- [ ] UI list/add/delete still work through the existing API.
- [ ] Voice/live session still receives memory instructions.
- [ ] Desktop-agent, browser, voice, and settings behavior remains intact.
