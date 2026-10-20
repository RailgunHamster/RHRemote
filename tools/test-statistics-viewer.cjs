// Run the real offline report script against a minimal DOM; no desktop or browser control.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync(process.argv[2],'utf8'),script=html.split('<script>')[1].split('</script>')[0];
const elements=new Map(),ctx=new Proxy({measureText:s=>({width:String(s).length*7})},{get:(t,k)=>t[k]??(()=>{})});
function element(){return {textContent:'',children:[],style:{},append(...children){this.children.push(...children)},clientWidth:1200,getContext:()=>ctx};}
const document={getElementById(id){if(!elements.has(id))elements.set(id,element());return elements.get(id)},createElement:element};
vm.runInNewContext(script,{document,devicePixelRatio:1,addEventListener(){},console});
assert.match(elements.get('cards').children[0].children[1].textContent,/9\.817 秒/);
assert.match(elements.get('endpoints').textContent,/02:29:49\.562/);
assert.match(elements.get('range').textContent,/^0\.000–/);
assert.match(elements.get('scope').textContent,/不能用来证明首次连接的 37 秒等待已解决/);
const rows=elements.get('phases').children;assert.equal(rows.at(-1).children.at(-1).textContent,'9817.465');
elements.get('all').onclick();assert.match(elements.get('range').textContent,/^0\.000–/);
elements.get('startupView').onclick();assert.match(elements.get('range').textContent,/^0\.000–10\./);
console.log('PASS: 9.817s endpoints, phase total, zero origin and startup/full-stream controls');
