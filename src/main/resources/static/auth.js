import {$,app,h,say,api,run} from './util.js';

export function authView(mode,onLogin){
  $('#nav').replaceChildren();
  const em=h('input',{type:'email',placeholder:'Имейл',required:true,autocomplete:'email'});
  const pw=h('input',{type:'password',placeholder:mode==='reset'?'Нова парола (мин. 8 символа)':'Парола (мин. 8 символа)',required:true,minLength:8,autocomplete:mode==='login'?'current-password':'new-password'});
  const go=m=>h('button',{type:'button',cls:'l',onclick:()=>authView(m,onLogin)},{login:'Вход',register:'Регистрация',forgot:'Забравена парола?'}[m]);
  const title={login:'Вход',register:'Регистрация',forgot:'Възстановяване на парола',reset:'Нова парола'}[mode];
  const submit=run(async()=>{
    if(mode==='login'||mode==='register'){await api('/'+mode,'POST',{email:em.value,password:pw.value});location.hash='';onLogin()}
    else if(mode==='forgot'){say((await api('/forgot','POST',{email:em.value})).message)}
    else{await api('/reset','POST',{token:location.hash.slice(7),password:pw.value});location.hash='';authView('login',onLogin);say('Паролата е сменена, моля влезте.')}});
  app.replaceChildren(h('form',{cls:'card auth',onsubmit:submit},h('h2',{},title),
    mode!=='reset'?em:null,mode!=='forgot'?pw:null,h('button',{},title),
    h('div',{cls:'row'},mode==='login'?[go('register'),go('forgot')]:go('login'))));
}

