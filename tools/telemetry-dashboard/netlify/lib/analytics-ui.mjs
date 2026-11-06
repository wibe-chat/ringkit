export const REPORT_STYLE = String.raw`
  .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(155px,1fr));gap:12px;margin:16px 0}
  .card,.chart{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:16px}
  .card strong{display:block;font-size:28px;font-weight:600;margin-top:5px}.card span{color:var(--dim);font-size:12px}
  .section-head{display:flex;justify-content:space-between;gap:12px;align-items:center;flex-wrap:wrap;margin:24px 0 12px}
  .section-head h2{font-size:17px;margin:0}.notice{color:var(--dim);max-width:95ch}.warning{color:var(--amber)}
  .table-scroll{overflow:auto;border:1px solid var(--line);border-radius:8px}
  .chart svg{display:block;width:100%;height:220px}.chart .labels{display:flex;justify-content:space-between;color:var(--dim);font-size:12px}
  .filters{display:flex;gap:10px;align-items:end;flex-wrap:wrap}.filters label{font-size:12px;color:var(--dim);display:grid;gap:4px}
  .filters[hidden],.filters label[hidden],input[hidden]{display:none}
  .rec{overflow-wrap:anywhere}.rec .record-meta{font-size:11px;color:var(--dim);margin-top:4px}
  .pager{display:flex;gap:10px;align-items:center;margin-top:12px}.drawer .table-scroll{max-width:100%}
  button:focus-visible,input:focus-visible,select:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
  @media(max-width:700px){header{position:static}.tabs{display:flex;gap:4px;flex-wrap:wrap}main{padding:12px}.cards{grid-template-columns:repeat(2,1fr)}.filters input{max-width:180px}}
`;

export const REPORT_CONTROLS = String.raw`
<div id="reportFilters" class="filters" hidden>
  <label id="rangePresetLabel">Range<select id="rangePreset"><option value="7">7 days</option><option value="30" selected>30 days</option><option value="60">60 days</option><option value="custom">Custom</option></select></label>
  <label id="fromLabel">From (UTC)<input type="date" id="fromDate"></label>
  <label id="toLabel">Through (UTC)<input type="date" id="toDate"></label>
  <label id="hoursLabel" hidden>Missing check-in threshold<select id="silentHours"><option value="24">24 hours</option><option value="48" selected>48 hours</option><option value="72">72 hours</option><option value="168">7 days</option></select></label>
  <label id="callLabel" hidden>Call ID<input id="callFilter" placeholder="Exact call ID" maxlength="128"></label>
  <label id="installLabel" hidden>Install ID<input id="installFilter" placeholder="Install UUID"></label>
</div>`;

export const REPORT_CLIENT = String.raw`
var PAGE_OFFSET=0,PREV_OFFSETS=[],LOAD_ID=0,DRAWER_ID=0,DRAWER_OPEN=false,CHART_METRIC='shows',LAST_ANALYTICS=null,LOG_RECORDS=[],LOG_VISIBLE=500;
function closeDrawer(){DRAWER_ID++;DRAWER_OPEN=false;el('drawer').classList.remove('open')}
function num(n){return n==null?'—':Number(n).toLocaleString()}
function pct(n){return n==null?'—':(n*100).toFixed(1)+'%'}
function stamp(s){if(s==null)return '—';var d=new Date(s);return isNaN(d.getTime())?'—':d.toISOString().replace('T',' ').replace('.000Z',' UTC')}
function resetPage(){PAGE_OFFSET=0;PREV_OFFSETS=[]}
function setRange(){var end=new Date();end.setUTCHours(0,0,0,0);if(TAB==='analytics')end.setUTCDate(end.getUTCDate()-1);
  if(el('rangePreset').value==='custom'){if(el('toDate').value>end.toISOString().slice(0,10)){el('toDate').value=end.toISOString().slice(0,10);if(el('fromDate').value>el('toDate').value)el('fromDate').value=el('toDate').value}return}
  el('toDate').value=end.toISOString().slice(0,10);end.setUTCDate(end.getUTCDate()-(Number(el('rangePreset').value)-1));el('fromDate').value=end.toISOString().slice(0,10)}
function reportControls(){var dated=TAB==='analytics'||TAB==='timeline',silent=TAB==='silent';
  el('reportFilters').hidden=!dated&&!silent;
  ['rangePresetLabel','fromLabel','toLabel'].forEach(function(id){el(id).hidden=!dated});
  ['callLabel','installLabel'].forEach(function(id){el(id).hidden=TAB!=='timeline'});
  el('hoursLabel').hidden=!silent;el('search').hidden=TAB!=='devices'&&!silent;
  if(dated)setRange()}
function reportQuery(){var s=qstr()+'&offset='+PAGE_OFFSET;
  if(TAB==='analytics'||TAB==='timeline')s+='&from='+encodeURIComponent(el('fromDate').value)+'&to='+encodeURIComponent(el('toDate').value);
  if(TAB==='silent')s+='&hours='+el('silentHours').value;
  if(TAB==='timeline')s+='&call='+encodeURIComponent(el('callFilter').value.trim())+'&install='+encodeURIComponent(el('installFilter').value.trim());return s}
function pager(d,length){return '<div class="pager"><span class="dim">'+num(d.offset+length?d.offset+1:0)+'–'+num(d.offset+length)+' of '+num(d.total)+'</span>'+
  (PREV_OFFSETS.length?'<button data-act="previousPage">Previous</button>':'')+(d.nextOffset!=null?'<button data-act="nextPage" data-offset="'+d.nextOffset+'">Next</button>':'')+'</div>'}
function partial(d){return d.truncated?'<p class="warning" role="status">Partial results: the reporting limit was reached. Narrow the app or date filter before comparing totals or rankings.</p>':''}
function card(label,value,detail){return '<div class="card"><span>'+esc(label)+'</span><strong>'+num(value)+'</strong><span>'+esc(detail||'')+'</span></div>'}
function renderAnalytics(d){LAST_ANALYTICS=d;var t='<div class="section-head"><div><h2>Call reliability</h2><span class="dim">'+esc(d.from)+' to '+esc(d.to)+' · completed UTC days</span></div><span class="pill">'+esc(d.app||'All apps')+'</span></div>'+partial(d);
  t+='<p class="notice">Heartbeat rollups arrive asynchronously. Gaps mean no rollup is available; they do not establish zero calls. Today is excluded, and late catch-up data appears after the rollup is refreshed.</p>';
  t+='<div class="cards">'+card('Shows',d.totals.shows,'Recorded show outcomes')+card('Accepts',d.totals.accepts,'Not proof of media connection')+card('Show failures',d.totals.failures,pct(d.totals.failureRate)+' of show attempts')+
    card('Degradation events',d.totals.degradations,'A call can have several')+card('Timeouts',d.totals.timeouts,'Reported timeout events')+card('Reporting device-days',d.totals.reportingDeviceDays,'Not unique devices')+'</div>';
  t+='<div class="section-head"><h2>Daily trend</h2><label>Metric <select id="chartMetric">'+['shows','accepts','failures','declines','timeouts','cancels','ends','degradations','droppedLogs'].map(function(k){return '<option value="'+k+'"'+(k===CHART_METRIC?' selected':'')+'>'+esc(k)+'</option>'}).join('')+'</select></label></div><div class="chart" id="trendChart"></div>';
  t+='<div class="section-head"><h2>Device and Android heatmap</h2><span class="dim">Sorted by show failures, then degradation events</span></div><div class="table-scroll"><table><thead><tr><th>Manufacturer</th><th>Model</th><th>API</th><th>Device-days</th><th>Shows</th><th>Failures</th><th>Failure rate</th><th>Degradations</th></tr></thead><tbody>';
  d.heatmap.forEach(function(x){var alpha=x.failureRate==null?0:Math.min(.65,.08+x.failureRate*.8);t+='<tr><td>'+esc(x.manufacturer)+'</td><td>'+esc(x.model)+'</td><td>'+esc(x.sdk)+'</td><td>'+num(x.reportingDeviceDays)+'</td><td>'+num(x.shows)+'</td><td style="background:rgba(248,81,73,'+alpha+')">'+num(x.failures)+'</td><td>'+pct(x.failureRate)+'</td><td title="'+esc(JSON.stringify(x.counters))+'">'+num(x.degradations)+'</td></tr>'});
  if(!d.heatmap.length)t+='<tr><td colspan="8" class="empty">No rollups for this range</td></tr>';
  t+='</tbody></table></div><div class="section-head"><h2>Daily outcomes</h2><span class="dim">Events are not a cohort or a count of distinct calls</span></div><div class="table-scroll"><table><thead><tr><th>UTC day</th><th>Reporting devices</th><th>Shows</th><th>Accepts</th><th>Declines</th><th>Timeouts</th><th>Cancels</th><th>Ends</th><th>Failures</th><th>Degradations</th></tr></thead><tbody>';
  d.daily.forEach(function(x){t+='<tr><td>'+esc(x.day)+'</td>';if(!x.reported)t+='<td colspan="9" class="dim">No rollup reported</td>';else ['reportingDevices','shows','accepts','declines','timeouts','cancels','ends','failures','degradations'].forEach(function(k){t+='<td>'+num(x[k])+'</td>'});t+='</tr>'});
  el('main').innerHTML=t+'</tbody></table></div>';drawTrend()}
function drawTrend(){var d=LAST_ANALYTICS;if(!d)return;var rows=d.daily,max=Math.max(1,...rows.filter(function(x){return x.reported}).map(function(x){return x[CHART_METRIC]}));
  var path='',points='',connected=false;rows.forEach(function(r,i){var x=24+(rows.length===1?.5:i/(rows.length-1))*940,y=188-(r[CHART_METRIC]||0)/max*164;
    if(!r.reported){connected=false;return}path+=(connected?' L':' M')+x+' '+y;connected=true;points+='<circle cx="'+x+'" cy="'+y+'" r="4" fill="var(--accent)"><title>'+esc(r.day)+' · '+num(r[CHART_METRIC])+' '+esc(CHART_METRIC)+'</title></circle>'});
  el('trendChart').innerHTML='<svg role="img" aria-label="Daily '+esc(CHART_METRIC)+'; missing days are gaps" viewBox="0 0 1000 220" preserveAspectRatio="none"><title>Daily '+esc(CHART_METRIC)+'</title><path d="M24 188 H964" stroke="var(--line)"/><text x="24" y="16" fill="var(--dim)" font-size="12">'+num(max)+'</text><path d="'+path+'" fill="none" stroke="var(--accent)" stroke-width="2"/>'+points+'</svg><div class="labels"><span>'+esc(d.from)+'</span><span>'+esc(d.to)+'</span></div>'}
function renderSilent(d){var t='<div class="section-head"><h2>Missing check-ins</h2><span class="pill">'+num(d.total)+' devices</span></div><p class="notice">No contact for more than '+d.hours+' hours (before '+esc(stamp(d.cutoff))+'). Offline operation, force-stop, background restrictions and disabled telemetry can all delay reports. This list does not identify the cause. A kill-switch device may still send its recovery poll.</p>';
  t+='<div class="table-scroll"><table><thead><tr><th>App</th><th>Device label</th><th>Device / API</th><th>Last contact (UTC)</th><th>Flags</th></tr></thead><tbody>';
  d.devices.forEach(function(x){t+='<tr data-act="device" data-install="'+esc(x.install_id)+'"><td>'+esc(x.app_id)+'</td><td>'+esc(x.device_label)+'</td><td>'+esc(x.manufacturer)+' '+esc(x.model)+' / '+esc(x.sdk_int)+'</td><td>'+esc(stamp(x.last_seen))+'</td><td>'+(x.killed?'<span class="pill killed">telemetry killed</span> ':'')+(x.pull_requested?'<span class="pill pull">pull pending</span>':'')+'</td></tr>'});
  if(!d.devices.length)t+='<tr><td colspan="5" class="empty">No matching devices</td></tr>';
  el('main').innerHTML=t+'</tbody></table></div>'+pager(d,d.devices.length)}
function renderTimeline(d){var t='<div class="section-head"><h2>Captured call and device timeline</h2><span class="pill">'+num(d.total)+' uploads</span></div><p class="notice">Retained incident and requested-buffer uploads, ordered by server receipt. Healthy calls do not have complete uploaded histories. The call filter matches upload metadata; open a full-buffer upload to search calls inside its logs. Adjusted device time uses the upload clock offset.</p>'+partial(d);
  t+='<div class="table-scroll"><table><thead><tr><th>Received (UTC)</th><th>Adjusted device time</th><th>App / device</th><th>Kind / issue</th><th>Call ID</th><th>Context</th></tr></thead><tbody>';
  d.incidents.forEach(function(i){var v=i.devices||{},it=i.issues||{};t+='<tr><td>'+esc(stamp(i.server_ts))+'</td><td title="Device clock: '+esc(i.device_ts||'unknown')+'; offset ms: '+esc(i.clock_offset_ms)+'">'+esc(stamp(i.corrected_device_ts))+'</td><td><button data-act="device" data-install="'+esc(i.install_id)+'">'+esc(v.device_label||i.install_id)+'</button><div class="dim">'+esc(v.app_id)+'</div></td><td>'+esc(it.incident_type||i.kind)+'<div class="mono dim">'+esc(it.codepoint||'')+'</div></td><td class="mono">'+esc(i.call_id||'—')+'</td><td><button data-act="payload" data-path="'+esc(encodeURIComponent(i.payload_path))+'">Open log</button></td></tr>'});
  if(!d.incidents.length)t+='<tr><td colspan="6" class="empty">No captured uploads match these filters</td></tr>';
  el('main').innerHTML=t+'</tbody></table></div>'+pager(d,d.incidents.length)}
function renderLogRecords(){var call=el('logCallFilter').value.trim(),records=LOG_RECORDS.filter(function(r){return !call||r.callId===call});
  el('logCount').textContent=num(Math.min(LOG_VISIBLE,records.length))+' shown · '+num(records.length)+' / '+num(LOG_RECORDS.length)+' records match';el('moreLogs').hidden=LOG_VISIBLE>=records.length;
  el('logRecords').innerHTML=records.slice(0,LOG_VISIBLE).map(function(r){return '<div class="rec"><span class="lvl '+esc(['ERROR','INFO','VERBOSE'].includes(r.level)?r.level:'')+'">'+esc(r.level||'')+'</span> <span class="mono">'+esc(r.cp||r.codepoint||r.tag||'')+'</span> '+esc(r.event||r._raw||'')+
    '<div class="record-meta">'+esc(stamp(r.wall==null?r.wallMs:r.wall))+' (device clock) · boot '+esc(r.boot==null?'?':r.boot)+' · elapsed '+esc(r.elapsed==null?'?':r.elapsed)+' ms · call '+esc(r.callId||'—')+'</div>'+
    (r.data?'<pre>'+esc(JSON.stringify(r.data,null,2))+'</pre>':'')+(r.stack?'<pre>'+esc(r.stack)+'</pre>':'')+'</div>'}).join('')||'<div class="empty">No records match this call ID</div>'}
document.addEventListener('change',function(e){
  if(e.target.id==='chartMetric'){CHART_METRIC=e.target.value;drawTrend();return}
  if(['rangePreset','fromDate','toDate','silentHours'].includes(e.target.id)){
    if(e.target.id==='fromDate'||e.target.id==='toDate')el('rangePreset').value='custom';else if(e.target.id==='rangePreset')setRange();resetPage();reload()}});
document.addEventListener('input',function(e){if(e.target.id==='logCallFilter'){LOG_VISIBLE=500;renderLogRecords()}
  else if(e.target.id==='callFilter'||e.target.id==='installFilter'){clearTimeout(si);si=setTimeout(function(){resetPage();reload()},350)}});
`;
