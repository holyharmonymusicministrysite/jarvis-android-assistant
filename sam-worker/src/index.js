const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };
const MODEL = "@cf/zai-org/glm-4.7-flash";
const cors = (request, env) => {
  const origin = request.headers.get("Origin") || "";
  const allowed = env.SAM_ORIGIN || "https://holyharmonymusicministrysite.github.io";
  return origin === allowed ? { "access-control-allow-origin": origin, "access-control-allow-methods": "GET,POST,OPTIONS", "access-control-allow-headers": "authorization,content-type", "access-control-max-age": "86400", "vary": "Origin" } : {};
};
const json = (data, status=200, headers={}) => new Response(JSON.stringify(data), {status,headers:{...JSON_HEADERS,...headers}});
const digits = value => String(value || "").replace(/\D/g,"");
async function authorized(request, env) {
  const token = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i,"");
  if (!env.SAM_OWNER_KEY || !token) return false;
  const a = new TextEncoder().encode(token), b = new TextEncoder().encode(env.SAM_OWNER_KEY);
  if (a.length !== b.length) return false;
  const digest = async x => new Uint8Array(await crypto.subtle.digest("SHA-256",x));
  const [da,db] = await Promise.all([digest(a),digest(b)]);
  return da.every((v,i)=>v===db[i]);
}
async function verifyMetaSignature(raw, signature, secret) {
  if (!signature || !secret) return false;
  const key = await crypto.subtle.importKey("raw",new TextEncoder().encode(secret),{name:"HMAC",hash:"SHA-256"},false,["sign"]);
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC",key,new TextEncoder().encode(raw)));
  const hex = [...mac].map(x=>x.toString(16).padStart(2,"0")).join("");
  const supplied = signature.replace(/^sha256=/,"").toLowerCase();
  return hex.length===supplied.length && [...hex].every((v,i)=>v===supplied[i]);
}
async function aiReply(env, incoming, channel="web") {
  const recent = env.DB ? await env.DB.prepare("SELECT role, content FROM messages WHERE owner_id = ? ORDER BY id DESC LIMIT 12").bind("owner").all() : {results:[]};
  const memories = env.DB ? await env.DB.prepare("SELECT content FROM memories WHERE owner_id = ? ORDER BY id DESC LIMIT 15").bind("owner").all() : {results:[]};
  const history = (recent.results || []).reverse().map(m=>({role:m.role,content:m.content}));
  const memoryText = (memories.results || []).map(m=>m.content).join("\n");
  const messages = [
    {role:"system",content:"You are SAM, the user's practical personal AI operator. Be capable, direct, honest, and concise. Help with research, planning, writing, software engineering, and business operations. Never claim you executed an action unless a connected tool confirms it. You cannot directly control a phone or computer from this chat. For actions with external effects (messages, payments, deleting files, publishing, deployments, calls), prepare a clear proposal and wait for explicit user approval. Never ask for or reveal passwords, access tokens, or secret keys. Respect platform policies and applicable law. Use this saved context only when relevant:\n"+memoryText},
    ...history,
    {role:"user",content:incoming}
  ];
  const result = await env.AI.run(env.SAM_MODEL || MODEL,{messages,max_tokens:900,temperature:0.55});
  const answer = typeof result === "string" ? result : (result.response || result.result || JSON.stringify(result));
  if (env.DB) {
    await env.DB.prepare("INSERT INTO messages (owner_id, channel, role, content) VALUES (?, ?, ?, ?)").bind("owner",channel,"user",incoming.slice(0,12000)).run();
    await env.DB.prepare("INSERT INTO messages (owner_id, channel, role, content) VALUES (?, ?, ?, ?)").bind("owner",channel,"assistant",String(answer).slice(0,12000)).run();
  }
  return String(answer).slice(0,5000);
}
async function sendWhatsApp(env,to,text) {
  if (!env.WHATSAPP_TOKEN || !env.WHATSAPP_PHONE_NUMBER_ID) throw new Error("WhatsApp credentials are not configured.");
  const version = env.WHATSAPP_API_VERSION || "v24.0";
  const response = await fetch("https://graph.facebook.com/"+version+"/"+env.WHATSAPP_PHONE_NUMBER_ID+"/messages",{
    method:"POST",headers:{"authorization":"Bearer "+env.WHATSAPP_TOKEN,"content-type":"application/json"},
    body:JSON.stringify({messaging_product:"whatsapp",recipient_type:"individual",to:digits(to),type:"text",text:{preview_url:false,body:String(text).slice(0,4000)}})
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error?.message || "WhatsApp send failed ("+response.status+").");
  return data;
}
async function webhook(request, env) {
  const url = new URL(request.url);
  if (request.method === "GET") {
    const mode=url.searchParams.get("hub.mode"), token=url.searchParams.get("hub.verify_token"), challenge=url.searchParams.get("hub.challenge");
    if (mode==="subscribe" && token && token===env.WHATSAPP_VERIFY_TOKEN) return new Response(challenge,{status:200});
    return new Response("Webhook verification failed",{status:403});
  }
  const raw=await request.text();
  if (!(await verifyMetaSignature(raw,request.headers.get("x-hub-signature-256"),env.META_APP_SECRET))) return new Response("Invalid signature",{status:401});
  const body=JSON.parse(raw);
  for (const entry of body.entry || []) for (const change of entry.changes || []) {
    const value=change.value || {};
    for (const message of value.messages || []) {
      const sender=digits(message.from);
      if (!sender || sender!==digits(env.OWNER_WA_ID)) continue;
      let text="";
      if (message.type==="text") text=message.text?.body || "";
      else if (message.type==="button") text=message.button?.text || "";
      else if (message.type==="interactive") text=message.interactive?.button_reply?.title || message.interactive?.list_reply?.title || "";
      if (!text.trim()) continue;
      try {
        const answer=await aiReply(env,text,"whatsapp");
        await sendWhatsApp(env,sender,answer);
      } catch (error) {
        await sendWhatsApp(env,sender,"SAM received your message, but could not complete the response: "+error.message);
      }
    }
  }
  return new Response("EVENT_RECEIVED",{status:200});
}
export default {
  async fetch(request,env) {
    const url=new URL(request.url), headers=cors(request,env);
    if (request.method==="OPTIONS") return new Response(null,{status:204,headers});
    if (url.pathname==="/webhook") return webhook(request,env);
    if (url.pathname==="/health") return json({ok:true,service:"sam-operator-api",ai:!!env.AI,database:!!env.DB,whatsapp:!!(env.WHATSAPP_TOKEN&&env.WHATSAPP_PHONE_NUMBER_ID)},200,headers);
    if (url.pathname.startsWith("/api/") && !(await authorized(request,env))) return json({error:"Unauthorized. Configure your SAM owner key."},401,headers);
    try {
      if (url.pathname==="/api/chat" && request.method==="POST") {
        const body=await request.json(), message=String(body.message || "").trim();
        if (!message) return json({error:"Message is required."},400,headers);
        return json({reply:await aiReply(env,message,"web")},200,headers);
      }
      if (url.pathname==="/api/whatsapp/send" && request.method==="POST") {
        const body=await request.json(), text=String(body.message || "").trim();
        if (!text) return json({error:"Message is required."},400,headers);
        const result=await sendWhatsApp(env,env.OWNER_WA_ID,text);
        return json({ok:true,result},200,headers);
      }
      if (url.pathname==="/api/memory" && request.method==="GET") {
        if (!env.DB) return json({error:"D1 database is not connected."},503,headers);
        const rows=await env.DB.prepare("SELECT id, content, created_at FROM memories WHERE owner_id=? ORDER BY id DESC LIMIT 100").bind("owner").all();
        return json({items:rows.results||[]},200,headers);
      }
      if (url.pathname==="/api/memory" && request.method==="POST") {
        if (!env.DB) return json({error:"D1 database is not connected."},503,headers);
        const body=await request.json(), content=String(body.content||"").trim();
        if (!content) return json({error:"Memory content is required."},400,headers);
        await env.DB.prepare("INSERT INTO memories (owner_id,content) VALUES (?,?)").bind("owner",content.slice(0,5000)).run();
        return json({ok:true},201,headers);
      }
      if (url.pathname==="/api/whatsapp/call") return json({error:"WhatsApp calling is not enabled in this build. It needs Meta calling eligibility, explicit call permission, and a real-time audio/WebRTC or SIP media service."},501,headers);
      return json({error:"Not found"},404,headers);
    } catch(error) { return json({error:error.message||"Internal error"},500,headers); }
  }
};