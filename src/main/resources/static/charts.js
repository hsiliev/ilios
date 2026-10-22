import {f1,dm,dmy,ICONS} from './util.js';

// ---------- charts (hand-rolled SVG; values only, no user text goes into the markup) ----------
const WD=['нд','пн','вт','ср','чт','пт','сб'],wdOf=iso=>WD[new Date(iso+'T12:00:00').getDay()];
Object.assign(ICONS,{
  cloud:'<path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"/>',
  wind:'<path d="M9.6 4.6A2 2 0 1 1 11 8H2m10.6 11.4A2 2 0 1 0 14 16H2m15.7-8.3A2.5 2.5 0 1 1 19.5 12H2"/>',
  thunder:'<path d="M19 16.9A5 5 0 0 0 18 7h-1.26a8 8 0 1 0-11.62 9"/><polyline points="13 11 9 17 15 17 11 23"/>',
  zap:'<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>',
  yes_:'<path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/>',
  no_:'<circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/>',
  snow:'<line x1="12" y1="2" x2="12" y2="22"/><line x1="4" y1="7" x2="20" y2="17"/><line x1="20" y1="7" x2="4" y2="17"/>',
});
const EVENTS={thunder:'Гръмотевична буря',snow:'Сняг',rain:'Дъжд',wind:'Силен вятър',cloud:'Облачно'};
const num=(x,d=1)=>f1(x,d);
const tempScale=(vals,headroom)=>{const mn=Math.min(...vals),mx=Math.max(...vals)+headroom;
  const tstep=[1,2,5,10].find(s=>Math.ceil(mx/s)-Math.floor(mn/s)<=7)||20;
  const lo=Math.floor(mn/tstep)*tstep,hi=Math.ceil(mx/tstep)*tstep;return{lo,hi,tstep,ticks:(hi-lo)/tstep}};
const stepFor=(max,ticks,cands)=>cands.find(s=>s*ticks>=max)||cands[cands.length-1];
const legend=items=>'<div class="legend">'+items.map(([sw,t])=>`<span>${sw}${t}</span>`).join('')+'</div>';
const sw=(cls,style='')=>`<i class="sw ${cls}" style="${style}"></i>`;
const evIcon=(ev,x,y,s)=>`<svg x="${x}" y="${y}" width="${s}" height="${s}" viewBox="0 0 24 24" class="ev ${ev}"><title>${EVENTS[ev]}</title>${ICONS[ev==='rain'?'rain':ev]}</svg>`;
const shell=(W,H,label,inner)=>`<div class="chartbox"><svg viewBox="0 0 ${W} ${H}" class="chart" role="img" aria-label="${label}">${inner}</svg></div>`;

// shared week frame for the 21-day charts: separators, week captions, today shading, day labels
function weekFrame(days,today,L,T,iw,ih,step){
  const x=i=>L+step*i;let o='';
  const ti=days.findIndex(d=>d.date===today);
  if(ti>=0)o+=`<rect x="${x(ti)}" y="${T}" width="${step}" height="${ih}" class="today"/>`;
  [7,14].forEach(i=>o+=`<line x1="${x(i)}" x2="${x(i)}" y1="${T}" y2="${T+ih}" class="sep"/>`);
  [['ПРЕДХОДНА СЕДМИЦА',0],['ТЕКУЩА СЕДМИЦА',7],['СЛЕДВАЩА СЕДМИЦА (прогноза)',14]].forEach(([t,i])=>o+=`<text x="${x(i)+6}" y="${T-8}" class="cap2">${t}</text>`);
  days.forEach((d,i)=>o+=`<text x="${x(i)+step/2}" y="${T+ih+15}" class="tx" text-anchor="middle">${dm(d.date)}</text><text x="${x(i)+step/2}" y="${T+ih+28}" class="tx ${d.date===today?'b':''}" text-anchor="middle">${wdOf(d.date)}</text>`);
  return o;
}

export function weatherChart(days,today){
  const W=900,H=340,L=48,R=48,T=34,B=44,iw=W-L-R,ih=H-T-B,step=iw/days.length;
  const sc=tempScale(days.flatMap(d=>[d.tMax,d.tMin]).filter(v=>v!=null),2);
  const maxRain=Math.max(0,...days.map(d=>d.rainMm));
  const rstep=stepFor(maxRain*1.5,sc.ticks,[1,2,5,10,20,50,100]);
  const y=t=>T+ih-(t-sc.lo)/(sc.hi-sc.lo)*ih,yr=m=>T+ih-m/(rstep*sc.ticks)*ih,cx=i=>L+step*(i+.5);
  let o='';
  for(let k=0;k<=sc.ticks;k++){const yy=T+ih-k*ih/sc.ticks;
    o+=`<line x1="${L}" x2="${L+iw}" y1="${yy}" y2="${yy}" class="g"/><text x="${L-6}" y="${yy+4}" class="tx" text-anchor="end">${sc.lo+k*sc.tstep}°</text><text x="${L+iw+6}" y="${yy+4}" class="tx">${num(k*rstep,0)}</text>`}
  o+=`<text transform="translate(12 ${T+ih/2}) rotate(-90)" class="tx" text-anchor="middle">Температура (°C)</text><text transform="translate(${W-8} ${T+ih/2}) rotate(90)" class="tx" text-anchor="middle">Валеж (мм)</text>`;
  o+=weekFrame(days,today,L,T,iw,ih,step);
  days.forEach((d,i)=>{if(d.rainMm>0){const bw=step*.6;o+=`<rect x="${cx(i)-bw/2}" y="${yr(d.rainMm)}" width="${bw}" height="${T+ih-yr(d.rainMm)}" rx="2" class="rainbar"/>`}});
  const ok=days.map((d,i)=>[d,i]).filter(([d])=>d.tMax!=null&&d.tMin!=null);
  o+=`<polygon points="${ok.map(([d,i])=>cx(i)+','+y(d.tMax)).join(' ')+' '+ok.slice().reverse().map(([d,i])=>cx(i)+','+y(d.tMin)).join(' ')}" class="band"/>`;
  o+=`<polyline points="${ok.map(([d,i])=>cx(i)+','+y(d.tMax)).join(' ')}" class="lmax"/><polyline points="${ok.map(([d,i])=>cx(i)+','+y(d.tMin)).join(' ')}" class="lmin"/>`;
  days.forEach((d,i)=>o+=`<rect x="${L+step*i}" y="${T}" width="${step}" height="${ih}" class="hit" data-tip="${dmy(d.date)} (${wdOf(d.date)})|макс. ${num(d.tMax)}°C|мин. ${num(d.tMin)}°C|дъжд ${num(d.rainMm)} мм"/>`);
  return legend([[sw('l','background:var(--cmax)'),'Дневен максимум (°C)'],[sw('l','background:var(--cmin)'),'Дневен минимум (°C)'],[sw('b','background:var(--crain)'),'Валеж (мм)']])+shell(W,H,'Температури и валежи за три седмици',o);
}

// both day charts show 24 hours with the current time in the middle: 12 hours back, 12 ahead (yesterday, today and tomorrow are stitched together)
const nowMin=now=>+now.slice(0,2)*60+ +now.slice(3,5),tmin=(t,off)=>off*1440+ +t.slice(0,2)*60+ +t.slice(3,5);
const hm=m=>{m=(m%1440+1440)%1440;return String(Math.floor(m/60)).padStart(2,'0')+':'+String(m%60).padStart(2,'0')};
const winStart=now=>Math.floor(nowMin(now)/5)*5-720;

export function intraChart(it){
  const W=900,H=330,L=48,R=48,T=60,B=34,iw=W-L-R,ih=H-T-B,sm=it.stepMin,ws=winStart(it.now),X=m=>L+iw*(m-ws)/1440,bw0=iw*sm/1440;
  const all=[...(it.prev||[]).map(p=>[p,-1]),...it.points.map(p=>[p,0]),...(it.next||[]).map(p=>[p,1])].map(([p,o])=>({...p,m:tmin(p.t,o)})).filter(p=>p.m>=ws&&p.m<ws+1440);
  const sc=tempScale(all.map(p=>p.temp).filter(v=>v!=null),1);
  const maxRain=Math.max(0,...all.map(p=>p.rainMm));
  const rstep=stepFor(maxRain*1.5,sc.ticks,[.1,.2,.5,1,2,5,10]);
  const y=t=>T+ih-(t-sc.lo)/(sc.hi-sc.lo)*ih,yr=m=>T+ih-m/(rstep*sc.ticks)*ih,cx=p=>X(p.m)+bw0/2;
  let o='';
  for(let k=0;k<=sc.ticks;k++){const yy=T+ih-k*ih/sc.ticks;
    o+=`<line x1="${L}" x2="${L+iw}" y1="${yy}" y2="${yy}" class="g"/><text x="${L-6}" y="${yy+4}" class="tx" text-anchor="end">${sc.lo+k*sc.tstep}°</text><text x="${L+iw+6}" y="${yy+4}" class="tx">${num(k*rstep,1)}</text>`}
  o+=`<text transform="translate(12 ${T+ih/2}) rotate(-90)" class="tx" text-anchor="middle">Температура (°C)</text><text transform="translate(${W-8} ${T+ih/2}) rotate(90)" class="tx" text-anchor="middle">Валеж (мм/${sm} мин)</text>`;
  for(let m=Math.ceil(ws/180)*180;m<=ws+1440;m+=180){const xx=X(m);o+=`<line x1="${xx}" x2="${xx}" y1="${T}" y2="${T+ih}" class="g"/><text x="${xx}" y="${T+ih+16}" class="tx" text-anchor="middle">${hm(m)}</text>`}
  all.forEach(p=>{if(p.rainMm>0){const bw=Math.max(bw0*.8,1.5);o+=`<rect x="${cx(p)-bw/2}" y="${yr(p.rainMm)}" width="${bw}" height="${T+ih-yr(p.rainMm)}" rx="1.5" class="rainbar"/>`}});
  const ok=all.filter(p=>p.temp!=null);
  o+=`<polyline points="${ok.map(p=>cx(p)+','+y(p.temp)).join(' ')}" class="lmax"/>`;
  // one event icon per clock hour: the most severe event in that hour
  const rank=['thunder','snow','rain','wind','cloud'];
  for(let h=Math.ceil(ws/60);h*60+60<=ws+1440;h++){const evs=all.filter(p=>p.m>=h*60&&p.m<h*60+60).map(p=>p.event).filter(Boolean);
    const ev=rank.find(e=>evs.includes(e));if(ev)o+=evIcon(ev,X(h*60+30)-9,T-34,18)}
  const nx=X(nowMin(it.now));
  o+=`<line x1="${nx}" x2="${nx}" y1="${T}" y2="${T+ih}" class="now"/><text x="${nx+4}" y="${T+10}" class="tx b">сега ${it.now}</text>`;
  all.forEach(p=>o+=`<rect x="${X(p.m)}" y="${T-38}" width="${bw0}" height="${ih+38}" class="hit" data-tip="${hm(p.m)}|${num(p.temp)}°C|валеж ${num(p.rainMm,2)} мм${p.event?'|'+EVENTS[p.event]:''}"/>`);
  const used=rank.filter(e=>all.some(p=>p.event===e));
  return legend([[sw('l','background:var(--cmax)'),'Температура (°C)'],[sw('b','background:var(--crain)'),`Валеж (мм/${sm} мин)`],...used.map(e=>[`<svg viewBox="0 0 24 24" class="ev ${e} lg">${ICONS[e]}</svg>`,EVENTS[e]])])+shell(W,H,'Времето',o);
}

export function powerChart(days,today,cap,estimate=null){
  const planTip=p=>`Планирано зареждане през нощта до ${num(p.targetSoc,0)}% (до ${p.stopBy} ч.): ${num(p.dayStartKwh)} kWh за началото на деня${p.sunAt?` (до ${p.sunAt} ч., когато слънцето поеме консумацията)`:''} + ${num(p.solarKwh)} kWh компенсация на слънцето = ${num(p.totalKwh)} kWh`;
  const lf=d=>d.loadKwh??d.loadEst??0,maxL=Math.max(0,...days.map(lf));
  const W=900,L=52,R=16,T=66,B=44,step=(W-L-R)/days.length,iw=W-L-R;
  const max=Math.max(cap,...days.map(d=>Math.max(d.kwh,d.produced??0)),1)*1.12,ticks=5,tstep=stepFor(max,ticks,[1,2,5,10,20,50,100,200,500,1000]);
  // below the x axis: the home's consumption on the same kWh scale
  const nneg=maxL>0?Math.ceil(maxL/tstep):0,ihU=240,pxk=ihU/(tstep*ticks),ihD=nneg*tstep*pxk,ih=ihU,H=346+ihD;
  const y=v=>T+ih-v/(tstep*ticks)*ih,cx=i=>L+step*(i+.5),yd=v=>T+ih+v*pxk;
  let o='';
  for(let k=0;k<=ticks;k++){const yy=T+ih-k*ih/ticks;o+=`<line x1="${L}" x2="${L+iw}" y1="${yy}" y2="${yy}" class="g"/><text x="${L-6}" y="${yy+4}" class="tx" text-anchor="end">${num(k*tstep,0)}</text>`}
  o+=`<text transform="translate(12 ${T+ih/2}) rotate(-90)" class="tx" text-anchor="middle">Дневно производство (kWh)</text>`;
  for(let k=1;k<=nneg;k++){const yy=yd(k*tstep);o+=`<line x1="${L}" x2="${L+iw}" y1="${yy}" y2="${yy}" class="g"/><text x="${L-6}" y="${yy+4}" class="tx" text-anchor="end">-${num(k*tstep,0)}</text>`}
  if(nneg)o+=`<text transform="translate(12 ${T+ih+ihD/2}) rotate(-90)" class="tx" text-anchor="middle">Консумация (kWh)</text>`;
  o+=weekFrame(days,today,L,T,iw,ih+ihD,step);
  // solid bars = panel output measured by SolaX for past days (fetched once each); pale bars = solar forecast, today included.
  const measured=d=>d.date<today&&d.produced!=null,val=d=>measured(d)?d.produced:d.kwh;
  const measuredCharge=d=>d.date<today&&d.maxSoc!=null,reached=d=>d.maxSoc>=99; // 99: chargers often stop a hair below 100
  // today and later: the house (heat pump...) uses part of the sun first, so only the rest can go into the battery
  const net=d=>val(d)-(d.homeKwh||0),fullFor=d=>d.plan?d.plan.totalKwh<=0:measuredCharge(d)?reached(d):d.date===today&&estimate?!estimate.chargeNeeded:net(d)>=cap;
  const noData=d=>d.date<today&&!measuredCharge(d);
  // callout text explaining the check: was/will the battery be charged from the sun
  const chargeTip=d=>`${dmy(d.date)} (${wdOf(d.date)})|`+(measuredCharge(d)
    ?(reached(d)?`Слънцето е заредило батерията до 100% (макс. ${num(d.maxSoc,0)}%)`:`Слънцето не е заредило батерията напълно (макс. ${num(d.maxSoc,0)}%)`)
    :d.plan?(d.plan.totalKwh>0?planTip(d.plan):'Не е нужно нощно зареждане: слънцето покрива деня')
    :noData(d)?'Няма данни дали слънцето е заредило батерията'
    :fullFor(d)?`Слънцето ще зареди батерията до 100% (прогноза ${num(val(d))} kWh${d.homeKwh?`, консумация през деня ≈ ${num(d.homeKwh)} kWh`:''} при капацитет ${num(cap)} kWh)`
    :`Слънцето няма да зареди батерията напълно (прогноза ${num(val(d))} kWh${d.homeKwh?`, консумация през деня ≈ ${num(d.homeKwh)} kWh`:''} при капацитет ${num(cap)} kWh)`);
  const bar=(i,v,cls)=>{const bw=step*.62;return v>0?`<rect x="${cx(i)-bw/2}" y="${y(v)}" width="${bw}" height="${T+ih-y(v)}" rx="2" class="${cls}"/>`:''};
  days.forEach((d,i)=>{
    o+=bar(i,val(d),measured(d)?'kprod':'kfc');
    if(d.badCloud!=null)o+=`<text x="${cx(i)}" y="${y(val(d))-5}" class="badc" text-anchor="middle">!</text>`;
    if(lf(d)>0){const bw=step*.62;o+=`<rect x="${cx(i)-bw/2}" y="${T+ih}" width="${bw}" height="${lf(d)*pxk}" rx="2" class="${d.loadKwh!=null?'kbat':'kbatf'}"/>`}
    // icon row above the plot: a lightning where the battery did not reach 100 % (past day) or the sun alone is not expected to fill it
    // (today and the future, judged from the forecast), i.e. where charging would be needed; nothing when it is full or the history is not fetched yet
    const full=fullFor(d);
    if(cap>0)o+=`<rect x="${L+step*i}" y="${T-48}" width="${step}" height="48" class="hit" data-tip="${chargeTip(d)}"/>`
      +(noData(d)?`<svg x="${cx(i)-9}" y="${T-44}" width="18" height="18" viewBox="0 0 24 24" class="ev" data-tip="${chargeTip(d)}"><line x1="7" y1="12" x2="17" y2="12"/></svg>`
      :full?'':`<svg x="${cx(i)-9}" y="${T-44}" width="18" height="18" viewBox="0 0 24 24" class="ev zap" data-tip="${chargeTip(d)}">${ICONS.zap}</svg>`)});
  const wave=`M${L} ${y(cap)}`+' q4 -2.5 8 0'+' t8 0'.repeat(Math.floor(iw/8)-1);
  if(cap>0)o+=`<path d="${wave}" class="capw"/><text x="${L+iw-4}" y="${y(cap)-7}" class="tx halo" text-anchor="end">капацитет на батерията ${num(cap)} kWh</text>`;
  const tip=d=>{
    const p=[];
    if(d.produced!=null)p.push(`панели ${num(d.produced)} kWh`);
    else p.push(`${d.date<today?'оценка':'прогноза'} ${num(d.kwh)} kWh`);
    if(d.badCloud!=null)p.push(`! SolaX върна невярна стойност (${num(d.badCloud,0)} kWh) – показана е оценка`);
    if(d.ac!=null)p.push(`инвертор ${num(d.ac)} kWh`);
    if(d.maxSoc!=null)p.push(`батерия ${num(d.maxSoc,0)}%`);
    if(d.loadKwh!=null)p.push(`консумация ${num(d.loadKwh)} kWh`);else if(d.loadEst!=null)p.push(`консумация (оценка) ${num(d.loadEst)} kWh`);
    return p.join('|');
  };
  days.forEach((d,i)=>o+=`<rect x="${L+step*i}" y="${T}" width="${step}" height="${ih+ihD}" class="hit" data-tip="${dmy(d.date)} (${wdOf(d.date)})|${tip(d)}"/>`);
  return shell(W,H,'Слънчево производство и състояние на батерията',o);
}


// callout shown on hover/touch over any chart column carrying data-tip ("line|line|…"); one shared element
let callout;
function showCallout(e){
  const t=e.target.closest?.('[data-tip]');
  if(!t){if(callout)callout.hidden=true;return}
  if(!callout){callout=document.createElement('div');callout.className='callout';document.body.append(callout)}
  const [head,...rest]=t.dataset.tip.split('|');
  callout.replaceChildren(Object.assign(document.createElement('b'),{textContent:head}),...rest.map(x=>Object.assign(document.createElement('div'),{textContent:x})));
  callout.hidden=false;
  const r=callout.getBoundingClientRect(),m=12;
  callout.style.left=Math.max(4,Math.min(e.clientX+m,innerWidth-r.width-4))+'px';
  callout.style.top=(e.clientY+m+r.height>innerHeight?e.clientY-m-r.height:e.clientY+m)+'px';
}
document.addEventListener('pointermove',showCallout);
document.addEventListener('pointerdown',showCallout);
document.addEventListener('pointerleave',()=>{if(callout)callout.hidden=true});

// the battery charge (line, right axis in %) and panel power (bars, left axis in kW) at SolaX's finest step (5 minutes): the last 12 hours
// (yesterday's points are kept for this), the current time in the middle, and the forecast and estimate for the next 12
export const NIGHT_FROM=22,NIGHT_TO=6; // cheap night tariff hours (local time)
export function batteryChart(pts,fc=[],now='',load=[],bat=null,estimate=null,zone=[NIGHT_FROM,NIGHT_TO],prev=[],fcNext=[]){
  const [NF,NT]=zone;
  const W=900,H=360,L=48,R=48,T=20,B=34,iw=W-L-R,ih=H-T-B,n=288,step=iw/n,ticks=5;
  if(!now){const d=new Date();now=String(d.getHours()).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0')}
  const ws=winStart(now),inWin=m=>m>=ws&&m<ws+1440,at=m=>(m-ws)/5,X=m=>L+iw*(m-ws)/1440;
  const hist=[...prev.map(p=>({...p,m:tmin(p.t,-1)})),...pts.map(p=>({...p,m:tmin(p.t,0)}))].filter(p=>inWin(p.m));
  const last=hist.length?hist[hist.length-1].m:nowMin(now);
  const future=[...fc.map(p=>({...p,m:tmin(p.t,0)})),...fcNext.map(p=>({...p,m:tmin(p.t,1)}))].filter(p=>p.m>last&&inWin(p.m)&&p.pvW>0);
  // the server's 5-minute estimate (previous days' consumption pattern x the weather x the solar forecast) runs 12 hours ahead, past midnight
  // (its times wrap, so the day offset grows when a time is earlier than the one before); without consumption data there is no grid estimate
  const byServer=estimate?.points?.length>0,ew=byServer?1:3;
  let eo=0,et='';
  const ep=byServer?estimate.points.map(p=>{if(et&&p.t<et)eo++;et=p.t;return{...p,m:tmin(p.t,eo)}}).filter(p=>p.m>last&&inWin(p.m)):[];
  const est=ep.map(p=>({m:p.m,w:p.gridW,c:p.chargeW||0}));
  const negKw=p=>p.gridW>0?p.gridW/1000:0; // below the axis: power drawn from the grid
  const maxKw=Math.max(0.5,...hist.map(p=>(p.pvW||0)/1000),...future.map(p=>p.pvW/1000))*1.1,kstep=stepFor(maxKw,ticks,[.5,1,2,5,10,20,50,100]);
  const maxD=Math.max(0,...hist.map(negKw),...est.map(p=>p.w/1000)),hasD=maxD>0;
  // the zone below the x axis (battery consumption) only exists when there is any discharge
  // both zones share one kW scale: the same number of pixels per kW above and below the axis
  const nneg=hasD?Math.ceil(maxD/kstep):0,ihT=ih*ticks/(ticks+nneg),ihB=ih-ihT,midY=T+ihT,dmax=nneg*kstep;
  const yk=v=>midY-v/(kstep*ticks)*ihT,yp=p=>midY-p/100*ihT,yd=v=>midY+v/dmax*ihB;
  let o='';
  for(let k=0;k<=ticks;k++){const yy=midY-k*ihT/ticks;
    o+=`<line x1="${L}" x2="${L+iw}" y1="${yy}" y2="${yy}" class="g"/><text x="${L-6}" y="${yy+4}" class="tx" text-anchor="end">${num(k*kstep,kstep<1?1:0)}</text><text x="${L+iw+6}" y="${yy+4}" class="tx">${k*20}%</text>`}
  if(hasD){for(let k=1;k<=nneg;k++){const yy=yd(k*kstep);o+=`<line x1="${L}" x2="${L+iw}" y1="${yy}" y2="${yy}" class="g"/><text x="${L-6}" y="${yy+4}" class="tx" text-anchor="end">-${num(k*kstep,kstep<1?1:0)}</text>`}
    o+=`<text transform="translate(12 ${midY+ihB/2}) rotate(-90)" class="tx" text-anchor="middle">Мрежа (kW)</text>`}
  o+=`<text transform="translate(12 ${T+ihT/2}) rotate(-90)" class="tx" text-anchor="middle">Мощност на панелите (kW)</text><text transform="translate(${W-8} ${T+ihT/2}) rotate(90)" class="tx" text-anchor="middle">Заряд на батерията (%)</text>`;
  // tariff zones: night (NF-NT, cheap energy) is shaded, the day zone starts when it ends; yesterday's, today's and tomorrow's zones are cut to the window
  for(const d of [-1,0,1]){
    for(const [a,b] of [[d*1440,d*1440+NT*60],[d*1440+NF*60,d*1440+1440]]){
      const x1=X(Math.max(a,ws)),x2=X(Math.min(b,ws+1440));
      if(x2-x1>0)o+=`<rect x="${x1}" y="${T}" width="${x2-x1}" height="${ih}" class="nightz"/>`+(x2-x1>90?`<text x="${(x1+x2)/2}" y="${T+12}" class="tx" text-anchor="middle">нощна енергия</text>`:'')}
    const dz=d*1440+NT*60;
    if(inWin(dz))o+=`<line x1="${X(dz)}" x2="${X(dz)}" y1="${T}" y2="${T+ih}" class="sep"/><text x="${X(dz)+4}" y="${T+12}" class="tx">дневна енергия →</text>`}
  for(let m=Math.ceil(ws/180)*180;m<=ws+1440;m+=180){const xx=X(m);o+=`<line x1="${xx}" x2="${xx}" y1="${T}" y2="${T+ih}" class="g"/><text x="${xx}" y="${T+ih+16}" class="tx" text-anchor="middle">${hm(m)}</text>`}
  hist.forEach(p=>{const v=(p.pvW||0)/1000;if(v>0){const bw=Math.max(step*.9,1.5);o+=`<rect x="${L+step*at(p.m)+step/2-bw/2}" y="${yk(v)}" width="${bw}" height="${midY-yk(v)}" class="kprod"/>`}});
  hist.forEach(p=>{const v=negKw(p);if(v>0){const bw=Math.max(step*.9,1.5);o+=`<rect x="${L+step*at(p.m)+step/2-bw/2}" y="${midY}" width="${bw}" height="${yd(v)-midY}" class="kbat"/>`}});
  // pale bars: Open-Meteo's forecast (15-minute steps) for the next hours
  future.forEach(p=>{const bw=step*3*.9;o+=`<rect x="${L+step*at(p.m)+step*1.5-bw/2}" y="${yk(p.pvW/1000)}" width="${bw}" height="${midY-yk(p.pvW/1000)}" class="kfc"/><rect x="${L+step*at(p.m)}" y="${T}" width="${step*3}" height="${ih}" class="hit" data-tip="${hm(p.m)}|прогноза ${num(p.pvW/1000,2)} kW"/>`});
  // pale red bars: the grid power the estimate expects
  est.forEach(p=>{const v=p.w/1000,bw=step*ew*.9;if(v>0)o+=`<rect x="${L+step*at(p.m)+step*ew/2-bw/2}" y="${midY}" width="${bw}" height="${yd(v)-midY}" class="kbatf"/><rect x="${L+step*at(p.m)}" y="${T}" width="${step*ew}" height="${ih}" class="hit" data-tip="${hm(p.m)}|очаквано от мрежата ${num(v,2)} kW${p.c>0?'|в т.ч. планирано зареждане '+num(p.c/1000,2)+' kW':''}"/>`});
  // dashed line: the charge the estimate expects for the rest of the day
  if(ep.length)o+=`<polyline points="${ep.map(p=>(L+step*(at(p.m)+.5))+','+yp(p.soc)).join(' ')}" class="bsoc" stroke-dasharray="5 4"/>`;
  const s=hist.filter(p=>p.soc!=null);
  if(s.length)o+=`<polyline points="${s.map(p=>(L+step*(at(p.m)+.5))+','+yp(p.soc)).join(' ')}" class="bsoc"/>`;
  const nx=X(nowMin(now));
  o+=`<line x1="${nx}" x2="${nx}" y1="${T}" y2="${T+ih}" class="now"/><text x="${nx+4}" y="${T+ih-6}" class="tx b">сега ${now}</text>`;
  hist.forEach(p=>o+=`<rect x="${L+step*at(p.m)}" y="${T}" width="${step}" height="${ih}" class="hit" data-tip="${hm(p.m)}${p.pvW!=null?'|панели '+num(p.pvW/1000,2)+' kW':''}${p.gridW>0?'|от мрежата '+num(p.gridW/1000,2)+' kW':''}${p.soc!=null?'|батерия '+num(p.soc,0)+'%':''}"/>`);
  return legend([[sw('l','background:var(--cbat)'),'Заряд на батерията (%)'],[sw('b','background:var(--kprod)'),'Мощност на панелите (kW)'],[sw('b','background:var(--kbat)'),'Изтеглено от мрежата (kW)']])+shell(W,H,'Батерия',o);
}
