(function () {
  "use strict";

  const runtime = window.Live2DCubismCore;
  const canvas = document.getElementById("avatar");
  let gl;
  const state = {
    phase: "idle",
    speaking: false,
    focusX: 0,
    focusY: 0,
    targetX: 0,
    targetY: 0,
    gestureAt: 0,
    viewMode: "portrait",
    startedAt: performance.now(),
  };

  let model;
  let moc;
  let textures = [];
  let parameterIndex = new Map();
  let transform = [1, 1, 0, 0];
  let program;
  let locations;
  let positionBuffer;
  let uvBuffer;
  let indexBuffer;
  let excludedDrawables = new Set();
  let legacyModel = false;
  let legacyApp;
  let legacyAvatar;

  const vertexShader = `
    attribute vec2 aPosition;
    attribute vec2 aUv;
    uniform vec4 uTransform;
    varying vec2 vUv;
    void main() {
      vec2 position = aPosition * uTransform.xy + uTransform.zw;
      gl_Position = vec4(position, 0.0, 1.0);
      vUv = aUv;
    }
  `;
  const fragmentShader = `
    precision mediump float;
    uniform sampler2D uTexture;
    uniform float uOpacity;
    uniform float uMaskPass;
    varying vec2 vUv;
    void main() {
      vec4 color = texture2D(uTexture, vUv);
      if (uMaskPass > 0.5) {
        if (color.a * uOpacity < 0.02) discard;
        gl_FragColor = vec4(1.0);
        return;
      }
      gl_FragColor = color * uOpacity;
    }
  `;

  function report(name, value) {
    try {
      if (window.AndroidAvatar && typeof window.AndroidAvatar[name] === "function") {
        window.AndroidAvatar[name](value || "");
      }
    } catch (_) {}
  }

  function compile(type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      throw new Error(gl.getShaderInfoLog(shader) || "着色器编译失败");
    }
    return shader;
  }

  function createProgram() {
    const vertex = compile(gl.VERTEX_SHADER, vertexShader);
    const fragment = compile(gl.FRAGMENT_SHADER, fragmentShader);
    const result = gl.createProgram();
    gl.attachShader(result, vertex);
    gl.attachShader(result, fragment);
    gl.linkProgram(result);
    gl.deleteShader(vertex);
    gl.deleteShader(fragment);
    if (!gl.getProgramParameter(result, gl.LINK_STATUS)) {
      throw new Error(gl.getProgramInfoLog(result) || "着色器链接失败");
    }
    return result;
  }

  async function waitForRuntime() {
    const started = performance.now();
    while (performance.now() - started < 8000) {
      try {
        runtime.Version.csmGetVersion();
        return;
      } catch (_) {
        await new Promise(resolve => setTimeout(resolve, 30));
      }
    }
    throw new Error("Live2D Core 初始化超时");
  }

  function loadTexture(source) {
    return new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => {
        try {
          const texture = gl.createTexture();
          gl.bindTexture(gl.TEXTURE_2D, texture);
          gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
          gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
          gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image);
          resolve(texture);
        } catch (error) {
          reject(error);
        }
      };
      image.onerror = () => reject(new Error("无法加载人物纹理"));
      image.src = source;
    });
  }

  function loadAsset(source, responseType) {
    return new Promise((resolve, reject) => {
      const request = new XMLHttpRequest();
      request.open("GET", source, true);
      request.responseType = responseType;
      request.onload = () => {
        // Local APK assets report status 0 in Android WebView.
        if (request.status === 0 || (request.status >= 200 && request.status < 300)) {
          resolve(request.response);
        } else {
          reject(new Error("无法读取内置资源（" + request.status + "）"));
        }
      };
      request.onerror = () => reject(new Error("无法读取内置资源"));
      request.send();
    });
  }

  function effectivePartOpacity(partIndex) {
    let opacity = 1;
    const visited = new Set();
    while (partIndex >= 0 && !visited.has(partIndex)) {
      visited.add(partIndex);
      opacity *= model.parts.opacities[partIndex];
      partIndex = model.parts.parentIndices[partIndex];
    }
    return opacity;
  }

  function effectiveDrawableOpacity(index) {
    return model.drawables.opacities[index]
      * effectivePartOpacity(model.drawables.parentPartIndices[index]);
  }

  function maskDrawableOpacity(index) {
    // Cubism clipping meshes may be intentionally invisible in the final
    // composition. Their texture alpha still defines the clipping shape.
    return effectivePartOpacity(model.drawables.parentPartIndices[index]);
  }

  function drawableBounds(index) {
    const vertices = model.drawables.vertexPositions[index];
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (let vertex = 0; vertex < vertices.length; vertex += 2) {
      minX = Math.min(minX, vertices[vertex]);
      maxX = Math.max(maxX, vertices[vertex]);
      minY = Math.min(minY, vertices[vertex + 1]);
      maxY = Math.max(maxY, vertices[vertex + 1]);
    }
    return { minX, maxX, minY, maxY, area: (maxX - minX) * (maxY - minY) };
  }

  function findExcludedDrawables() {
    const drawables = model.drawables;
    const excluded = new Set();
    const visual = [];
    for (let index = 0; index < drawables.count; index++) {
      if (!drawables.indexCounts[index]) continue;
      const id = String(drawables.ids[index] || "");
      if (/touch(?:body|head)?|hitarea|blackbg/i.test(id)) {
        excluded.add(index);
        continue;
      }
      const bounds = drawableBounds(index);
      if (Number.isFinite(bounds.area) && bounds.area > 0) {
        visual.push({ index, area: bounds.area });
      }
    }
    visual.sort((left, right) => right.area - left.area);
    // Some third-party models contain one full-canvas helper mesh hundreds of
    // times larger than the art. It is neither a character layer nor a mask.
    if (visual.length > 1 && visual[0].area > visual[1].area * 20) {
      excluded.add(visual[0].index);
    }
    return excluded;
  }

  function visibleBounds() {
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    const drawables = model.drawables;
    for (let index = 0; index < drawables.count; index++) {
      if (excludedDrawables.has(index)
          || effectiveDrawableOpacity(index) <= 0.001
          || !drawables.indexCounts[index]) continue;
      const bounds = drawableBounds(index);
      minX = Math.min(minX, bounds.minX);
      maxX = Math.max(maxX, bounds.maxX);
      minY = Math.min(minY, bounds.minY);
      maxY = Math.max(maxY, bounds.maxY);
    }
    if (!Number.isFinite(minX)) throw new Error("人物模型没有可见网格");
    return { minX, maxX, minY, maxY };
  }

  function resize() {
    const ratio = Math.min(2, window.devicePixelRatio || 1);
    const width = Math.max(1, Math.round(canvas.clientWidth * ratio));
    const height = Math.max(1, Math.round(canvas.clientHeight * ratio));
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
    if (!model) return;
    const bounds = visibleBounds();
    const fullScale = Math.min(
      width * 0.84 / (bounds.maxX - bounds.minX),
      height * 0.84 / (bounds.maxY - bounds.minY));
    const scale = state.viewMode === "full" ? fullScale : fullScale * 1.5;
    const scaleX = 2 * scale / width;
    const scaleY = 2 * scale / height;
    transform = state.viewMode === "full"
      ? [scaleX, scaleY,
          -(bounds.minX + bounds.maxX) * 0.5 * scaleX,
          -(bounds.minY + bounds.maxY) * 0.5 * scaleY]
      : [scaleX, scaleY,
          -(bounds.minX + bounds.maxX) * 0.5 * scaleX,
          0.84 - bounds.maxY * scaleY];
  }

  function legacyGeometryBounds() {
    if (!legacyAvatar || !legacyAvatar.internalModel) return null;
    const internal = legacyAvatar.internalModel;
    const offsetRatio = internal.settings && internal.settings.layout ? 0.45 : 1;
    const coreOffsetX = internal.originalWidth * 0.5 * offsetRatio;
    const ids = internal.getDrawableIDs();
    const entries = [];
    for (let index = 0; index < internal.drawDataCount; index++) {
      const id = String(ids[index] || "");
      if (/touch(?:body|head)?|hitarea|blackbg/i.test(id)) continue;
      let vertices;
      try {
        vertices = internal.getDrawableVertices(index);
      } catch (_) {
        continue;
      }
      if (!vertices || vertices.length < 2) continue;
      let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
      const matrix = internal.localTransform;
      for (let vertex = 0; vertex < vertices.length; vertex += 2) {
        const coreX = vertices[vertex] + coreOffsetX;
        const x = matrix.a * coreX
          + matrix.c * vertices[vertex + 1] + matrix.tx;
        const y = matrix.b * vertices[vertex]
          + matrix.d * vertices[vertex + 1] + matrix.ty;
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
      }
      const area = (maxX - minX) * (maxY - minY);
      if (Number.isFinite(area) && area > 0) {
        entries.push({ minX, maxX, minY, maxY, area });
      }
    }
    if (!entries.length) return null;
    entries.sort((left, right) => right.area - left.area);
    if (entries.length > 1 && entries[0].area > entries[1].area * 20) {
      entries.shift();
    }
    return entries.reduce((bounds, entry) => ({
      minX: Math.min(bounds.minX, entry.minX),
      maxX: Math.max(bounds.maxX, entry.maxX),
      minY: Math.min(bounds.minY, entry.minY),
      maxY: Math.max(bounds.maxY, entry.maxY),
    }), { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity });
  }

  function fitLegacyAvatar() {
    if (!legacyApp || !legacyAvatar) return;
    const width = Math.max(1, canvas.clientWidth);
    const height = Math.max(1, canvas.clientHeight);
    legacyApp.renderer.resize(width, height);
    const portrait = state.viewMode !== "full";
    const bounds = legacyGeometryBounds();
    if (!bounds) return;
    const boundsWidth = Math.max(1, bounds.maxX - bounds.minX);
    const boundsHeight = Math.max(1, bounds.maxY - bounds.minY);
    const wideModel = boundsWidth / boundsHeight > 0.65;
    const fullBodyFill = wideModel ? 0.68 : 0.84;
    const fullScale = Math.min(
      width * fullBodyFill / boundsWidth,
      height * fullBodyFill / boundsHeight);
    const scale = portrait ? fullScale * 1.5 : fullScale;
    legacyAvatar.anchor.set(0, 0);
    legacyAvatar.scale.set(scale, scale);
    legacyAvatar.position.x = width * (wideModel ? 0.45 : 0.5)
      - (bounds.minX + bounds.maxX) * 0.5 * scale;
    legacyAvatar.position.y = portrait
      ? height * 0.04 - bounds.minY * scale
      : height * 0.5 - (bounds.minY + bounds.maxY) * 0.5 * scale;
  }

  async function initializeLegacy(selected) {
    legacyModel = true;
    if (!window.PIXI || !window.PIXI.live2d) {
      throw new Error("Cubism 2 Pixi 运行库不可用");
    }
    window.PIXI.live2d.config.sound = false;
    legacyApp = new window.PIXI.Application({
      view: canvas,
      backgroundAlpha: 0,
      antialias: true,
      autoDensity: true,
      preserveDrawingBuffer: true,
      resolution: Math.min(window.devicePixelRatio || 1, 2),
    });
    legacyAvatar = await window.PIXI.live2d.Live2DModel.from(selected.manifest, {
      autoInteract: false,
      autoUpdate: true,
    });
    legacyApp.stage.addChild(legacyAvatar);
    legacyAvatar.internalModel.on("beforeModelUpdate", () => {
      const core = legacyAvatar.internalModel.coreModel;
      const set = (id, value) => {
        const index = core.getParamIndex(id);
        if (index >= 0) core.setParamFloat(index, value);
      };
      set("PARAM_EYE_BALL_X", state.focusX);
      set("PARAM_EYE_BALL_Y", -state.focusY);
      set("PARAM_MOUTH_OPEN_Y", state.speaking ?
        0.25 + Math.abs(Math.sin(performance.now() / 95)) * 0.65 : 0);
    });
    fitLegacyAvatar();
    window.addEventListener("resize", fitLegacyAvatar);
    report("onReady", selected.name);
    setTimeout(fitLegacyAvatar, 450);
  }

  function initializePose(groups) {
    (groups || []).forEach(group => group.forEach((entry, index) => {
      const item = typeof entry === "string" ? { id: entry, links: [] } : entry;
      const opacity = index === 0 ? 1 : 0;
      [item.id, ...(item.links || [])].forEach(id => {
        const part = model.parts.ids.indexOf(id);
        if (part >= 0) model.parts.opacities[part] = opacity;
      });
    }));
  }

  function setParameter(id, value, weight) {
    const index = parameterIndex.get(id);
    if (index === undefined) return;
    const parameters = model.parameters;
    const blend = weight === undefined ? 1 : weight;
    const next = parameters.values[index] * (1 - blend) + value * blend;
    parameters.values[index] = Math.max(
      parameters.minimumValues[index],
      Math.min(parameters.maximumValues[index], next));
  }

  function blinkValue(seconds) {
    const phase = seconds % 4.4;
    if (phase < 3.98) return 1;
    if (phase < 4.08) return 1 - (phase - 3.98) / 0.1;
    if (phase < 4.14) return 0;
    if (phase < 4.28) return (phase - 4.14) / 0.14;
    return 1;
  }

  function updateParameters(now) {
    const time = (now - state.startedAt) / 1000;
    const thinking = state.phase === "thinking";
    const answering = state.phase === "answering";
    const listening = state.phase === "listening";
    state.focusX += (state.targetX - state.focusX) * 0.11;
    state.focusY += (state.targetY - state.focusY) * 0.11;
    const sway = Math.sin(time * (thinking ? 1.8 : 0.72));
    const gestureAge = (performance.now() - state.gestureAt) / 1000;
    const gesture = gestureAge >= 0 && gestureAge < 0.9
      ? Math.sin(gestureAge / 0.9 * Math.PI * 2)
      : 0;
    const mouth = state.speaking
      ? 0.25 + Math.abs(Math.sin(time * 10.5)) * 0.65
      : (answering ? 0.1 : 0);
    setParameter("ParamAngleX", state.focusX * 22 + sway * 3.2 + gesture * 4);
    setParameter("ParamAngleY", -state.focusY * 16 + Math.sin(time * 0.53) * 2 - gesture * 8);
    setParameter("ParamAngleZ", (listening ? 5 : 0) + state.focusX * state.focusY * -10);
    setParameter("ParamEyeBallX", state.focusX);
    setParameter("ParamEyeBallY", -state.focusY);
    setParameter("ParamBodyAngleX", sway * (thinking ? 4 : 2.2));
    setParameter("ParamBreath", 0.5 + Math.sin(time * 1.75) * 0.45);
    setParameter("ParamEyeLOpen", blinkValue(time));
    setParameter("ParamEyeROpen", blinkValue(time + 0.015));
    setParameter("ParamMouthOpenY", mouth);
    setParameter("ParamMouthForm", answering || state.speaking ? 0.55 : 0.08);
    model.update();
  }

  function bindGeometry(index) {
    const drawables = model.drawables;
    gl.bindBuffer(gl.ARRAY_BUFFER, positionBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, drawables.vertexPositions[index], gl.DYNAMIC_DRAW);
    gl.enableVertexAttribArray(locations.position);
    gl.vertexAttribPointer(locations.position, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, uvBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, drawables.vertexUvs[index], gl.STATIC_DRAW);
    gl.enableVertexAttribArray(locations.uv);
    gl.vertexAttribPointer(locations.uv, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, indexBuffer);
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, drawables.indices[index], gl.STATIC_DRAW);
  }

  function drawDrawable(index, maskPass) {
    const drawables = model.drawables;
    const texture = textures[drawables.textureIndices[index]];
    if (!texture || !drawables.indexCounts[index]) return;
    bindGeometry(index);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.uniform1f(locations.opacity,
      maskPass ? maskDrawableOpacity(index) : effectiveDrawableOpacity(index));
    gl.uniform1f(locations.maskPass, maskPass ? 1 : 0);
    gl.drawElements(gl.TRIANGLES, drawables.indexCounts[index], gl.UNSIGNED_SHORT, 0);
  }

  function prepareMask(index) {
    const drawables = model.drawables;
    gl.stencilMask(0xff);
    gl.clearStencil(0);
    gl.clear(gl.STENCIL_BUFFER_BIT);
    gl.enable(gl.STENCIL_TEST);
    gl.stencilFunc(gl.ALWAYS, 1, 0xff);
    gl.stencilOp(gl.KEEP, gl.KEEP, gl.REPLACE);
    gl.colorMask(false, false, false, false);
    gl.disable(gl.BLEND);
    const masks = drawables.masks[index] || [];
    for (let offset = 0; offset < masks.length; offset++) drawDrawable(masks[offset], true);
    gl.colorMask(true, true, true, true);
    gl.enable(gl.BLEND);
    const inverted = runtime.Utils.hasIsInvertedMaskBit(drawables.constantFlags[index]);
    gl.stencilMask(0);
    gl.stencilFunc(inverted ? gl.NOTEQUAL : gl.EQUAL, 1, 0xff);
    gl.stencilOp(gl.KEEP, gl.KEEP, gl.KEEP);
  }

  function frame(now) {
    if (model) {
      resize();
      updateParameters(now);
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT | gl.STENCIL_BUFFER_BIT);
      gl.useProgram(program);
      gl.uniform4fv(locations.transform, transform);
      const drawables = model.drawables;
      const order = Array.from({ length: drawables.count }, (_, index) => index)
        .sort((left, right) => drawables.renderOrders[left] - drawables.renderOrders[right]);
      for (const index of order) {
        if (excludedDrawables.has(index)
            || !runtime.Utils.hasIsVisibleBit(drawables.dynamicFlags[index])
            || effectiveDrawableOpacity(index) <= 0.001) continue;
        if (drawables.maskCounts[index] > 0) prepareMask(index);
        else gl.disable(gl.STENCIL_TEST);
        if (runtime.Utils.hasBlendAdditiveBit(drawables.constantFlags[index])) {
          gl.blendFunc(gl.ONE, gl.ONE);
        } else if (runtime.Utils.hasBlendMultiplicativeBit(drawables.constantFlags[index])) {
          gl.blendFunc(gl.DST_COLOR, gl.ONE_MINUS_SRC_ALPHA);
        } else {
          gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
        }
        drawDrawable(index, false);
      }
      gl.disable(gl.STENCIL_TEST);
      drawables.resetDynamicFlags();
    }
    requestAnimationFrame(frame);
  }

  async function initialize() {
    const catalog = JSON.parse(await loadAsset("catalog.json", "text"));
    const selectedId = new URLSearchParams(location.search).get("model") || "hiyori";
    const selected = catalog.find(item => item.id === selectedId) || catalog[0];
    if (!selected) throw new Error("内置人物目录为空");
    if (selected.generation === 2) {
      await initializeLegacy(selected);
      return;
    }
    gl = canvas.getContext("webgl", {
      alpha: true,
      antialias: true,
      premultipliedAlpha: true,
      preserveDrawingBuffer: false,
      stencil: true,
    });
    if (!gl) throw new Error("当前设备不支持 WebGL");
    await waitForRuntime();
    program = createProgram();
    locations = {
      position: gl.getAttribLocation(program, "aPosition"),
      uv: gl.getAttribLocation(program, "aUv"),
      transform: gl.getUniformLocation(program, "uTransform"),
      texture: gl.getUniformLocation(program, "uTexture"),
      opacity: gl.getUniformLocation(program, "uOpacity"),
      maskPass: gl.getUniformLocation(program, "uMaskPass"),
    };
    positionBuffer = gl.createBuffer();
    uvBuffer = gl.createBuffer();
    indexBuffer = gl.createBuffer();
    const modelBytes = await loadAsset(selected.model, "arraybuffer");
    moc = runtime.Moc.fromArrayBuffer(modelBytes);
    if (!moc) throw new Error("Cubism Core 无法读取人物模型");
    model = runtime.Model.fromMoc(moc);
    if (!model) throw new Error("无法创建 Live2D 人物");
    initializePose(selected.poseGroups);
    model.update();
    excludedDrawables = findExcludedDrawables();
    for (let index = 0; index < model.parameters.count; index++) {
      parameterIndex.set(model.parameters.ids[index], index);
    }
    textures = await Promise.all(selected.textures.map(loadTexture));
    gl.useProgram(program);
    gl.uniform1i(locations.texture, 0);
    gl.enable(gl.BLEND);
    gl.disable(gl.DEPTH_TEST);
    gl.disable(gl.CULL_FACE);
    resize();
    report("onReady", selected.name);
  }

  function pointer(event) {
    const bounds = canvas.getBoundingClientRect();
    state.targetX = Math.max(-1, Math.min(1, (event.clientX - bounds.left) / bounds.width * 2 - 1));
    state.targetY = Math.max(-1, Math.min(1, (event.clientY - bounds.top) / bounds.height * 2 - 1));
    if (legacyAvatar) legacyAvatar.focus(state.targetX, state.targetY);
  }

  canvas.addEventListener("pointerdown", event => {
    pointer(event);
    state.gestureAt = performance.now();
  });
  canvas.addEventListener("pointermove", pointer);
  canvas.addEventListener("pointerup", () => { state.targetX = 0; state.targetY = 0; });
  canvas.addEventListener("pointercancel", () => { state.targetX = 0; state.targetY = 0; });

  window.avatar = {
    setState(value) { state.phase = String(value || "idle"); },
    setSpeaking(value) { state.speaking = Boolean(value); },
    setViewMode(value) {
      state.viewMode = value === "full" ? "full" : "portrait";
      if (legacyModel) {
        fitLegacyAvatar();
        setTimeout(fitLegacyAvatar, 300);
      }
    },
    triggerGesture() { state.gestureAt = performance.now(); },
  };

  requestAnimationFrame(frame);
  initialize().catch(error => report("onError", String(error && error.message || error)));
})();
