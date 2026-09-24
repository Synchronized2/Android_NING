const fs = require('fs');
const vm = require('vm');
const path = require('path');
const root = path.resolve(__dirname, '../app/src/main/assets/live2d');
const context = { console, require, process, Buffer, setTimeout, clearTimeout, WebAssembly, __dirname: root };
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(root, 'live2dcubismcore.min.js'), 'utf8'), context);
setTimeout(() => {
  const core = context.Live2DCubismCore;
  const catalog = JSON.parse(fs.readFileSync(path.join(root, 'catalog.json')));
  for (const name of process.argv.slice(2)) {
    const entry = catalog.find(e => e.name === name);
    const bytes = fs.readFileSync(path.join(root, entry.model));
    const moc = core.Moc.fromArrayBuffer(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
    const model = core.Model.fromMoc(moc);
    model.update();
    const d = model.drawables;
    const belongsTo = (i, ids) => {
      let p = d.parentPartIndices[i];
      while (p >= 0) {
        if ((ids || []).includes(model.parts.ids[p])) return true;
        p = model.parts.parentIndices[p];
      }
      return false;
    };
    const rows = Array.from(d.ids).map((id, i) => {
      const v = d.vertexPositions[i];
      const x = Array.from(v).filter((_,j) => j%2 === 0), y = Array.from(v).filter((_,j) => j%2 === 1);
      const b = [Math.min(...x), Math.max(...x), Math.min(...y), Math.max(...y)];
      return {id, body:belongsTo(i, entry.framingParts), excluded:belongsTo(i, entry.excludedParts), opacity:d.opacities[i], b, area:(b[1]-b[0])*(b[3]-b[2])};
    }).sort((a,b)=>b.area-a.area);
    const visible = rows.filter(r=>r.opacity > .001 && !/touch|hitarea/i.test(r.id));
    const body = visible.filter(r=>r.body && !r.excluded);
    const bounds = list => [Math.min(...list.map(r=>r.b[0])), Math.max(...list.map(r=>r.b[1])), Math.min(...list.map(r=>r.b[2])), Math.max(...list.map(r=>r.b[3]))];
    const before = bounds(visible), after = bounds(body);
    if (body.length < 20 || after.some(v=>!Number.isFinite(v))) throw new Error('Missing character geometry');
    if (!rows.filter(r=>/^baiwu/.test(r.id)).every(r=>r.excluded)) throw new Error('Stage fog not excluded');
    if ((after[1]-after[0]) >= (before[1]-before[0]) / 3) throw new Error('Stage still affects framing');
    console.log(name, 'PASS', JSON.stringify({before, after, bodyMeshes:body.length}));
    model.release(); moc._release();
  }
}, 500);
