import fs from "fs/promises";
import path from "path";
import { randomUUID } from "crypto";
import { GoogleGenAI, Type } from "@google/genai";
import type { Memory, MemoryTransaction, MemoryCategory } from "./src/lib/memoryTypes";
import { dataFile } from "./server_paths";

const MEMORY_FILE = dataFile("memories.json");
const CATEGORIES: readonly MemoryCategory[] = ["identity", "preference", "goal", "project", "relationship", "emotional", "behavior"];
let fileQueue: Promise<unknown> = Promise.resolve();
let isConsolidating = false;

function serialized<T>(operation: () => Promise<T>): Promise<T> {
  const next = fileQueue.then(operation, operation);
  fileQueue = next.then(() => undefined, () => undefined);
  return next;
}
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function validateMemory(value: unknown, index: number): Memory {
  if (!isRecord(value)) throw new Error(`Memory record ${index} is not an object.`);
  const { id, category, text, createdAt, updatedAt } = value;
  if (typeof id !== "string" || !id.trim()) throw new Error(`Memory record ${index} has an invalid id.`);
  if (typeof category !== "string" || !CATEGORIES.includes(category as MemoryCategory)) throw new Error(`Memory record ${index} has an invalid category.`);
  if (typeof text !== "string" || !text.trim()) throw new Error(`Memory record ${index} has empty text.`);
  if (typeof createdAt !== "string" || typeof updatedAt !== "string") throw new Error(`Memory record ${index} has invalid timestamps.`);
  return { id: id.trim(), category: category as MemoryCategory, text: text.trim(), createdAt, updatedAt };
}
async function readFileUnsafe(): Promise<Memory[]> {
  let raw: string;
  try { raw = await fs.readFile(MEMORY_FILE, "utf8"); }
  catch (error: any) { if (error?.code === "ENOENT") return []; throw error; }
  let parsed: unknown;
  try { parsed = JSON.parse(raw); }
  catch (error) { throw new Error(`Memory file contains invalid JSON and was left untouched: ${String(error)}`); }
  if (!Array.isArray(parsed)) throw new Error("Memory file must contain a JSON array; file was left untouched.");
  const memories = parsed.map(validateMemory);
  const ids = new Set<string>();
  for (const memory of memories) { if (ids.has(memory.id)) throw new Error(`Memory file contains duplicate ID: ${memory.id}`); ids.add(memory.id); }
  return memories;
}
async function writeFileUnsafe(memories: Memory[]): Promise<void> {
  const validated = memories.map(validateMemory);
  const ids = new Set<string>();
  for (const memory of validated) { if (ids.has(memory.id)) throw new Error(`Duplicate memory id rejected: ${memory.id}`); ids.add(memory.id); }
  await fs.mkdir(path.dirname(MEMORY_FILE), { recursive: true });
  const temporary = `${MEMORY_FILE}.${process.pid}.${randomUUID()}.tmp`;
  try {
    await fs.writeFile(temporary, `${JSON.stringify(validated, null, 2)}\n`, { encoding: "utf8", flag: "wx" });
    await fs.rename(temporary, MEMORY_FILE);
  } catch (error) { await fs.rm(temporary, { force: true }).catch(() => undefined); throw error; }
}
export async function loadMemories(): Promise<Memory[]> { return serialized(readFileUnsafe); }
export async function saveMemories(memories: Memory[]): Promise<void> {
  if (!Array.isArray(memories)) throw new Error("saveMemories expects an array.");
  return serialized(() => writeFileUnsafe(memories));
}
export async function mutateMemories(mutator: (current: Memory[]) => Memory[]): Promise<Memory[]> {
  return serialized(async () => {
    const current = await readFileUnsafe();
    const next = mutator([...current]);
    if (!Array.isArray(next)) throw new Error("Memory mutator must return an array.");
    await writeFileUnsafe(next);
    return next;
  });
}
function normalizeText(value: string): string { return value.normalize("NFKC").toLocaleLowerCase().replace(/\s+/g, " ").trim(); }
export async function addMemory(category: MemoryCategory, text: string): Promise<Memory> {
  if (!CATEGORIES.includes(category)) throw new Error("Invalid memory category.");
  if (typeof text !== "string" || !text.trim()) throw new Error("Memory text is required.");
  const clean = text.trim(); const key = normalizeText(clean); let result!: Memory;
  await mutateMemories(current => {
    const duplicate = current.find(m => m.category === category && normalizeText(m.text) === key);
    if (duplicate) { result = duplicate; return current; }
    const now = new Date().toISOString();
    result = { id: randomUUID(), category, text: clean, createdAt: now, updatedAt: now };
    return [...current, result];
  });
  return result;
}
export async function deleteMemory(id: string): Promise<boolean> {
  if (!id) throw new Error("Memory id is required."); let removed = false;
  await mutateMemories(current => { const next = current.filter(m => m.id !== id); removed = next.length !== current.length; return next; });
  return removed;
}
export function formatSystemInstructionsWithMemories(baseInstruction: string, memories: Memory[]): string {
  const grouped: Partial<Record<MemoryCategory, string[]>> = {};
  for (const memory of memories) (grouped[memory.category] ??= []).push(memory.text);
  const labels: Record<MemoryCategory, string> = { identity: "Identity", preference: "Preferences", goal: "Goals", project: "Projects", relationship: "Relationships", emotional: "Important emotional context", behavior: "Observed behavior" };
  const sections = CATEGORIES.flatMap(category => grouped[category]?.length ? [`${labels[category]}:\n${grouped[category]!.map(text => `- ${text}`).join("\n")}`] : []);
  const body = sections.length ? sections.join("\n\n") : "No persistent memories are currently stored. Learn durable details naturally during conversation.";
  return `${baseInstruction}\n\n=== MYRAA PERSISTENT MEMORY ===\nUse these details only when relevant. Do not force references or treat uncertain details as facts.\n${body}\n==============================\n`;
}
function parseTransactions(value: unknown): MemoryTransaction[] {
  if (!isRecord(value) || !Array.isArray(value.transactions)) return [];
  const out: MemoryTransaction[] = [];
  for (const item of value.transactions) {
    if (!isRecord(item)) continue;
    const action = item.action; const category = item.category;
    const text = typeof item.text === "string" ? item.text.trim() : "";
    const id = typeof item.id === "string" ? item.id.trim() : "";
    if (!["ADD", "UPDATE", "REMOVE"].includes(String(action))) continue;
    if (typeof category !== "string" || !CATEGORIES.includes(category as MemoryCategory)) continue;
    if (action !== "REMOVE" && !text) continue;
    out.push({ action: action as MemoryTransaction["action"], id, category: category as MemoryCategory, text });
  }
  return out;
}
export async function processConversationSlice(apiKey: string, dialogueHistory: { role: string; text: string }[]): Promise<Memory[] | null> {
  if (isConsolidating || dialogueHistory.length < 2) return null;
  const dialogue = dialogueHistory
    .filter(line => ["user", "assistant", "model"].includes(line.role) && typeof line.text === "string")
    .map(line => `${line.role === "user" ? "User" : "Myraa"}: ${line.text.trim()}`).filter(line => !line.endsWith(":"));
  if (dialogue.length < 2) return null;
  isConsolidating = true;
  try {
    const ai = new GoogleGenAI({ apiKey, httpOptions: { headers: { "User-Agent": "aistudio-build" } } });
    const current = await loadMemories();
    const memoryContext = current.map(m => `ID: ${m.id} | Category: ${m.category} | Fact: ${m.text}`).join("\n");
    const prompt = `You are MYRAA's durable-memory curator. Extract only stable, useful personal facts. Ignore greetings, small talk, transient states, speculation, and facts about others unless they are an important relationship.\n\nCURRENT MEMORIES:\n${memoryContext || "(none)"}\n\nRECENT DIALOGUE:\n${dialogue.join("\n")}\n\nReturn transactions only. ADD genuinely new facts. UPDATE only an exact existing ID. REMOVE only when explicitly asked to forget or clearly disproven. Never invent IDs. Keep facts concise and third-person. Never store passwords, API keys, secrets, or highly sensitive personal data.`;
    const response = await ai.models.generateContent({
      model: "gemini-3.5-flash", contents: prompt,
      config: { responseMimeType: "application/json", responseSchema: {
        type: Type.OBJECT, properties: { transactions: { type: Type.ARRAY, items: { type: Type.OBJECT,
          properties: { action: { type: Type.STRING, enum: ["ADD", "UPDATE", "REMOVE"] }, id: { type: Type.STRING }, category: { type: Type.STRING, enum: [...CATEGORIES] }, text: { type: Type.STRING } },
          required: ["action", "category", "text"] } } }, required: ["transactions"] } }
    });
    const raw = response.text?.trim(); if (!raw) return null;
    const transactions = parseTransactions(JSON.parse(raw)); if (!transactions.length) return null;
    let changed = false;
    const updated = await mutateMemories(latest => {
      const next = [...latest]; const now = new Date().toISOString();
      for (const trx of transactions) {
        if (trx.action === "ADD") {
          const key = normalizeText(trx.text);
          if (!next.some(m => m.category === trx.category && normalizeText(m.text) === key)) {
            next.push({ id: randomUUID(), category: trx.category, text: trx.text, createdAt: now, updatedAt: now }); changed = true;
          }
          continue;
        }
        if (!trx.id) continue;
        const index = next.findIndex(m => m.id === trx.id); if (index < 0) continue;
        if (trx.action === "REMOVE") { next.splice(index, 1); changed = true; }
        else if (next[index].category !== trx.category || normalizeText(next[index].text) !== normalizeText(trx.text)) {
          next[index] = { ...next[index], category: trx.category, text: trx.text, updatedAt: now }; changed = true;
        }
      }
      return next;
    });
    return changed ? updated : null;
  } catch (error) {
    console.error("[Memory] Consolidation failed; existing memory file was preserved:", error);
    return null;
  } finally { isConsolidating = false; }
}
