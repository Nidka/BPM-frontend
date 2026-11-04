@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.features.incidents.presentation

@JsFun(
    """(date, time, zone) => {
 try {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time) || !zone) return null;
  const raw = date + 'T' + time + ':00Z';
  const naive = Date.parse(raw);
  if (!Number.isFinite(naive) || new Date(naive).toISOString().slice(0,16) !== raw.slice(0,16)) return null;
  const fmt = new Intl.DateTimeFormat('en-CA', {timeZone:zone, year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});
  const parts = ms => Object.fromEntries(fmt.formatToParts(new Date(ms)).map(p => [p.type,p.value]));
  const local = ms => { const p=parts(ms); return p.year+'-'+p.month+'-'+p.day+'T'+p.hour+':'+p.minute+':'+p.second+'Z'; };
  let guess=naive;
  for(let i=0;i<4;i++) guess += naive-Date.parse(local(guess));
  const offsets = [...new Set([-172800000,-86400000,0,86400000,172800000].map(d=>Date.parse(local(guess+d))-(guess+d)))];
  const candidates = offsets.map(offset=>naive-offset).filter(ms=>local(ms)===raw);
  return candidates.length===1 ? new Date(candidates[0]).toISOString() : null;
 } catch (_) { return null; }
}"""
)
private external fun deadlineJs(date: String, time: String, zone: String): String?

internal actual fun incidentDeadline(date: String, time: String, timeZone: String): String? =
    deadlineJs(date, time, timeZone)
