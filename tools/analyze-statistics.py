"""Analyze RHRemote test traces, export an offline timeline, then discard used raw data."""
import argparse
import csv
import datetime as dt
import json
import math
import re
from collections import defaultdict
from pathlib import Path

PAIRS={"reassembly_ms":("packet_first","frame_assembled"),"decoder_queue_ms":("frame_assembled","decode_submit"),"decoder_submit_ms":("decode_submit","decode_submit_return"),"decode_ms":("decode_submit","decode_output"),"pacing_ms":("decode_output","render_submit"),"render_call_ms":("render_submit","render_return"),"surface_callback_delay_ms":("render_submit","surface_render_callback"),"client_pipeline_ms":("packet_first","render_return")}

def distribution(values):
    values=sorted(values)
    if not values:return None
    def percentile(p):
        x=(len(values)-1)*p;i=int(x);return values[i]+(values[min(i+1,len(values)-1)]-values[i])*(x-i)
    return {"count":len(values),"min":round(values[0],4),"mean":round(sum(values)/len(values),4),"p50":round(percentile(.5),4),"p95":round(percentile(.95),4),"p99":round(percentile(.99),4),"max":round(values[-1],4)}

def connection_measurement(events,platform):
    origin=next((e for e in events if e["event"]=="connect_action"),None)
    if origin is None:return None,[]
    start=origin["ts_us"]
    def first(name):return min((e for e in events if e["event"]==name and e["ts_us"]>=start),key=lambda e:e["ts_us"],default=None)
    endpoint="render_return" if platform=="windows" else "surface_render_callback"
    end=first(endpoint)
    measurement={"origin_event":"connect_action","origin_us":start,"endpoint_event":endpoint,"endpoint_us":end["ts_us"] if end else None,"endpoint_frame":end.get("frame") if end else None,"total_ms":round((end["ts_us"]-start)/1000,3) if end else None,"session_kind":"resume" if first("http_resume") else "launch" if first("http_launch") else "unknown","physical_scanout_measured":False}
    labels={"connect_request_received":"客户端操作排队","native_spawn_begin":"连接请求处理","connection_begin":"客户端连接准备","connection_ready":"串流协议握手","packet_first":"等待视频首包","decode_output":"首帧组帧及解码",endpoint:"首帧排队及渲染提交" if platform=="windows" else "首帧排队及系统回调"}
    phases=[];previous=origin
    for name in ["connect_request_received","native_spawn_begin","connection_begin","connection_ready","packet_first","decode_output",endpoint]:
        point=first(name)
        if point is None or point["ts_us"]<previous["ts_us"]:continue
        phases.append({"name":("连接准备及协议握手" if name=="connection_ready" and previous is origin else labels[name]),"from":previous["event"],"to":name,"start_us":previous["ts_us"],"end_us":point["ts_us"],"start_ms":round((previous["ts_us"]-start)/1000,3),"end_ms":round((point["ts_us"]-start)/1000,3),"duration_ms":round((point["ts_us"]-previous["ts_us"])/1000,3)})
        previous=point
    return measurement,phases

def analyze(events,manifest):
    events=sorted(events,key=lambda e:e.get("ts_us",0));frames={};errors=[];ends=[];lost=0
    for e in events:
        if e["event"]=="trace_end":ends.append(e);lost+=int(e.get("lost_events",0))
        if e.get("frame",-1)<0:continue
        f=frames.setdefault(int(e["frame"]),{"frame":int(e["frame"]),"events":{}})
        # A frame may have several configuration NALUs. Only picture input defines a frame.
        f["events"].setdefault(e["event"],e["ts_us"])
        fields=e.get("fields",{})
        if e["event"]=="frame_assembled":f.update({k:fields[k] for k in ["bytes","host_processing_us","keyframe"] if k in fields})
        if e["event"]=="frame_drop":f["drop_reason"]=fields.get("reason","unknown")
        if e["event"]=="decode_submit_return" and fields.get("code",0)<0:f["decode_error"]=fields["code"]
    distributions={};negative=0
    for name,(a,b) in PAIRS.items():
        vals=[]
        for f in frames.values():
            t=f["events"]
            if a in t and b in t:
                v=(t[b]-t[a])/1000
                if v<0:negative+=1;continue
                f[name]=round(v,4);vals.append(v)
        distributions[name]=distribution(vals)
    distributions["host_processing_ms"]=distribution([f["host_processing_us"]/1000 for f in frames.values() if f.get("host_processing_us",0)>0])
    frame_gaps={}
    for name in ["packet_first","decode_output","render_return","surface_rendered"]:
        stamps=sorted(f["events"][name] for f in frames.values() if name in f["events"])
        gaps=[(b-a)/1000 for a,b in zip(stamps,stamps[1:])]
        frame_gaps[name]={"frames":len(stamps),"interval_ms":distribution(gaps),"fps":round((len(stamps)-1)*1e6/(stamps[-1]-stamps[0]),3) if len(stamps)>1 and stamps[-1]>stamps[0] else None,"over_50ms":sum(x>50 for x in gaps)}
    by_name=defaultdict(list)
    for e in events:by_name[e["event"]].append(e)
    connect=by_name["connect_action"][0]["ts_us"] if by_name["connect_action"] else None
    startup={}
    app=[e for e in events if e["event"] in ["application_start","preferences_loaded","devices_restored","ui_created","ui_shown"]]
    if app and app[-1]["event"]=="ui_shown":startup["application_to_window_ms"]=round((app[-1]["ts_us"]-app[0]["ts_us"])/1000,3)
    if connect is not None:
        for name in ["connect_request_received","native_spawn_begin","native_start","native_ui_ready","connection_begin","connection_ready","packet_first","decode_output","render_submit","render_return","surface_rendered","surface_render_callback"]:
            ts=[e["ts_us"] for e in by_name[name] if e["ts_us"]>=connect]
            if ts:startup[name+"_ms"] =round((min(ts)-connect)/1000,3)
    spans=[{"name":e["event"],"ms":round(e["dur_us"]/1000,3),"start_us":e["ts_us"]} for e in events if e.get("dur_us") and e.get("frame",-1)<0]
    for prev,nxt in zip(app,app[1:]):spans.append({"name":"application: "+prev["event"]+" → "+nxt["event"],"ms":round((nxt["ts_us"]-prev["ts_us"])/1000,3),"start_us":prev["ts_us"]})
    stages={}
    for e in events:
        if e["event"]=="connection_stage_begin":stages[e.get("fields",{}).get("name","unknown")]=e["ts_us"]
        elif e["event"]=="connection_stage_end":
            n=e.get("fields",{}).get("name","unknown")
            if n in stages:spans.append({"name":"connection: "+n,"ms":round((e["ts_us"]-stages[n])/1000,3),"start_us":stages.pop(n)})
    ids=sorted(f["frame"] for f in frames.values() if "frame_assembled" in f["events"])
    missing=sum(max(0,b-a-1) for a,b in zip(ids,ids[1:]))
    if not ends:errors.append("Trace has no trace_end footer: process may have terminated; completeness is unverified.")
    if lost:errors.append(f"Recorder lost {lost} events; timeline is incomplete.")
    if negative:errors.append(f"Excluded {negative} negative durations (clock/association mismatch).")
    if by_name["connection_stage_failed"]:errors.append("Connection failed; partial trace retained.")
    summary={"schema":"rhremote.statistics.analysis/v1","platform":manifest.get("platform"),"profile":manifest.get("profile"),"event_count":len(events),"frame_count":len(frames),"recorder_lost_events":lost,"warnings":errors,"startup":startup,"startup_spans":sorted(spans,key=lambda s:s["start_us"]),"frame_metrics":distributions,"cadence":frame_gaps,"missing_frame_numbers":missing,"frame_drops":sum("frame_drop" in f["events"] for f in frames.values()),"interpretation":{"Windows":"render_return measures CPU render/present submission return, not physical screen scanout.","Android":"surface_render_callback is callback arrival. surface_rendered is the framework-reported timestamp and can precede submission (e.g. a requested Choreographer timestamp). Neither measures physical scanout.","host":"host_processing_ms is a protocol aggregate. Capture/encode sub-stages require host instrumentation.","network":"packet_first is available for assembled frames; missing frame IDs do not identify the loss location."}}
    summary["connection_measurement"],summary["connection_phases"]=connection_measurement(events,manifest.get("platform"))
    preparation=by_name["host_display_prepared"]
    if preparation:summary["display_preparation"]=preparation[-1].get("fields",{})
    return summary,list(frames.values())

def import_host(path,lo,hi):
    data=json.loads(path.read_text(encoding="utf-8-sig"));offset=data.get("clockOffsetUs",0);zone=dt.timezone(dt.timedelta(minutes=data.get("utcOffsetMinutes",480)));result=[];probe=None;encoding=None;scopes={}
    for line in data.get("log",[]):
        m=re.match(r"\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\]: \w+: (.*)",line)
        if not m:continue
        ts=int(dt.datetime.fromisoformat(m[1]).replace(tzinfo=zone).timestamp()*1e6)-offset
        if not lo-2e6<=ts<=hi+2e6:continue
        message=m[2];name="host_log"
        phase=re.match(r"RHREMOTE_PHASE (begin|end) id=(\d+) name=(\w+) tick_us=(\d+)(?: elapsed_us=(\d+))?",message)
        if phase:
            if phase[1]=="begin":scopes[phase[2]]=(ts,phase[3])
            elif phase[2] in scopes:
                start,stage=scopes.pop(phase[2]);result.append({"event":"host_"+stage,"ts_us":start,"dur_us":int(phase[5]),"frame":-1,"thread":"Sunshine "+stage,"fields":{"measurement":"server steady clock; instrumented source","scopeId":phase[2]}})
            continue
        if "Trying encoder [" in message and probe is None:probe=ts;name="host_encoder_probe_begin"
        if "Encoder reenumeration is required" in message:probe=ts;name="host_encoder_probe_begin"
        if "Creating encoder" in message:encoding=(ts,message);name="host_encoder_create"
        if "Streaming bitrate" in message and encoding:
            start,encoder=encoding;result.append({"event":encoder,"ts_us":start,"dur_us":ts-start,"frame":-1,"thread":"Sunshine encoder","fields":{"measurement":"log marker interval; not complete encoder initialization"}});encoding=None
        if ("Executing [" in message or "CLIENT CONNECTED" in message) and probe:
            result.append({"event":"host_encoder_reenumeration","ts_us":probe,"dur_us":ts-probe,"frame":-1,"thread":"Sunshine launch","fields":{"measurement":"log marker interval; may include unrelated waiting"}});probe=None
        result.append({"event":name,"ts_us":ts,"frame":-1,"thread":"Sunshine log","fields":{"message":message}})
    measured={e["event"] for e in result if e.get("fields",{}).get("scopeId")}
    # A log interval can span idle time between startup and the first client.
    # Do not present it as another encoder duration beside the exact scope.
    result=[e for e in result if not (
        e["event"]=="host_encoder_reenumeration" and "host_encoder_probe" in measured or
        e["thread"]=="Sunshine encoder" and "host_encoder_create" in measured)]
    return result,{"offset_us":offset,"uncertainty_us":data.get("clockUncertaintyUs"),"alignment":"SSH midpoint estimate; host substage durations use host clock only"}

def chrome(events):
    output=[]
    for e in events:
        args=dict(e.get("fields",{}));args["frame"]=e.get("frame",-1)
        r={"name":e["event"],"cat":"frame" if args["frame"]>=0 else "startup","ph":"X" if e.get("dur_us") else "i","ts":e["ts_us"],"pid":1 if str(e.get("thread","")).startswith("Sunshine") else 0,"tid":e.get("thread","unknown"),"args":args}
        if e.get("dur_us"):r["dur"]=e["dur_us"]
        else:r["s"]="t"
        output.append(r)
    return {"traceEvents":output,"displayTimeUnit":"ms"}

def report_html(summary,frames,events):
    payload=json.dumps({"summary":summary,"frames":frames,"events":events},ensure_ascii=False,separators=(",",":")).replace("<","\\u003c")
    template=Path(__file__).with_name("statistics-viewer.html").read_text(encoding="utf-8")
    return template.replace("/*RHREMOTE_DATA*/",payload)

def main():
    p=argparse.ArgumentParser();p.add_argument("run",type=Path);p.add_argument("--host-evidence",type=Path);p.add_argument("--refresh-report",action="store_true",help="Refresh an analyzed report from its embedded events without recapturing or recreating deleted raw traces");p.add_argument("--keep-raw",action="store_true",help="Keep only while needed for further investigation; creates a keep marker")
    a=p.parse_args();root=a.run.resolve();manifest=json.loads((root/"manifest.json").read_text(encoding="utf-8-sig"))
    if manifest.get("schema")!="rhremote.statistics/v1":raise ValueError("Not a RHRemote statistics run")
    if a.refresh_report:
        html=(root/"report.html").read_text(encoding="utf-8");match=re.search(r"<script>\s*const D=",html)
        if not match:raise ValueError("Report has no embedded trace data")
        previous,_=json.JSONDecoder().raw_decode(html[match.end():]);events=previous["events"]
        if a.host_evidence:
            events=[e for e in events if not str(e.get("thread","")).startswith("Sunshine")]
        summary,frames=analyze(events,manifest)
        if a.host_evidence and events:
            first=next((e["ts_us"] for e in events if e["event"]=="connect_action"),min(e["ts_us"] for e in events))
            host,alignment=import_host(a.host_evidence,first,max(e["ts_us"] for e in events));events+=host;events.sort(key=lambda e:e["ts_us"])
            summary["host_clock"]=alignment
            summary["host_spans"]=[{"name":e["event"],"ms":round(e["dur_us"]/1000,3)} for e in host if e.get("dur_us")]
        else:
            for key in ["host_clock","host_spans"]:
                if key in previous["summary"]:summary[key]=previous["summary"][key]
        (root/"summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
        (root/"timeline.json").write_text(json.dumps(chrome(events),ensure_ascii=False,separators=(",",":")),encoding="utf-8")
        (root/"report.html").write_text(report_html(summary,frames,events),encoding="utf-8")
        print(json.dumps({"report":str(root/"report.html"),"measurement":summary["connection_measurement"],"phases":summary["connection_phases"]},ensure_ascii=False,indent=2));return
    events=[];raw=[];malformed=0
    for name in manifest["files"]:
        if Path(name).name!=name or not name.endswith(".jsonl"):raise ValueError("Invalid statistics input path")
        path=root/name
        if path.is_symlink():raise ValueError("Statistics symlinks are not accepted")
        if not path.exists():continue
        raw.append(path)
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            try:events.append(json.loads(line))
            except json.JSONDecodeError:malformed+=1
    summary,frames=analyze(events,manifest)
    if malformed:summary["warnings"].append(f"Ignored {malformed} truncated JSON records.")
    if a.host_evidence and events:
        first=next((e["ts_us"] for e in events if e["event"]=="connect_action"),min(e["ts_us"] for e in events))
        host,alignment=import_host(a.host_evidence,first,max(e["ts_us"] for e in events));events+=host;summary["host_clock"]=alignment
        summary["host_spans"]=[{"name":e["event"],"ms":round(e["dur_us"]/1000,3)} for e in host if e.get("dur_us")]
    events.sort(key=lambda e:e["ts_us"])
    (root/"summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
    (root/"timeline.json").write_text(json.dumps(chrome(events),ensure_ascii=False,separators=(",",":")),encoding="utf-8")
    columns=["frame","bytes","host_processing_us","drop_reason",*PAIRS]
    with (root/"frames.csv").open("w",encoding="utf-8",newline="") as out:
        writer=csv.DictWriter(out,fieldnames=columns,extrasaction="ignore");writer.writeheader();writer.writerows(frames)
    (root/"report.html").write_text(report_html(summary,frames,events),encoding="utf-8")
    complete=not summary["warnings"]
    if complete and not a.keep_raw:
        for path in raw:path.unlink()
    if a.keep_raw:(root/"keep").write_text("Needed for active investigation; remove after use.\n",encoding="utf-8")
    if complete:(root/"analysis-complete.json").write_text(json.dumps({"rawDeleted":not a.keep_raw,"schema":"rhremote.statistics.cleanup/v1"}),encoding="utf-8")
    print(json.dumps({"report":str(root/"report.html"),"startup":summary["startup"],"host_spans":summary.get("host_spans",[]),"frames":summary["frame_count"],"warnings":summary["warnings"],"raw_deleted":complete and not a.keep_raw},ensure_ascii=False,indent=2))

if __name__=="__main__":main()
