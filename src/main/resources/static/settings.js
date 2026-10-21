import {app,h,say,api,run,f1,svg,ICONS} from './util.js';

ICONS.refresh='<polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>';
ICONS.up='<polyline points="18 15 12 9 6 15"/>';
ICONS.down='<polyline points="6 9 12 15 18 9"/>';
ICONS.grip='<circle cx="9" cy="6" r="1"/><circle cx="15" cy="6" r="1"/><circle cx="9" cy="12" r="1"/><circle cx="15" cy="12" r="1"/><circle cx="9" cy="18" r="1"/><circle cx="15" cy="18" r="1"/>';
ICONS.trash='<polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>';

// one card per place: solar checkbox + energy parameters. Returns the element and a save() used by the page's single Save button
function placeCard(l,reload,move){
  const solar=h('input',{type:'checkbox',checked:l.solar});
  const text=(v,attrs)=>h('input',{type:'text',value:v||'',style:'width:100%',autocomplete:'off',maxLength:100,...attrs});
  const cid=text(l.solaxClientId);
  // plants of the SolaX account, known once the credentials have connected; the first one is selected by default
  const plant=h('select',{style:'width:100%'}),plantLabel=h('span',{},'Централа');
  const fillPlants=r=>{
    const ps=r.solaxPlants||[],names=ps.map(p=>p.name);
    plant.replaceChildren(...ps.map((p,i)=>h('option',{value:p.id},names.indexOf(p.name)===i&&names.lastIndexOf(p.name)===i?p.name:p.name+' ('+p.id+')')));
    plant.value=ps.some(p=>p.id===r.solaxPlantId)?r.solaxPlantId:(ps[0]?ps[0].id:'');
    plant.hidden=plantLabel.hidden=!ps.length;
  };
  const sec=h('input',{type:'password',style:'width:100%',autocomplete:'new-password',maxLength:200,placeholder:l.solaxHasSecret?'•••••••• (запазен)':''});
  const status=h('div',{cls:'status'}),battery=h('div',{cls:'status'}),capacity=h('div',{cls:'status mut'});
  const showStatus=r=>{status.textContent=r.solaxStatus||'';status.className='status '+((r.solaxStatus||'').startsWith('Свързано')?'okc':'bad');status.hidden=!r.solaxStatus;
    const bs=r.solaxBatteryStatus||'';battery.textContent=bs;battery.className='status '+(bs.startsWith('Заряд')?'okc':'bad');battery.hidden=!bs;
    capacity.textContent=r.solaxBatteryInfo||'';capacity.hidden=!r.solaxBatteryInfo;
    sec.value='';sec.placeholder=r.solaxHasSecret?'•••••••• (запазен)':'';fillPlants(r)};
  showStatus(l);
  const refresh=h('button',{type:'button',cls:'ib',title:'Опресни данните',"aria-label":'Опресни данните за '+l.name,hidden:!l.solar,onclick:run(async()=>{showStatus(await api('/locations/'+l.id+'/refresh','POST'));say('Данните са опреснени')})},svg(ICONS.refresh));
  const supplier=h('select',{style:'width:100%'},...[['electrohold','Electrohold'],['evn','EVN'],['energopro','Energo-Pro']].map(([v,n])=>h('option',{value:v},n)));
  supplier.value=l.supplier||'electrohold';
  const reserve=h('input',{type:'number',min:0,max:50,step:1,value:l.reservedPct??0,style:'width:100%',title:'Заряд на батерията (%), под който инверторът не я разрежда'});
  const maxCharge=h('input',{type:'number',min:0.5,max:6,step:0.5,value:(l.maxChargeW??2000)/1000,style:'width:100%',title:'Най-голямата мощност (kW), с която инверторът зарежда батерията от мрежата'});
  const control=h('input',{type:'checkbox',checked:!!l.allowControl});
  // the management panel: the inverter is programmed to charge the battery only when this is on
  const manage=h('div',{cls:'fields',hidden:!l.allowControl},h('span',{},'Резервирана батерия (%)'),reserve,h('span',{},'Макс. мощност на зареждане (kW)'),maxCharge);
  control.addEventListener('change',()=>{manage.hidden=!control.checked});
  const panel=h('div',{cls:'panel'},h('label',{cls:'inl'},control,' Разреши управлене'),manage);
  const fields=h('div',{cls:'fields'},
    h('span',{},'Доставчик на ток'),supplier,h('span',{},'Client ID'),cid,h('span',{},'Client Secret'),sec,plantLabel,plant,status,battery,capacity);
  // the connection settings on the left, the management panel to their right (below them on narrow screens)
  const cols=h('div',{cls:'cols',hidden:!l.solar},fields,panel);
  solar.addEventListener('change',()=>{cols.hidden=refresh.hidden=!solar.checked});
  const save=async()=>showStatus(await api('/locations/'+l.id,'PUT',{solar:solar.checked,supplier:supplier.value,reservedPct:Math.min(50,Math.max(0,Math.round(+reserve.value)||0)),allowControl:control.checked,maxChargeW:Math.min(6000,Math.max(500,Math.round(+maxCharge.value*1000)||2000)),
    solaxClientId:cid.value,solaxClientSecret:sec.value,solaxPlantId:plant.value}));
  const up=h('button',{type:'button',cls:'ib',title:'Нагоре',"aria-label":'Премести '+l.name+' нагоре',onclick:()=>move(-1)},svg(ICONS.up));
  const down=h('button',{type:'button',cls:'ib',title:'Надолу',"aria-label":'Премести '+l.name+' надолу',onclick:()=>move(1)},svg(ICONS.down));
  const grip=h('span',{cls:'grip',title:'Плъзнете, за да преместите',"aria-hidden":'true'},svg(ICONS.grip));
  const el=h('div',{cls:'card'},
    h('div',{cls:'row'},h('h2',{},grip,l.name,' ',h('span',{cls:'mut'},l.country||'')),
      h('span',{cls:'btns'},up,down,
      h('button',{type:'button',cls:'ib del',title:'Премахни',"aria-label":'Премахни '+l.name,onclick:run(async()=>{if(confirm('Премахване на '+l.name+'?')){await api('/locations/'+l.id,'DELETE');reload()}})},svg(ICONS.trash)))),
    h('div',{cls:'panel'},h('div',{cls:'row'},h('label',{cls:'inl'},solar,' Solax Cloud'),refresh),cols));
  return {el,save,solar,id:l.id,up,down,grip};
}

// search panel opened from the "+ Добави място" button at the top of the places section
function addPlacePanel(reload,existing){
  const q=h('input',{placeholder:'Търсене на населено място, напр. София',size:30,required:true}),res=h('div',{cls:'results'});
  const has=g=>existing.some(l=>l.name===g.name&&(l.country||'')===(g.country||''));
  const add=g=>run(async()=>{const l=await api('/locations','POST',{name:g.name,country:g.country,lat:g.latitude,lon:g.longitude,tz:g.timezone});reload(l.id)});
  const search=run(async()=>{
    const hits=await api('/geocode?q='+encodeURIComponent(q.value));
    res.replaceChildren(...(hits.length?hits.map(g=>h('div',{cls:'hit'},
      has(g)?h('span',{cls:'mut'},'добавено'):h('button',{type:'button',cls:'l',onclick:add(g)},'+ Добави'),
      h('span',{},' '+g.name+(g.admin1?', '+g.admin1:'')+(g.country?', '+g.country:'')+' '),h('span',{cls:'mut'},f1(g.latitude,2)+'; '+f1(g.longitude,2)))):[h('div',{cls:'mut'},'Няма резултати')]));});
  const el=h('div',{cls:'card',hidden:true},
    h('form',{onsubmit:search},q,' ',h('button',{},'Търси'),' ',h('button',{type:'button',cls:'l',onclick:()=>setOpen(false)},'Затвори')),res);
  const setOpen=v=>{el.hidden=!v;if(v)q.focus()};
  return {el,setOpen,toggle:()=>setOpen(el.hidden)};
}

/** Settings page: a "Места" header with the add button, one card per place, and a single Save button for all of them. */
export async function showSettings(focusId){
  const locs=await api('/locations');
  const reload=id=>showSettings(id);
  const cards=[];
  // reordering moves the cards in place (unsaved edits stay) and stores the new order at once
  const sync=()=>{cards.forEach((c,i)=>{c.up.disabled=i===0;c.down.disabled=i===cards.length-1;c.grip.hidden=cards.length<2;form.append(c.el)});
    if(actions)form.append(actions)};
  const persist=run(()=>api('/locations/order','PUT',{ids:cards.map(c=>c.id)}));
  const place=(c,to)=>{const i=cards.indexOf(c);if(to<0||to>=cards.length||to===i)return;cards.splice(i,1);cards.splice(to,0,c);sync();persist()};
  locs.forEach(l=>{const c=placeCard(l,reload,d=>place(c,cards.indexOf(c)+d));cards.push(c)});
  // drag and drop (mouse): only the grip starts a drag, so the inputs stay usable; the card is dropped before or after the one under the pointer
  let dragged=null;
  const clear=()=>cards.forEach(c=>c.el.classList.remove('dropbefore','dropafter'));
  for(const c of cards){
    c.grip.addEventListener('mousedown',()=>{c.el.draggable=true});
    c.el.addEventListener('dragstart',e=>{if(!c.el.draggable)return;dragged=c;e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('text/plain',String(c.id));c.el.classList.add('dragging')});
    c.el.addEventListener('dragend',()=>{dragged=null;c.el.draggable=false;c.el.classList.remove('dragging');clear()});
    c.el.addEventListener('dragover',e=>{if(!dragged||dragged===c)return;e.preventDefault();clear();const r=c.el.getBoundingClientRect();c.el.classList.add(e.clientY<r.top+r.height/2?'dropbefore':'dropafter')});
    c.el.addEventListener('drop',e=>{if(!dragged||dragged===c)return;e.preventDefault();const r=c.el.getBoundingClientRect(),after=e.clientY>=r.top+r.height/2,d=dragged;clear();
      const from=cards.indexOf(d);let to=cards.indexOf(c)+(after?1:0);if(from<to)to--;place(d,to)});
  }
  const adder=addPlacePanel(reload,locs);
  const head=h('div',{cls:'row sechead'},h('h2',{},'Места'),h('button',{type:'button',onclick:()=>adder.toggle()},'+ Добави място'));
  const actions=cards.length?h('div',{cls:'actions'},h('button',{},'Запази')):null;
  const form=h('form',{onsubmit:run(async()=>{for(const c of cards)await c.save();say('Запазено')})},
    ...cards.map(c=>c.el),actions||h('p',{cls:'mut'},'Още няма добавени места.'));
  sync();
  app.replaceChildren(head,adder.el,form);
  if(!locs.length)adder.setOpen(true);
  const added=cards.find(c=>c.id===focusId);
  if(added){added.el.classList.add('flash');added.el.scrollIntoView({behavior:'smooth',block:'center'});added.solar.focus({preventScroll:true})}
}
