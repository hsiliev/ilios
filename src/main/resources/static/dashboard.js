import {$,h,say,api,f1,svg,ICONS} from './util.js';
import {weatherChart,intraChart,powerChart} from './charts.js';

// collapsible place: a summary row with today's key figures, the charts and advice inside
const OPEN_KEY='ilios.open';
const readOpen=()=>{try{return JSON.parse(localStorage.getItem(OPEN_KEY)||'{}')}catch{return{}}};
ICONS.chev='<polyline points="9 6 15 12 9 18"/>';

// battery charge: icon whose fill follows the level, plus a text describing the last SolaX reading
const level=p=>p<20?'low':p<50?'mid':'ok';
const batIcon=(p,cls)=>svg('<rect x="1" y="6" width="18" height="12" rx="2"/><line x1="23" y1="13" x2="23" y2="11"/>'
  +`<rect x="3.5" y="8.5" width="${Math.max(0,Math.min(100,p))*0.135}" height="7" rx="1" fill="currentColor" stroke="none"/>`,'bat '+level(p)+' '+cls);
function batteryText(b){
  if(b.soc==null)return b.batteryKwh>0?'Капацитет на батерията: '+f1(b.batteryKwh)+' kWh':'';
  // the charge is read daily at 22:30 Sofia time, so show when (with the date once it is not today's)
  const tz={timeZone:'Europe/Sofia'},d=b.readAt?new Date(b.readAt):null;
  const time=!d?'':d.toLocaleTimeString('bg-BG',{...tz,hour:'2-digit',minute:'2-digit'})+(d.toLocaleDateString('bg-BG',tz)===new Date().toLocaleDateString('bg-BG',tz)?'':' на '+d.toLocaleDateString('bg-BG',{...tz,day:'2-digit',month:'2-digit'}));
  const kw=Math.abs(b.powerW||0)/1000;
  const power=b.powerW==null?'':b.powerW>50?', зарежда се с '+f1(kw)+' kW':b.powerW<-50?', разрежда се с '+f1(kw)+' kW':', в покой';
  const energy=b.remainingKwh!=null?' (≈ '+f1(b.remainingKwh)+' от '+f1(b.batteryKwh)+' kWh)':'';
  return 'Батерия: '+f1(b.soc,0)+'%'+energy+power+(time?' – към '+time:'');
}
function batteryLine(b){
  if(b.soc==null){
    if(b.batteryKwh>0){
      const t='Капацитет на батерията: '+f1(b.batteryKwh)+' kWh';
      return [h('div',{cls:'bline',title:t,'aria-label':t},batIcon(100,''),h('span',{},t))];
    }
    return [];
  }
  return [h('div',{cls:'bline',title:batteryText(b),'aria-label':batteryText(b)},batIcon(b.soc,''),
    h('div',{cls:'meter'},h('i',{cls:level(b.soc),style:'width:'+Math.max(0,Math.min(100,b.soc))+'%'})),
    h('span',{},batteryText(b).replace('Батерия: ','')))];
}

function placeSummary(r){
  const day=r.days.find(d=>d.date===r.today),tom=r.days.find(d=>d.date>r.today),w=r.watering,b=r.battery;
  const chip=(title,...k)=>h('span',{cls:'chip',title,'aria-label':title},...k);
  const crops=[['plants','Растения'],['grass','Трева'],['trees','Дървета']];
  return h('summary',{},svg(ICONS.chev,'chev sm'),h('h2',{},r.name),
    h('span',{cls:'chips'},
      day&&chip('Днес: макс. '+f1(day.tMax)+'°C, мин. '+f1(day.tMin)+'°C',h('b',{},f1(day.tMax,0)+'°'),h('span',{cls:'mut'},' / '+f1(day.tMin,0)+'°')),
      day&&chip('Валеж днес: '+f1(day.rainMm)+' мм',svg(ICONS.rain,'rain sm'),f1(day.rainMm)+' мм'),
      ...crops.map(([k,l])=>chip(l+': '+w[k].text,svg(ICONS[k],'crop sm'),svg(ICONS[w[k].water],'water sm '+w[k].water))),
      b&&b.soc!=null&&chip(batteryText(b),batIcon(b.soc,'sm'),f1(b.soc,0)+'%'),
      b&&tom&&(()=>{if(!(b.batteryKwh>0))return chip('Утре: очаквано производство '+f1(tom.kwh)+' kWh',f1(tom.kwh)+' kWh');
        const full=tom.kwh>=b.batteryKwh;
        return chip('Утре: '+f1(tom.kwh)+' kWh – батерията '+(full?'може':'не може')+' да се зареди напълно от слънцето',svg(ICONS[full?'yes_':'no_'],'sm ev '+(full?'okc':'nox')),f1(tom.kwh)+' kWh')})()));
}

function reportCard(r,i,state){
  if(r.error)return h('div',{cls:'card'},h('h2',{},r.name),h('div',{cls:'yes'},r.error));
  const b=r.battery,html=(t,s)=>{const d=h('div');d.innerHTML=s;return t?[h('h3',{},t),d]:[d]};
  return h('details',{cls:'card place',open:state[r.id]??i===0,      ontoggle:e=>{const st=readOpen();st[r.id]=e.target.open;localStorage.setItem(OPEN_KEY,JSON.stringify(st))}},
    placeSummary(r),
    h('div',{cls:'pbody'},
    ...html('Седмични температури и валежи',weatherChart(r.days,r.today)),
    ...(r.intraday?html('Днес',intraChart(r.intraday)):[h('h3',{},'Днес'),h('p',{cls:'mut'},'Данните за деня се зареждат…')]),
    ...(b?[h('h3',{},'Слънце и батерия'),...batteryLine(b),...html(null,powerChart(r.days,r.today,b.batteryKwh))]:r.solar?[h('h3',{},'Слънце и батерия'),h('p',{cls:'mut'},'Чака се връзка със Solax – въведете Client ID и Secret в ',h('a',{href:'#settings'},'настройките'),'. Мощността на панелите и капацитетът на батерията се вземат оттам.')]:[])));
}


export async function loadReports(){
  const box=$('#reports');
  try{
    const rs=await api('/report');
    const st=readOpen(),all=v=>()=>box.querySelectorAll('details.place').forEach(d=>{d.open=v});
    box.replaceChildren(...(rs.length?[
      ...(rs.length>1?[h('div',{cls:'bar'},h('button',{cls:'l',onclick:all(true)},'Разгъни всички'),h('button',{cls:'l',onclick:all(false)},'Свий всички'))]:[]),
      ...rs.map((r,i)=>reportCard(r,i,st))]:[h('p',{cls:'mut'},'Добавете място от ',h('a',{href:'#settings'},'настройките'),', за да започнете.')]));
  }catch(x){say(x.message,1)}
}

