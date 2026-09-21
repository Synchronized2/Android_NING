import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDirectory, "../app/src/main/assets/live2d");
const require = createRequire(import.meta.url);
const runtime = require(path.resolve(
  scriptDirectory,
  "../../../../github/WeChatProjects/ning/miniprogram/vendor/live2d/live2dcubismcore.min.js"
)).Live2DCubismCore;

async function waitForRuntime() {
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline) {
    try {
      runtime.Version.csmGetVersion();
      return;
    } catch (_) {
      await new Promise(resolve => setTimeout(resolve, 25));
    }
  }
  throw new Error("Cubism Core did not initialize");
}

function partOpacity(model, partIndex) {
  let opacity = 1;
  const visited = new Set();
  while (partIndex >= 0 && !visited.has(partIndex)) {
    visited.add(partIndex);
    opacity *= model.parts.opacities[partIndex];
    partIndex = model.parts.parentIndices[partIndex];
  }
  return opacity;
}

function maskOpacity(model, drawableIndex) {
  return partOpacity(model, model.drawables.parentPartIndices[drawableIndex]);
}

function drawableBox(model, index) {
  const vertices = model.drawables.vertexPositions[index];
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  for (let offset = 0; offset < vertices.length; offset += 2) {
    minX = Math.min(minX, vertices[offset]);
    maxX = Math.max(maxX, vertices[offset]);
    minY = Math.min(minY, vertices[offset + 1]);
    maxY = Math.max(maxY, vertices[offset + 1]);
  }
  return { minX, maxX, minY, maxY, area: (maxX - minX) * (maxY - minY) };
}

function excludedDrawables(model) {
  const excluded = new Set();
  const visual = [];
  for (let index = 0; index < model.drawables.count; index++) {
    if (!model.drawables.indexCounts[index]) continue;
    const id = String(model.drawables.ids[index] || "");
    if (/touch(?:body|head)?|hitarea|blackbg/i.test(id)) {
      excluded.add(index);
      continue;
    }
    const box = drawableBox(model, index);
    if (Number.isFinite(box.area) && box.area > 0) visual.push({ index, area: box.area });
  }
  visual.sort((left, right) => right.area - left.area);
  if (visual.length > 1 && visual[0].area > visual[1].area * 20) {
    excluded.add(visual[0].index);
  }
  return excluded;
}

function legacyReferences(config) {
  const references = [config.model, config.pose, config.physics, ...(config.textures || [])];
  for (const expression of config.expressions || []) references.push(expression.file);
  for (const motions of Object.values(config.motions || {})) {
    for (const motion of motions || []) references.push(motion.file, motion.sound);
  }
  return references.filter(reference => typeof reference === "string" && reference.trim());
}

function validateLegacyEntry(entry) {
  const manifestPath = path.join(root, entry.manifest);
  const previewPath = path.join(root, entry.preview);
  for (const required of [manifestPath, previewPath]) {
    if (!fs.existsSync(required) || fs.statSync(required).size === 0) {
      throw new Error(`${entry.name}: missing or empty ${required}`);
    }
  }
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  if (!manifest.model || !(manifest.textures || []).length) {
    throw new Error(`${entry.name}: incomplete Cubism 2 manifest`);
  }
  for (const reference of legacyReferences(manifest)) {
    const absolute = path.resolve(path.dirname(manifestPath), reference);
    const relative = path.relative(root, absolute);
    if (relative.startsWith("..") || path.isAbsolute(relative)) {
      throw new Error(`${entry.name}: Cubism 2 reference escapes assets: ${reference}`);
    }
    if (!fs.existsSync(absolute) || fs.statSync(absolute).size === 0) {
      throw new Error(`${entry.name}: missing Cubism 2 reference ${reference}`);
    }
  }
  const mocPath = path.resolve(path.dirname(manifestPath), manifest.model);
  const signature = fs.readFileSync(mocPath).subarray(0, 3).toString("ascii").toLowerCase();
  if (signature !== "moc") throw new Error(`${entry.name}: invalid Cubism 2 moc signature`);
  return { excluded: 0, visible: 1 };
}

function validateEntry(entry) {
  if (entry.generation === 2) return validateLegacyEntry(entry);
  const required = [entry.model, entry.preview, ...entry.textures];
  for (const relative of required) {
    const absolute = path.join(root, relative);
    if (!fs.existsSync(absolute) || fs.statSync(absolute).size === 0) {
      throw new Error(`${entry.name}: missing or empty ${relative}`);
    }
  }
  const bytes = new Uint8Array(fs.readFileSync(path.join(root, entry.model)));
  const moc = runtime.Moc.fromArrayBuffer(bytes.buffer);
  if (!moc) throw new Error(`${entry.name}: Cubism Core rejected model`);
  const model = runtime.Model.fromMoc(moc);
  if (!model) throw new Error(`${entry.name}: Cubism Core could not create model`);
  try {
    for (const group of entry.poseGroups || []) {
      if (group.length < 2) throw new Error(`${entry.name}: invalid one-item pose group`);
      group.forEach((item, itemIndex) => {
        const opacity = itemIndex === 0 ? 1 : 0;
        for (const id of [item.id, ...(item.links || [])]) {
          const part = model.parts.ids.indexOf(id);
          if (part < 0) throw new Error(`${entry.name}: missing pose part ${id}`);
          model.parts.opacities[part] = opacity;
        }
      });
    }
    model.update();
    const excluded = excludedDrawables(model);
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    let visible = 0;
    let invisibleMaskSources = 0;
    for (let index = 0; index < model.drawables.count; index++) {
      const opacity = model.drawables.opacities[index]
        * partOpacity(model, model.drawables.parentPartIndices[index]);
      if (excluded.has(index) || opacity <= 0.001 || !model.drawables.indexCounts[index]) continue;
      for (const maskIndex of model.drawables.masks[index] || []) {
        if (model.drawables.opacities[maskIndex] <= 0.001
            && maskOpacity(model, maskIndex) > 0.001) {
          invisibleMaskSources += 1;
        }
      }
      const box = drawableBox(model, index);
      minX = Math.min(minX, box.minX);
      maxX = Math.max(maxX, box.maxX);
      minY = Math.min(minY, box.minY);
      maxY = Math.max(maxY, box.maxY);
      visible += 1;
    }
    if (!visible || !Number.isFinite(minX) || maxX <= minX || maxY <= minY) {
      throw new Error(`${entry.name}: no usable visible geometry`);
    }
    if (entry.name === "Haru" && invisibleMaskSources === 0) {
      throw new Error("Haru: expected invisible eye clipping sources were not found");
    }
    return { excluded: excluded.size, visible };
  } finally {
    model.release();
    moc._release();
  }
}

await waitForRuntime();
const catalog = JSON.parse(fs.readFileSync(path.join(root, "catalog.json"), "utf8"));
const ids = new Set();
let excluded = 0;
let modern = 0;
let legacy = 0;
for (const entry of catalog) {
  if (!entry.id || ids.has(entry.id)) throw new Error(`Duplicate or empty model id: ${entry.id}`);
  ids.add(entry.id);
  excluded += validateEntry(entry).excluded;
  if (entry.generation === 2) legacy += 1;
  else modern += 1;
}
console.log(`Verified ${catalog.length} Live2D configurations `
  + `(${modern} Cubism 3, ${legacy} Cubism 2); `
  + `${excluded} helper/outlier drawables excluded.`);
