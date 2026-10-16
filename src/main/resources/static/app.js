import {$,app,h,say,api,run,svg} from './util.js';
import {authView} from './auth.js';
import {loadReports} from './dashboard.js';
import {showSettings} from './settings.js';

const NAV={
  cog:'<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
  out:'<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/>',
  home:'<path d="M3 11l9-8 9 8"/><path d="M5 10v11h14V10"/>',
};
const iconBtn=(key,title,onclick,on)=>h('button',{cls:'ib'+(on?' on':''),title,'aria-label':title,onclick},svg(NAV[key]));

async function start(){
  say();
  let me;
  try{me=await api('/me')}catch{return authView(location.hash.startsWith('#reset=')?'reset':'login',start)}
  const settings=location.hash==='#settings';
  $('#nav').replaceChildren(h('span',{cls:'mut email'},me.email),
    settings?iconBtn('home','Обратно към прогнозата',()=>{location.hash=''}):iconBtn('cog','Настройки',()=>{location.hash='#settings'}),
    iconBtn('out','Изход',run(async()=>{await api('/logout','POST');location.hash='';start()})));
  if(settings)return showSettings();
  app.replaceChildren(h('div',{id:'reports'},h('p',{cls:'mut'},'Зареждане на прогнозата…')));
  loadReports();
}


addEventListener('hashchange',start);
start();
