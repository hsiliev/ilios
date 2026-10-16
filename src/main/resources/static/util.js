// Shared helpers: DOM builder, API calls, Bulgarian number/date formats, SVG icons.
export const $=s=>document.querySelector(s),app=$('#app');
export const h=(t,a={},...k)=>{const e=document.createElement(t);for(const[n,v]of Object.entries(a))n.startsWith('on')?e.addEventListener(n.slice(2),v):n==='cls'?e.className=v:e[n]=v;e.append(...k.flat().filter(x=>x!=null));return e};
export const say=(t,err)=>{const m=$('#msg');m.hidden=!t;m.textContent=t||'';m.style.color=err?'var(--yes)':''};
export async function api(path,method='GET',body){
  const r=await fetch('/api'+path,{method,headers:body?{'Content-Type':'application/json'}:{},body:body&&JSON.stringify(body)});
  const d=await r.json().catch(()=>({}));
  if(!r.ok){const e=new Error(d.message||r.statusText);e.status=r.status;throw e}
  return d}
export const run=f=>async e=>{e&&e.preventDefault&&e.preventDefault();say();try{await f(e)}catch(x){say(x.message,1)}};
// Bulgarian formats: decimal comma, dd.MM.yyyy dates (API dates are ISO yyyy-MM-dd)
export const f1=(n,d=1)=>n==null?'–':n.toLocaleString('bg-BG',{minimumFractionDigits:0,maximumFractionDigits:d});
export const dm=iso=>iso.slice(8,10)+'.'+iso.slice(5,7);
export const dmy=iso=>dm(iso)+'.'+iso.slice(0,4);


export const svg=(body,cls='')=>{const e=document.createElementNS('http://www.w3.org/2000/svg','svg');e.setAttribute('viewBox','0 0 24 24');e.setAttribute('class','ic '+cls);e.setAttribute('aria-hidden','true');e.innerHTML=body;return e};
const DROP='<path d="M12 3s6 7 6 11a6 6 0 0 1-12 0c0-4 6-11 6-11z"/>';
export const ICONS={
  plants:'<path d="M12 14V9"/><path d="M12 10c0-3-2-5-5-5 0 3 2 5 5 5z"/><path d="M12 9c0-3 2-5 5-5 0 3-2 5-5 5z"/><path d="M7 14h10l-1 7H8z"/>',
  grass:'<path d="M5 21c1-5 1-9-1-13"/><path d="M9 21c0-6 0-11 2-16"/><path d="M14 21c0-6 1-10 3-13"/><path d="M19 21c0-3 0-5 1-7"/><path d="M2 21h20"/>',
  trees:'<path d="M12 21v-7"/><path d="M12 2c-4 3-7 6-7 9 0 2.5 3 4 7 4s7-1.5 7-4c0-3-3-6-7-9z"/>',
  yes:DROP, light:DROP, no:DROP+'<path d="M4 4l16 16"/>',
};
ICONS.rain='<line x1="16" y1="13" x2="16" y2="21"/><line x1="8" y1="13" x2="8" y2="21"/><line x1="12" y1="15" x2="12" y2="23"/><path d="M20 16.58A5 5 0 0 0 18 7h-1.26A8 8 0 1 0 4 15.25"/>';
