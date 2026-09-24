// Offline validation of every bundled resource and the actual Cubism 3 framing code.
const fs = require('fs'), path = require('path'), vm = require('vm');
const root = path.resolve(__dirname, '../app/src/main/assets/live2d');
const out = path.resolve(__dirname, '../artifacts/avatar-audit');
fs.mkdirSync(out, {recursive:true});
const catalog = JSON.parse(fs.readFileSync(path.join(root, 'catalog.json')));
const context = {console: {log(){}, warn(){}, error:console.error}, require, process, Buffer, setTimeout, clearTimeout, WebAssembly, __dirname:root};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(root, 'live2dcubismcore.min.js'), 'utf8'), context);
const exists = p => fs.existsSync(path.join(root, p));
const reports = [];
setTimeout(() => {
  for (const e of catalog) {
    const r = {id:e.id, name:e.name, generation:e.generation, errors:[], warnings:[]};
    let model, moc;
    try {
      let files = [e.preview];
      if (e.generation === 2) {
        files.push(e.manifest);
        const manifest = JSON.parse(fs.readFileSync(path.join(root, e.manifest)));
        const base = path.dirname(e.manifest);
        const refs = [manifest.model, ...(manifest.textures || []), manifest.physics, manifest.pose,
          ...(manifest.expressions || []).map(x=>x.file),
          ...Object.values(manifest.motions || {}).flat().map(x=>x.file)].filter(Boolean);
        files.push(...refs.map(p=>path.join(base,p)));
      } else {
        files.push(e.model, ...e.textures);
        const bytes = fs.readFileSync(path.join(root,e.model));
        moc = context.Live2DCubismCore.Moc.fromArrayBuffer(bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.length));
        model = context.Live2DCubismCore.Model.fromMoc(moc);
        const sandbox = {window:{Live2DCubismCore:context.Live2DCubismCore}, performance:{now:()=>0},
          document:{getElementById:()=>({addEventListener(){}})}, Map, Set, console, __model:model, __selected:e};
        vm.createContext(sandbox);
        let source = fs.readFileSync(path.join(root,'avatar.js'),'utf8');
        source = source.replace('  requestAnimationFrame(frame);\n  initialize().catch',
          `  model = __model;
  initializePose(__selected.poseGroups);
  model.update();
  framingParts = new Set(__selected.framingParts || []);
  excludedDrawables = findExcludedDrawables(__selected.excludedParts, __selected.excludedDrawables);
  window.audit = {visibleBounds, drawableBounds, excludedDrawables, effectiveDrawableOpacity};
  return;
  initialize().catch`);
        vm.runInContext(source,sandbox);
        const api = sandbox.window.audit;
        if (!api) throw new Error('Audit hook could not bind framing implementation');
        const b = api.visibleBounds();
        r.bounds=b; r.aspect=(b.maxX-b.minX)/(b.maxY-b.minY);
        const d=model.drawables;
        const visible=Array.from(d.ids).map((id,i)=>({id,i,b:api.drawableBounds(i), opacity:api.effectiveDrawableOpacity(i)}))
          .filter(x=>!api.excludedDrawables.has(x.i) && x.opacity>.001 && d.indexCounts[x.i]);
        r.visible=visible.length;
        r.largest=visible.sort((a,b)=>b.b.area-a.b.area).slice(0,8).map(x=>({id:x.id,b:x.b}));
        r.parts=Array.from(model.parts.ids).map((id,i)=>({id,parent:model.parts.parentIndices[i]}));
        r.meshes=Array.from(d.ids).map((id,i)=>({id,part:model.parts.ids[d.parentPartIndices[i]],opacity:api.effectiveDrawableOpacity(i),b:api.drawableBounds(i),excluded:api.excludedDrawables.has(i)}));
        if (r.largest.length>1 && r.largest[0].b.area>r.largest[1].b.area*10) r.warnings.push('dominant helper geometry');
        r.effects=visible.filter(x=>/baiwu|BG|background|yanwu|light|particle|snow|heise/i.test(x.id)).map(x=>x.id);
        if (r.aspect>1.2 || r.aspect<.15) r.warnings.push('unusual aspect ratio');
        if (r.effects.length) r.warnings.push('visible environment/effect meshes');
        if (!Number.isFinite(r.aspect) || !r.visible) r.errors.push('invalid geometry');
        for (const textureIndex of d.textureIndices) if(textureIndex<0||textureIndex>=e.textures.length) r.errors.push('invalid texture index');
      }
      for (const file of files) if(!exists(file)) r.errors.push('Missing: '+file);
    } catch(error) {r.errors.push(String(error));}
    finally { if(model)model.release(); if(moc)moc._release(); }
    reports.push(r);
  }
  fs.writeFileSync(path.join(out,'catalog-audit.json'),JSON.stringify(reports,null,2));
  console.log(JSON.stringify({total:reports.length,errors:reports.filter(r=>r.errors.length),
    candidates:reports.filter(r=>r.warnings.length).map(({name,id,warnings,aspect,effects})=>({name,id,warnings,aspect,effects}))},null,2));
},500);
