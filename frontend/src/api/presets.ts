import { getJson, requestJson } from "./client";

/** One parameter of the goal; share is its actual percentage of the total weight (PresetController.WeightResponse). */
export interface PresetWeight {
  name: string;
  weight: number;
  share: number;
}

/** The base resume as the preset sees it: a stored resume (shared with the Resume screen) or the default template. */
export interface BaseResume {
  resumeId: string | null;
  name: string;
  fileName: string | null;
  contentType: string | null;
  text: string;
  template: boolean;
  hasOriginal: boolean;
}

/** A field that keeps a document from being generated, with the placeholders still to fill. */
export interface MissingField {
  field: string;
  message: string;
  placeholders: string[];
}

/** An AI preset (T-093, PresetController.PresetResponse). */
export interface Preset {
  id: string;
  name: string;
  isDefault: boolean;
  mainGoal: string;
  weights: PresetWeight[];
  resumePrompt: string;
  coverLetterPrompt: string;
  baseResume: BaseResume;
  baseCoverLetter: string;
  baseCoverLetterFileName: string | null;
  readiness: { resume: MissingField[]; coverLetter: MissingField[] };
  createdAt: string;
  updatedAt: string;
}

/** The texts "Reset to default" puts back; the guardrail is shown but never editable. */
export interface PresetDefaults {
  mainGoal: string;
  weights: { name: string; weight: number }[];
  resumePrompt: string;
  coverLetterPrompt: string;
  baseResume: string;
  baseCoverLetter: string;
  guardrail: string;
}

export interface PresetChange {
  name?: string;
  mainGoal?: string;
  weights?: { name: string; weight: number }[];
  resumePrompt?: string;
  coverLetterPrompt?: string;
  baseCoverLetter?: string;
}

const base = (id: string) => `/api/presets/${encodeURIComponent(id)}`;
const fileQuery = (file: File) => `?fileName=${encodeURIComponent(file.name)}`;
const fileType = (file: File) => file.type || "application/octet-stream";

export const listPresets = (signal?: AbortSignal) => getJson<Preset[]>("/api/presets", signal);
export const getPresetDefaults = (signal?: AbortSignal) => getJson<PresetDefaults>("/api/presets/defaults", signal);
export const createPreset = (name: string) => requestJson<Preset>("POST", "/api/presets", { name });
export const duplicatePreset = (id: string) => requestJson<Preset>("POST", `${base(id)}/duplicate`);
export const updatePreset = (id: string, change: PresetChange) => requestJson<Preset>("PATCH", base(id), change);
export const deletePreset = (id: string) => requestJson<void>("DELETE", base(id));
export const makeDefaultPreset = (id: string) => requestJson<Preset>("POST", `${base(id)}/default`);
export const uploadBaseResume = (id: string, file: File) =>
  requestJson<Preset>("POST", `${base(id)}/base-resume/file${fileQuery(file)}`, file, fileType(file));
export const pasteBaseResume = (id: string, text: string) => requestJson<Preset>("POST", `${base(id)}/base-resume/text`, { text });
export const resetBaseResume = (id: string) => requestJson<Preset>("DELETE", `${base(id)}/base-resume`);
export const uploadBaseCoverLetter = (id: string, file: File) =>
  requestJson<Preset>("POST", `${base(id)}/base-cover-letter/file${fileQuery(file)}`, file, fileType(file));
