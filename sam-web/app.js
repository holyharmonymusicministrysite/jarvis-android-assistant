(() => {
const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];
const KEY='sam.operator.workspace.v1';
const state=load();
function load(){try{return JSON.parse(localStorage.getItem(KEY))||{notes:[],records:[],tasks:[],messages:[]}}catch{return{notes:[],records:[],tasks:[],messages:[]}}}
function save(){localStorage.setItem(KEY,JSON.stringify(state));renderAll()}
function id(){return Date.now().toString(36)+Math.random().toString(36).slice(2,7)}
function toast(s){const t=$('#toast');t.textContent=s;t.classList.add('show');setTimeout(()=>t.classList.remove('show'),2200)}
function esc(s){return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function addMessage(role,text,plan){state.messages.push({role,text,plan:plan||null});save();renderChat()}
function renderChat(){const c=$('#conversation');c.innerHTML=state.messages.map(m=>'<div class="message '+m.role+'">'+esc(m.text)+(m.plan?planHTML(m.plan):'')+'</div>').join('');c.querySelectorAll('[data-action]').forEach(b=>b.addEventListener('click',()=>act(b.dataset.action,b.dataset.id)));c.scrollTop=c.scrollHeight}
function planHTML(p){return '<div class="plan-card"><h3>Proposed action · '+esc(p.kind)+'</h3><p>'+esc(p.description)+'</p><div class="plan-actions"><button class="send-btn" data-action="approve" data-id="'+p.id+'">Approve & run</button><button class="danger-btn" data-action="reject" data-id="'+p.id+'">Reject</button></div></div>'}
function propose(kind,description,payload){const t={id:id(),kind,description,payload,status:'pending',created:new Date().toISOString()};state.tasks.unshift(t);save();return t}
function planFor(text){
const q=text.trim(),low=q.toLowerCase();
if(/^(note:|save (a )?note|remember )/.test(low)){
 const note=q.replace(/^(note:|save (a )?note:?|remember:?)/i,'').trim();
 return {kind:'Save note',description:'Save this note to SAM local memory: '+(note||q),payload:{type:'note',value:note||q}};
}
if(/^(search|research|look up|find information|google)\b/.test(low)){
 const term=q.replace(/^(search|research|look up|find information|google)\s*(for|about)?\s*/i,'').trim()||q;
 return {kind:'Web research',description:'Open a web search for: '+term,payload:{type:'search',value:term}};
}
if(/^(wikipedia|wiki)\b/.test(low)){
 const term=q.replace(/^(wikipedia|wiki)\s*/i,'').trim();
 return {kind:'Wikipedia lookup',description:'Fetch a short public encyclopedia summary for: '+term,payload:{type:'wiki',value:term}};
}
if(/^(create|make|build|plan|prepare)\b/.test(low)){
 return {kind:'Create project checklist',description:'Create a saved checklist from this goal: '+q,payload:{type:'checklist',value:q}};
}
if(/^(open|visit)\s+(https?:\/\/|www\.)/i.test(q)){
 const url=q.replace(/^(open|visit)\s+/i,'');
 return {kind:'Open website',description:'Open '+url,payload:{type:'url',value:url}};
}
return {kind:'Prepare work plan',description:'Break this request into a clear checklist for you to review: '+q,payload:{type:'checklist',value:q}};
}
function send(text){if(!text.trim())return;const q=text.trim();state.messages.push({role:'user',text:q});const p=planFor(q);const task={id:id(),...p,status:'pending',created:new Date().toISOString()};state.tasks.unshift(task);state.messages.push({role:'sam',text:'I have prepared a proposed action. Review the details and approve it before I execute anything. This local build can save notes, create checklists, open web searches and retrieve Wikipedia summaries.',plan:task});save();renderChat()}
async function act(action,taskId){const t=state.tasks.find(x=>x.id===taskId);if(!t)return;if(action==='reject'){t.status='rejected';save();renderChat();toast('Action rejected');return}if(action!=='approve')return;
const p=t.payload||{};try{if(p.type==='note'){state.notes.unshift({id:id(),text:p.value,created:new Date().toISOString()});t.status='completed';toast('Note saved')}
else if(p.type==='search'){t.status='completed';window.open('https://duckduckgo.com/?q='+encodeURIComponent(p.value),'_blank','noopener');toast('Search opened in a new tab')}
else if(p.type==='url'){let u=p.value;if(u.startsWith('www.'))u='https://'+u;const parsed=new URL(u);if(!['http:','https:'].includes(parsed.protocol))throw Error('Only web links are supported');t.status='completed';window.open(parsed.href,'_blank','noopener');toast('Website opened')}
else if(p.type==='wiki'){const res=await fetch('https://en.wikipedia.org/api/rest_v1/page/summary/'+encodeURIComponent(p.value));if(!res.ok)throw Error('No Wikipedia result found');const data=await res.json();const summary=(data.extract||'No summary available')+'\n\nSource: '+(data.content_urls?.desktop?.page||'Wikipedia');state.notes.unshift({id:id(),text:summary,created:new Date().toISOString()});t.status='completed';addMessage('sam','Research summary saved to Memory & notes:\n'+summary);toast('Summary saved')}
else if(p.type==='checklist'){const lines=p.value.split(/[.!?\n]+/).filter(Boolean);const items=lines.length>1?lines:[p.value];const text='PROJECT: '+p.value+'\n\nNEXT STEPS\n'+items.map((x,i)=>(i+1)+'. Clarify and complete: '+x.trim()).join('\n')+'\n'+(items.length===1?'2. Identify the required resources\n3. Set a deadline and success measure\n4. Review the result':'');state.notes.unshift({id:id(),text,created:new Date().toISOString()});t.status='completed';toast('Checklist saved to notes')}
save();renderChat()}catch(e){t.status='failed';t.error=e.message;save();toast(e.message)}}
function renderTasks(){$('#pendingBadge').textContent=state.tasks.filter(t=>t.status==='pending').length;const el=$('#taskList');el.innerHTML=state.tasks.length?state.tasks.map(t=>'<article class="panel-item"><div><h3>'+esc(t.kind)+' <span class="tag">· '+esc(t.status.toUpperCase())+'</span></h3><p>'+esc(t.description)+(t.error?'\nError: '+esc(t.error):'')+'</p><time>'+new Date(t.created).toLocaleString()+'</time></div>'+(t.status==='pending'?'<div class="item-actions"><button class="send-btn" data-task="approve" data-id="'+t.id+'">Approve</button><button class="danger-btn" data-task="reject" data-id="'+t.id+'">Reject</button></div>':'')+'</article>').join(''):'<div class="empty">No actions yet. Ask SAM to prepare a task.</div>';el.querySelectorAll('[data-task]').forEach(b=>b.onclick=()=>act(b.dataset.task,b.dataset.id))}
function renderNotes(){$('#notesList').innerHTML=state.notes.length?state.notes.map(n=>'<article class="panel-item"><div><p>'+esc(n.text)+'</p><time>'+new Date(n.created).toLocaleString()+'</time></div><button class="danger-btn" data-delete-note="'+n.id+'">Delete</button></article>').join(''):'<div class="empty">No saved notes yet.</div>';$$('[data-delete-note]').forEach(b=>b.onclick=()=>{state.notes=state.notes.filter(n=>n.id!==b.dataset.deleteNote);save()})}
function renderBusiness(){$('#businessList').innerHTML=state.records.length?state.records.map(r=>'<article class="panel-item"><div><h3>'+esc(r.name)+' <span class="tag">· '+esc(r.type)+'</span></h3><p>'+esc(r.detail||'No details')+'</p><time>'+new Date(r.created).toLocaleString()+'</time></div><button class="danger-btn" data-delete-record="'+r.id+'">Delete</button></article>').join(''):'<div class="empty">No business records yet. Add a customer, project or follow-up.</div>';$$('[data-delete-record]').forEach(b=>b.onclick=()=>{state.records=state.records.filter(r=>r.id!==b.dataset.deleteRecord);save()})}
function renderAll(){renderTasks();renderNotes();renderBusiness()}
function view(name){$$('.view').forEach(v=>v.classList.toggle('active',v.id===name+'View'));$$('.nav').forEach(b=>b.classList.toggle('active',b.dataset.view===name));$('#viewTitle').textContent=({chat:'Command centre',tasks:'Task approvals',memory:'Memory & notes',business:'Business desk',files:'Files & exports'})[name];$('#sidebar').classList.remove('open')}
$$('.nav').forEach(b=>b.onclick=()=>view(b.dataset.view));$('#menuBtn').onclick=()=>$('#sidebar').classList.toggle('open');$('#newChat').onclick=()=>{state.messages=[];save();renderChat();view('chat')};
$('#composer').onsubmit=e=>{e.preventDefault();const p=$('#prompt');send(p.value);p.value=''};
$$('.suggestion').forEach(b=>b.onclick=()=>send(b.dataset.prompt));
$('#noteForm').onsubmit=e=>{e.preventDefault();state.notes.unshift({id:id(),text:$('#noteInput').value,created:new Date().toISOString()});$('#noteInput').value='';save();toast('Note saved')};
$('#businessForm').onsubmit=e=>{e.preventDefault();state.records.unshift({id:id(),name:$('#businessName').value,type:$('#businessType').value,detail:$('#businessDetail').value,created:new Date().toISOString()});e.target.reset();save();toast('Record added')};
function download(name,content,type='application/json'){const a=document.createElement('a'),url=URL.createObjectURL(new Blob([content],{type}));a.href=url;a.download=name;a.click();URL.revokeObjectURL(url)}
$('#exportAll').onclick=()=>download('sam-workspace-backup.json',JSON.stringify(state,null,2));$('#downloadNotes').onclick=()=>download('sam-notes.txt',state.notes.map(n=>n.text).join('\n\n---\n\n'),'text/plain');
$('#filePicker').onchange=async e=>{const f=e.target.files[0];if(!f)return;$('#filePreview').textContent=await f.text()};
$('#voiceBtn').onclick=()=>{const SR=window.SpeechRecognition||window.webkitSpeechRecognition;if(!SR){toast('Voice input is not supported in this browser');return}const r=new SR();r.lang='en-KE';r.interimResults=false;r.onresult=e=>{$('#prompt').value=e.results[0][0].transcript;$('#prompt').focus()};r.onerror=()=>toast('Voice input failed. Check microphone permission.');r.start()};
if('serviceWorker'in navigator&&location.protocol.startsWith('http'))navigator.serviceWorker.register('./sw.js').catch(()=>{});
let installPrompt;window.addEventListener('beforeinstallprompt',e=>{e.preventDefault();installPrompt=e;$('#installBtn').classList.remove('hidden')});$('#installBtn').onclick=async()=>{if(installPrompt){installPrompt.prompt();installPrompt=null}else toast('Use your browser menu and choose Install app')};
renderAll();renderChat();
})();