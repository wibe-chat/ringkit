/** Correlate only within one call and one boot. A playing flag is not an audibility test. */
export function callNarratives(records, offsetMs = null) {
  const groups = new Map();
  for (const r of records) {
    if (typeof r.callId !== 'string' || !r.callId) continue;
    const boot = Number.isFinite(r.boot) ? r.boot : 'unknown';
    const key = JSON.stringify([r.callId, boot]);
    if (!groups.has(key)) groups.set(key, { callId: r.callId, boot, physicalSound: 'unknown', records: [] });
    const text = String(r.event || ''), cp = String(r.cp || '');
    let stage = 'Native event';
    if (/policy/i.test(text)) stage = 'Sound policy';
    else if (/focus/i.test(text + cp)) stage = 'Audio focus';
    else if (/rung|fallback|playback|Playing|audio state/i.test(text)) stage = 'Playback engine';
    else if (/accept|decline|cancel|timeout|terminal|stop/i.test(text + cp)) stage = 'Stop / call state';
    else if (/show|admit|ringing/i.test(text + cp)) stage = 'Call admission / state';
    groups.get(key).records.push({ ...r, stage,
      correctedWall: Number.isFinite(offsetMs) && Number.isFinite(r.wall) && Math.abs(r.wall + offsetMs) <= 8640000000000000 ? r.wall + offsetMs : null,
      engineStartOnly: r.data && Object.hasOwn(r.data, 'audible') });
  }
  for (const g of groups.values()) g.records.sort((a, b) => (a.elapsed ?? Infinity) - (b.elapsed ?? Infinity) || (a.seq ?? 0) - (b.seq ?? 0));
  return [...groups.values()];
}

export const DIAGNOSTIC_CLIENT = `
${callNarratives.toString()}
function diagnosticPanel(d){
  var v=d.device, supported=v.telemetry_protocol>=2&&(v.capabilities||[]).indexOf('diagnostic_session')>=0;
  var s='<h2>Remote diagnostics</h2><p class="notice">Physical sound remains unknown until a tester confirms it. Registration and engine playback flags do not establish audible ringing.</p>';
  s+='<div class="kv"><div>Native build</div><div class="mono">'+esc(v.sdk_build_id||'unknown')+'</div>'+
    '<div>Host version code</div><div>'+esc(v.host_version_code==null?'unknown':v.host_version_code)+'</div>'+
    '<div>Host debuggable</div><div>'+esc(v.host_debuggable==null?'unknown':String(v.host_debuggable))+' (does not establish Flutter build mode or a real user)</div>'+
    '<div>Last control contact</div><div>'+esc(v.last_control_at||'not yet')+'</div>'+
    '<div>Capabilities</div><div>'+esc((v.capabilities||[]).join(', ')||'unknown')+'</div></div>';
  s+='<p>'+(!supported?'Unsupported: update the native SDK and open the app to refresh its identity.':v.diagnostics_enabled?'Enrolled for diagnostics.':'Not enrolled in the diagnostics canary.')+'</p>';
  s+='<div class="row"><button data-act="diagnosticEnroll" data-install="'+esc(v.install_id)+'" data-enabled="'+(!v.diagnostics_enabled)+'">'+(v.diagnostics_enabled?'Disable diagnostics':'Enable diagnostics canary')+'</button>'+
    '<button data-act="diagnosticStart" data-install="'+esc(v.install_id)+'" '+(!supported||!v.diagnostics_enabled||v.killed?'disabled':'')+'>Start 30-minute session</button></div>';
  s+='<p class="dim">Up to 20 calls and 512 KiB of decoded upload bodies. Open the app and wait for acknowledgement before testing. Offline or force-stopped phones cannot be woken by this request.</p>';
  var requests=d.requests||[], parts=d.parts||[];
  if(d.requestsTruncated)s+='<p class="warning">Request history is limited to the latest 20 requests plus active requests.</p>';
  if(d.partsTruncated)s+='<p class="warning">The retained part list is incomplete: only the latest 500 parts are displayed. Receipt totals include all stored parts.</p>';
  if(!requests.length)s+='<p>No diagnostic request history. Coverage is unknown.</p>';
  requests.forEach(function(r){
    var mine=parts.filter(function(p){return p.request_id===r.id}), state=r.state;
    if(['queued','offered','acknowledged','collecting'].indexOf(state)>=0&&r.expires_at&&Date.parse(r.expires_at)<=Date.now())state='expired';
    var explanation=state==='queued'?'pending device contact':state==='offered'?'awaiting device acknowledgement':state==='acknowledged'?'device persisted request; waiting for evidence':state==='collecting'?'partial; final receipt pending':state==='received_empty'?'empty; no call-log evidence received':state==='received'?'expected upload parts received; assess coverage below':state;
    s+='<section class="rec"><h3>'+esc(r.kind)+' — '+esc(state)+'</h3><p>'+esc(explanation)+'</p><div class="mono">'+esc(r.id)+'</div>'+
      '<p>Requested: '+esc(r.requested_at||'unknown')+'<br>Acknowledged: '+esc(r.acknowledged_at||'not yet')+'<br>Received: '+esc(r.received_at||'not yet')+'</p>'+
      '<p>'+esc(r.call_count||0)+' calls with records; '+esc(r.record_count||0)+' records; '+mine.length+' retained parts; '+kb(r.received_bytes||0)+' received.</p>';
    if(r.last_client_status)s+='<p>Last device upload status: '+esc(r.last_client_status.outcome)+'; reported '+esc(r.last_client_status.observed_at||'unknown')+'. This is device status, separate from the durable receipt.</p>';
    if(r.end_reason)s+='<p>End reason: '+esc(r.end_reason)+'</p>';
    if(['queued','offered','acknowledged','collecting'].indexOf(state)>=0)s+='<button data-act="diagnosticStop" data-install="'+esc(v.install_id)+'" data-request="'+esc(r.id)+'">Stop diagnostics</button>';
    mine.forEach(function(p){var m=p.metadata||{}, identity=p.identity||{};
      s+='<p class="mono">Captured build: '+esc(identity.sdk_build_id||'unknown')+' · host version code '+esc(identity.host_version_code==null?'unknown':identity.host_version_code)+'</p>';
      s+='<p>Report '+esc(p.report_id)+' part '+esc(p.part)+'/'+esc(p.parts_total)+' · '+esc(m.record_count||0)+' records '+
        '<button data-act="payload" data-path="'+esc(encodeURIComponent(p.payload_path))+'" data-offset="'+esc(m.clock_offset_ms==null?'':m.clock_offset_ms)+'">Call evidence</button></p>';
      if(m.final)s+='<p>Device observed '+esc(m.observed_call_count==null?'unknown':m.observed_call_count)+' admitted calls. The 20-call limit is not an expected-call count.</p>';
      if(m.truncated||m.calls_truncated||m.omitted_records||m.unparsed_records||m.dropped_records)s+='<p class="warning">Coverage limited: truncated='+esc(!!m.truncated)+', omitted='+esc(m.omitted_records||0)+', unparsed='+esc(m.unparsed_records||0)+', local dropped='+esc(m.dropped_records||0)+'.</p>';
      if(m.diagnose)s+='<details><summary>Native diagnose() snapshot</summary><pre>'+esc(JSON.stringify(m.diagnose,null,2))+'</pre></details>';
      s+='<p class="dim">Capture boot '+esc(m.boot==null?'unknown':m.boot)+'; elapsed '+esc(m.from_elapsed==null?'unknown':m.from_elapsed)+'–'+esc(m.to_elapsed==null?'unknown':m.to_elapsed)+' ms. Route information is only available when explicitly reported.</p>';
    });
    var o=r.tester_observation||{};
    s+='<label>Tester observation (separate from SDK facts)<textarea id="observation-'+esc(r.id)+'" maxlength="1000">'+esc(o.text||'')+'</textarea></label>'+
      '<label>Test time (ISO 8601 with timezone)<input id="observed-at-'+esc(r.id)+'" value="'+esc(o.observed_at||'')+'" placeholder="2026-11-11T16:27:00+05:30"></label>'+
      '<button data-act="diagnosticObserve" data-install="'+esc(v.install_id)+'" data-request="'+esc(r.id)+'">Save observation</button></section>';
  });
  return s;
}
function narrativeHtml(records,offset){
 var groups=callNarratives(records,offset),s='<h3>Per-call evidence</h3><p class="notice">Legacy audible=true means an engine started. Physical audibility is unknown. Calls and boots are kept separate; missing stages are not inferred.</p>';
 if(!groups.length)return s+'<p>No call-correlated records in this payload.</p>';
 if(groups.length>50)s+='<p class="warning">Narrative preview shows the first 50 call/boot groups. Use the raw call filter below for other records.</p>';
 groups.slice(0,50).forEach(function(g){s+='<details open><summary>'+esc(g.callId)+' · boot '+esc(g.boot)+'</summary>';
   if(g.records.length>20)s+='<p class="dim">Preview: latest 20 of '+g.records.length+' events. Full records are in the filter below.</p>';
   g.records.slice(-20).forEach(function(r){s+='<p><b>'+esc(r.stage)+'</b> · '+esc(r.elapsed==null?'elapsed unknown':r.elapsed+' ms')+' · '+esc(r.event||'')+
     (r.engineStartOnly?' <span class="warning">engine-start flag only</span>':'')+
     (r.correctedWall!=null?' · estimated corrected wall '+esc(new Date(r.correctedWall).toISOString()):'')+'</p>'});s+='</details>'});return s;
}
function diagnosticAction(action,t){
 var id=t.getAttribute('data-install'),request=t.getAttribute('data-request'),body={install:id,request:request},drawer=DRAWER_ID;
 if(action==='diagnosticEnroll'){body.action='enroll';body.enabled=t.getAttribute('data-enabled')==='true'}
 if(action==='diagnosticStart'){body.action='start';body.kind='diagnostic_session'}
 if(action==='diagnosticStop')body.action='stop';
 if(action==='diagnosticObserve'){body.action='observe';body.text=el('observation-'+request).value;body.observed_at=el('observed-at-'+request).value}
 api('diagnostic',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}).then(function(){toast('diagnostics updated');if(drawer===DRAWER_ID)openDevice(id)}).catch(function(e){toast(e.message)})
}
`;
