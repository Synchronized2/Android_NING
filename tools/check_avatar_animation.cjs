// Exercise real Cubism 3 deformation across waiting/answering state transitions.
const fs = require('fs'), path = require('path'), vm = require('vm');
const root = path.resolve(__dirname, '../app/src/main/assets/live2d');
const context = {console, require, process, Buffer, setTimeout, clearTimeout, WebAssembly, __dirname:root};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(root, 'live2dcubismcore.min.js'), 'utf8'), context);
setTimeout(() => {
  const catalog = JSON.parse(fs.readFileSync(path.join(root, 'catalog.json')));
  for (const entry of catalog.filter(e => e.generation === 3 && (!process.argv[2] || e.name === process.argv[2]))) {
    const bytes = fs.readFileSync(path.join(root, entry.model));
    const core = context.Live2DCubismCore;
    const moc = core.Moc.fromArrayBuffer(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.length));
    const model = core.Model.fromMoc(moc);
    let now = 0;
    const sandbox = {window:{Live2DCubismCore:core}, performance:{now:()=>now},
      document:{getElementById:()=>({addEventListener(){}})}, console, __model:model, __selected:entry};
    vm.createContext(sandbox);
    let source = fs.readFileSync(path.join(root, 'avatar.js'), 'utf8');
    source = source.replace('  requestAnimationFrame(frame);\n  initialize().catch', `
      model = __model;
      parameterAliases = __selected.parameterAliases || {};
      initializePose(__selected.poseGroups);
      for (let i=0;i<model.parameters.count;i++) parameterIndex.set(model.parameters.ids[i],i);
      window.audit = {updateParameters};
      return;
      initialize().catch`);
    vm.runInContext(source, sandbox);
    const changes = {};
    for (const phase of ['idle', 'thinking', 'answering']) {
      sandbox.window.avatar.setState(phase);
      const snapshots = [];
      for (const t of [1000, 1700, 2300, 4050, 4300]) {
        now = t;
        sandbox.window.audit.updateParameters(t);
        snapshots.push(Array.from(model.drawables.vertexPositions).flatMap(v=>Array.from(v)));
      }
      changes[phase] = Math.max(...snapshots.slice(1).map(s=>s.reduce((m,v,i)=>Math.max(m,Math.abs(v-snapshots[0][i])),0)));
    }
    const params = Array.from(model.parameters.ids).map((id,i)=>({id,min:model.parameters.minimumValues[i],max:model.parameters.maximumValues[i],value:model.parameters.values[i]}));
    console.log(JSON.stringify({name:entry.name,changes,...(process.argv[2] ? {params} : {})}));
    if (Object.values(changes).some(change => !Number.isFinite(change) || change <= 0)) process.exitCode = 1;
    model.release(); moc._release();
  }
}, 500);
