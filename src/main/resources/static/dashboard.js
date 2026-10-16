import {$,h,say,api,f1,svg,ICONS} from './util.js';
import {weatherChart,intraChart,powerChart} from './charts.js';

// collapsible place: a summary row with today's key figures, the charts and advice inside
const OPEN_KEY='ilios.open';
const readOpen=()=>{try{return JSON.parse(localStorage.getItem(OPEN_KEY)||'{}')}catch{return{}}};
ICONS.chev='<polyline points="9 6 15 12 9 18"/>';

function placeSummary(r){
  const day=r.days.find(d=>d.date===r.today),tom=r.days.find(d=>d.date>r.today),w=r.watering,b=r.battery;
  const chip=(title,...k)=>h('span',{cls:'chip',title,'aria-label':title},...k);
  const crops=[['plants','Растения'],['grass','Трева'],['trees','Дървета']];
  return h('summary',{},svg(ICONS.chev,'chev sm'),h('h2',{},r.name),
    h('span',{cls:'chips'},
      day&&chip('Днес: макс. '+f1(day.tMax)+'°C, мин. '+f1(day.tMin)+'°C',h('b',{},f1(day.tMax,0)+'°'),h('span',{cls:'mut'},' / '+f1(day.tMin,0)+'°')),
      day&&chip('Валеж днес: '+f1(day.rainMm)+' мм',svg(ICONS.rain,'rain sm'),f1(day.rainMm)+' мм'),
      ...crops.map(([k,l])=>chip(l+': '+w[k].text,svg(ICONS[k],'crop sm'),svg(ICONS[w[k].water],'water sm '+w[k].water))),
      b&&tom&&(()=>{const full=tom.kwh>=b.batteryKwh;
        return chip('Утре: '+f1(tom.kwh)+' kWh – батерията '+(full?'може':'не може')+' да се зареди напълно от слънцето',svg(ICONS[full?'yes_':'no_'],'sm ev '+(full?'okc':'nox')),f1(tom.kwh)+' kWh')})()));
}

function reportCard(r,i,state){
  if(r.error)return h('div',{cls:'card'},h('h2',{},r.name),h('div',{cls:'yes'},r.error));
  const b=r.battery,html=(t,s)=>{const d=h('div');d.innerHTML=s;return [h('h3',{},t),d]};
  return h('details',{cls:'card place',open:state[r.id]??i===0,      ontoggle:e=>{const st=readOpen();st[r.id]=e.target.open;localStorage.setItem(OPEN_KEY,JSON.stringify(st))}},
    placeSummary(r),
    h('div',{cls:'pbody'},
    ...html('Седмични температури и валежи',weatherChart(r.days,r.today)),
    ...(r.intraday?html('Днес',intraChart(r.intraday)):[h('h3',{},'Днес'),h('p',{cls:'mut'},'Данните за деня се зареждат…')]),
    ...(b?html('Слънце и батерия',powerChart(r.days,r.today,b.batteryKwh)):[])));
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

