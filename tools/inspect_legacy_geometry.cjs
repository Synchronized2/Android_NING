const fs=require('fs'),vm=require('vm'),path=require('path');
const root=path.resolve(__dirname,'../app/src/main/assets/live2d');
const sandbox={console, navigator:{userAgent:'Node',platform:'Win32',appVersion:'Node'}, ArrayBuffer,DataView,Float32Array,Uint8Array, setTimeout,clearTimeout,window:null};
sandbox.window=sandbox; sandbox.document={};
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(path.join(root,'live2d-legacy.js'),'utf8'),sandbox);
const catalog=JSON.parse(fs.readFileSync(path.join(root,'catalog.json')));
for(const query of process.argv.slice(2)) {
 const e=catalog.find(e=>e.name===query); const manifest=JSON.parse(fs.readFileSync(path.join(root,e.manifest)));
 const bytes=fs.readFileSync(path.join(root,path.dirname(e.manifest),manifest.model));
 const model=sandbox.Live2DModelWebGL.loadModel(bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.length));
 model.getModelContext().update();
 const ctx=model.getModelContext();
 const rows=[];
 for(let i=0;i<ctx._$aS.length;i++) {
  const mesh=ctx.getDrawData(i), state=ctx._$C2(i), v=model.getTransformedPoints(i);
  if(!v)continue;
  const x=Array.from(v).filter((_,j)=>j%2===0), y=Array.from(v).filter((_,j)=>j%2===1);
  const uv=mesh.getUVs ? Array.from(mesh.getUVs()) : [];
  rows.push({id:mesh.getDrawDataID().id, visible:state._$yo(), partOpacity:model.getPartsOpacity(state._$IP), opacity:mesh.getOpacity(ctx,state)*state.baseOpacity,
   uv:uv.length ? [Math.min(...uv.filter((_,j)=>j%2===0)),Math.max(...uv.filter((_,j)=>j%2===0)),Math.min(...uv.filter((_,j)=>j%2===1)),Math.max(...uv.filter((_,j)=>j%2===1))]:[],
   bounds:[Math.min(...x),Math.max(...x),Math.min(...y),Math.max(...y)]});
 }
 console.log(query,JSON.stringify(rows.filter(r=>r.bounds.some(n=>n<0||n>3500)),null,2));
}
