import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDirectory, "..");
const ningRoot = process.env.NING_ROOT || "D:/github/WeChatProjects/ning";
const miraRoot = process.env.MIRA_ROOT || "D:/gitlab/OpenAIQ/Mira-Companion";
const destinationRoot = path.join(projectRoot, "app/src/main/assets/live2d/models");
const licenseRoot = path.join(projectRoot, "app/src/main/assets/live2d/licenses");
const require = createRequire(import.meta.url);
const catalog = require(path.join(ningRoot, "miniprogram/utils/avatar-catalog.js"));
const plan = JSON.parse(fs.readFileSync(
  path.join(ningRoot, "artifacts/avatars/upload-plan.json"), "utf8"));
const exclusions = JSON.parse(fs.readFileSync(
  path.join(miraRoot, "shared/character-exclusions.json"), "utf8"));
const miraCharacters = JSON.parse(fs.readFileSync(
  path.join(miraRoot, "shared/characters.json"), "utf8"));

fs.mkdirSync(destinationRoot, { recursive: true });
fs.mkdirSync(licenseRoot, { recursive: true });

function copy(source, destination) {
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.copyFileSync(source, destination);
}

function loadPoseGroups(modelSource) {
  const directory = path.dirname(modelSource);
  const modelName = path.basename(modelSource);
  let modelConfig = null;
  for (const filename of fs.readdirSync(directory)) {
    if (!filename.toLowerCase().endsWith(".model3.json")) continue;
    const candidate = JSON.parse(fs.readFileSync(path.join(directory, filename), "utf8"));
    if (path.basename(candidate.FileReferences?.Moc || "") === modelName) {
      modelConfig = candidate;
      break;
    }
  }
  const poseReference = modelConfig?.FileReferences?.Pose;
  if (!poseReference) return [];
  const posePath = path.resolve(directory, poseReference);
  if (!fs.existsSync(posePath)) {
    throw new Error(`Missing pose file for ${modelSource}: ${posePath}`);
  }
  const pose = JSON.parse(fs.readFileSync(posePath, "utf8"));
  return (pose.Groups || []).map(group => group.map(item => ({
    id: String(item.Id || "").trim(),
    links: (item.Link || []).map(link => String(link || "").trim()).filter(Boolean),
  }))).filter(group => group.length > 1 && group.every(item => item.id));
}

function listFiles(directory, extension, result = []) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) listFiles(absolute, extension, result);
    else if (entry.name.toLowerCase().endsWith(extension)) result.push(absolute);
  }
  return result;
}

function normalized(filename) {
  return path.resolve(filename).toLowerCase();
}

function toAssetPath(filename) {
  return filename.split(path.sep).join("/");
}

function legacyReferences(config) {
  const references = [config.model, config.pose, config.physics, ...(config.textures || [])];
  for (const expression of config.expressions || []) references.push(expression.file);
  for (const motions of Object.values(config.motions || {})) {
    for (const motion of motions || []) references.push(motion.file, motion.sound);
  }
  return references.filter(reference => typeof reference === "string" && reference.trim());
}

const importedModelSources = new Set(plan.uploads
  .filter(item => item.cloudPath.endsWith("/model.moc3"))
  .map(item => normalized(item.source)));
const excludedModelSources = new Set();
for (const relativeConfig of Object.keys(exclusions)) {
  const configPath = path.join(miraRoot, "public/assets/characters", relativeConfig);
  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  excludedModelSources.add(normalized(path.resolve(
    path.dirname(configPath), config.FileReferences.Moc)));
}
const characterRoot = path.join(miraRoot, "public/assets/characters");
const unclassifiedModels = listFiles(characterRoot, ".moc3")
  .filter(filename => !importedModelSources.has(normalized(filename))
    && !excludedModelSources.has(normalized(filename)));
if (unclassifiedModels.length) {
  throw new Error(`Unclassified Cubism 3 models:\n${unclassifiedModels.join("\n")}`);
}

copy(
  path.join(miraRoot, "licenses/live2d-collection-README.md"),
  path.join(licenseRoot, "live2d-collection-README.md"));

const output = [];
for (const entry of catalog) {
  const destination = path.join(destinationRoot, entry.id);
  if (entry.id === "hiyori") {
    const runtime = path.join(
      miraRoot, "public/assets/hiyori/hiyori_pro_zh/runtime");
    copy(path.join(runtime, "hiyori_pro_t11.moc3"), path.join(destination, "model.moc3"));
    copy(path.join(runtime, "hiyori_pro_t11.2048/texture_00.png"), path.join(destination, "texture-0.png"));
    copy(path.join(runtime, "hiyori_pro_t11.2048/texture_01.png"), path.join(destination, "texture-1.png"));
    const previewSource = path.join(
      miraRoot, entry.preview.cloudPath
        ? "public/assets/character-previews/hiyori.png"
        : "public/assets/character-previews/hiyori.png");
    const fallbackPreview = path.join(ningRoot, "miniprogram/packages/avatars/previews/hiyori.jpg");
    copy(fs.existsSync(previewSource) ? previewSource : fallbackPreview, path.join(destination, "preview.jpg"));
    output.push({
      id: entry.id,
      name: entry.name,
      family: entry.family,
      generation: 3,
      model: `models/${entry.id}/model.moc3`,
      textures: [
        `models/${entry.id}/texture-0.png`,
        `models/${entry.id}/texture-1.png`,
      ],
      preview: `models/${entry.id}/preview.jpg`,
      poseGroups: [[
        { id: "PartArmA", links: [] },
        { id: "PartArmB", links: [] },
      ]],
    });
    continue;
  }

  const prefix = `ning/avatars/v1/${entry.id}/${entry.version}/`;
  const files = plan.uploads.filter(item => item.cloudPath.startsWith(prefix));
  const model = files.find(item => item.cloudPath.endsWith("/model.moc3"));
  const preview = files.find(item => item.cloudPath.endsWith("/preview.png"));
  const textures = files
    .filter(item => /\/texture-\d+\.png$/.test(item.cloudPath))
    .sort((left, right) => left.cloudPath.localeCompare(right.cloudPath, undefined, { numeric: true }));
  if (!model || !preview || !textures.length) {
    throw new Error(`Incomplete model entry: ${entry.id}`);
  }
  copy(model.source, path.join(destination, "model.moc3"));
  textures.forEach((item, index) => copy(item.source, path.join(destination, `texture-${index}.png`)));
  copy(preview.source, path.join(destination, "preview.png"));
  output.push({
    id: entry.id,
    name: entry.name,
    family: entry.family,
    generation: 3,
    model: `models/${entry.id}/model.moc3`,
    textures: textures.map((_, index) => `models/${entry.id}/texture-${index}.png`),
    preview: `models/${entry.id}/preview.png`,
    poseGroups: loadPoseGroups(model.source),
  });
}

if (output.length !== catalog.length) {
  throw new Error(`Expected ${catalog.length} Cubism 3 models, found ${output.length}`);
}

const publicRoot = path.join(miraRoot, "public");
const characterAssetRoot = path.join(publicRoot, "assets/characters");
const legacyCharacters = miraCharacters.filter(entry => entry.generation === 2);
const copiedLegacyFiles = new Set();
for (const entry of legacyCharacters) {
  const manifestSource = path.join(publicRoot, decodeURIComponent(entry.url).replace(/^\//, ""));
  const manifestRelative = path.relative(characterAssetRoot, manifestSource);
  if (manifestRelative.startsWith("..") || path.isAbsolute(manifestRelative)) {
    throw new Error(`Cubism 2 manifest is outside character assets: ${entry.url}`);
  }
  const manifest = JSON.parse(fs.readFileSync(manifestSource, "utf8"));
  const sources = [manifestSource, ...legacyReferences(manifest)
    .map(reference => path.resolve(path.dirname(manifestSource), reference))];
  for (const source of sources) {
    if (!fs.existsSync(source) || !fs.statSync(source).isFile()) {
      throw new Error(`Missing Cubism 2 asset for ${entry.name}: ${source}`);
    }
    const relative = path.relative(characterAssetRoot, source);
    if (relative.startsWith("..") || path.isAbsolute(relative)) {
      throw new Error(`Cubism 2 dependency is outside character assets: ${source}`);
    }
    const destination = path.join(projectRoot, "app/src/main/assets/live2d/legacy", relative);
    const key = normalized(destination);
    if (!copiedLegacyFiles.has(key)) {
      copy(source, destination);
      copiedLegacyFiles.add(key);
    }
  }
  const previewSource = path.join(publicRoot, decodeURIComponent(entry.preview).replace(/^\//, ""));
  const previewExtension = path.extname(previewSource).toLowerCase() || ".png";
  const previewRelative = `legacy/previews/${entry.id}${previewExtension}`;
  copy(previewSource, path.join(projectRoot, "app/src/main/assets/live2d", previewRelative));
  output.push({
    id: entry.id,
    name: entry.name,
    family: entry.family,
    generation: 2,
    manifest: `legacy/${toAssetPath(manifestRelative)}`,
    preview: previewRelative,
  });
}

copy(
  path.join(miraRoot, "public/vendor/live2d-legacy.js"),
  path.join(projectRoot, "app/src/main/assets/live2d/live2d-legacy.js"));
copy(
  path.join(miraRoot, "node_modules/pixi.js/dist/browser/pixi.min.js"),
  path.join(projectRoot, "app/src/main/assets/live2d/pixi.min.js"));
copy(
  path.join(miraRoot, "node_modules/pixi-live2d-display/dist/cubism2.min.js"),
  path.join(projectRoot, "app/src/main/assets/live2d/pixi-live2d-cubism2.min.js"));
fs.writeFileSync(
  path.join(projectRoot, "app/src/main/assets/live2d/catalog.json"),
  `${JSON.stringify(output, null, 2)}\n`);
const bytes = output.reduce((total, item) => {
  const files = item.generation === 2
    ? [item.manifest, item.preview]
    : [item.model, item.preview, ...item.textures];
  return total + files.reduce((sum, relative) =>
    sum + fs.statSync(path.join(projectRoot, "app/src/main/assets/live2d", relative)).size, 0);
}, 0);
console.log(`Imported ${output.length} Live2D configurations `
  + `(${catalog.length} Cubism 3, ${legacyCharacters.length} Cubism 2; `
  + `${Math.round(bytes / 1048576)} MiB catalog files); `
  + `${excludedModelSources.size} unusable source model(s) remain explicitly excluded.`);
