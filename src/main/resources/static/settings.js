import {app,h,say,api,run,f1,svg,ICONS} from './util.js';

ICONS.trash='<polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>';

// one card per place: solar checkbox + energy parameters. Returns the element and a save() used by the page's single Save button
function placeCard(l,reload){
  const num=(v,attrs)=>h('input',{type:'number',required:true,value:v,style:'width:100%',...attrs});
  const solar=h('input',{type:'checkbox',checked:l.solar});
  const bat=num(l.batteryKwh,{step:'0.1',min:0}),kwp=num(l.solarKwp,{step:'0.1',min:0}),pr=num(l.pvRatio,{step:'0.05',min:0.1,max:1});
  const fields=h('div',{cls:'fields',hidden:!l.solar},h('span',{},'Капацитет на батерията (kWh)'),bat,h('span',{},'Мощност на соларните панели (kWp)'),kwp,h('span',{},'Коефициент на ефективност'),pr);
  solar.addEventListener('change',()=>{fields.hidden=!solar.checked});
  const save=()=>api('/locations/'+l.id,'PUT',{solar:solar.checked,batteryKwh:+bat.value,solarKwp:+kwp.value,pvRatio:+pr.value});
  const el=h('div',{cls:'card'},
    h('div',{cls:'row'},h('h2',{},l.name,' ',h('span',{cls:'mut'},l.country||'')),
      h('button',{type:'button',cls:'ib del',title:'Премахни',"aria-label":'Премахни '+l.name,onclick:run(async()=>{if(confirm('Премахване на '+l.name+'?')){await api('/locations/'+l.id,'DELETE');reload()}})},svg(ICONS.trash))),
    h('div',{cls:'panel'},h('label',{cls:'inl'},solar,' Соларна система'),fields));
  return {el,save,solar,id:l.id};
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
  const cards=locs.map(l=>placeCard(l,reload));
  const adder=addPlacePanel(reload,locs);
  const head=h('div',{cls:'row sechead'},h('h2',{},'Места'),h('button',{type:'button',onclick:()=>adder.toggle()},'+ Добави място'));
  const form=h('form',{onsubmit:run(async()=>{for(const c of cards)await c.save();say('Запазено')})},
    ...cards.map(c=>c.el),
    cards.length?h('div',{cls:'actions'},h('button',{},'Запази')):h('p',{cls:'mut'},'Още няма добавени места.'));
  app.replaceChildren(head,adder.el,form);
  if(!locs.length)adder.setOpen(true);
  const added=cards.find(c=>c.id===focusId);
  if(added){added.el.classList.add('flash');added.el.scrollIntoView({behavior:'smooth',block:'center'});added.solar.focus({preventScroll:true})}
}
