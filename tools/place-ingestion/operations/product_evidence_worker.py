#!/usr/bin/env python3
"""Private future-run product worker. No unbound/standalone mutation CLI.

Each HTTP exchange runs in an isolated disposable client subprocess. A single
parent monotonic 5s deadline covers startup, DNS, headers and complete body; the
client is terminated on deadline (never a server/container). Secrets/body bytes
exist only in memory/pipes, never artifacts, argv, logs or environment.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid
from datetime import datetime, timezone
from persistent_telemetry_worker import read, publish, private, policy, execution_target, now, OLD_RUN

CHECKS = {"search","turkish_search","nearby","bounds","selected_place_coverage","public_provider_id_isolation",
          "zero_experience_detail","want_to_go","collections","synthetic_v2_experience","detail_after_publication",
          "experience_first_explore","rollback_safety","graph_safety"}
SCHEMA="v3-product-evidence-v1"
METHOD="didim-autonomous-validation-v3"
ORIGIN="https://api.phokarta.com"
FAILURE_CODES={'PRODUCT_TIMEOUT','PRODUCT_HTTP_FAILURE','PRODUCT_READBACK_FAILED','SYNTHETIC_CLEANUP_FAILED',
    'HTTP_SEMANTICS_OR_DEADLINE','UNSAFE_REQUEST_ID','TARGET_CHANGED_OR_STALE','TARGET_ATTESTATION_CHANGED','WORKER_STOPPED',
    'PRODUCT_MUTATION_NOT_AUTHORIZED','GRAPH_SAFETY_EVIDENCE_INVALID','SEARCH_COVERAGE','TURKISH_COVERAGE','BOUNDS_COVERAGE',
    'BOUNDS_GEOMETRY','DENSE_MARKERS','BOUNDS_LIMIT','NEARBY_COVERAGE','NEARBY_GEOMETRY_ORDER','ZERO_DETAIL','ZERO_AGGREGATE',
    'PROVIDER_FIELD_LEAK','PROVIDER_VALUE_LEAK','PRODUCT_HARD_FAILURE','SYNTHETIC_IDENTITY_MISMATCH'}
SENDER=r'''
import json,sys,urllib.request,urllib.error
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None
p=json.load(sys.stdin)
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
data=None if p['payload'] is None else json.dumps(p['payload']).encode()
headers={'Accept':'application/json','X-Request-ID':p['request_id']}
if data is not None: headers['Content-Type']='application/json'
if p['token']: headers['Authorization']='Bearer '+p['token']
try: response=opener.open(urllib.request.Request(p['url'],data=data,headers=headers,method=p['method']),timeout=5)
except urllib.error.HTTPError as error: response=error
with response as r:
    raw=r.read(4*1024*1024+1)
    if len(raw)>4*1024*1024: raise ValueError('BODY_LIMIT')
    try: body=json.loads(raw) if raw else None
    except ValueError: body=None
    json.dump({'status':r.status,'request_id':r.headers.get('X-Request-ID') or p['request_id'],
               'body':body},sys.stdout)
'''

def require(ok,category="PRODUCT_HARD_FAILURE"):
    if not ok: raise ValueError(category)


class RequestFailure(ValueError):
    def __init__(self,category,observation):
        super().__init__(category)
        self.observation=observation


class Transport:
    def __init__(self,origin=ORIGIN): self.origin=origin
    def request(self,method,path,payload=None,token=None):
        started=time.monotonic()
        request_id="m55b-product-"+secrets.token_hex(12)
        data=json.dumps({"method":method,"url":self.origin+path,"payload":payload,"token":token,"request_id":request_id}).encode()
        # Windows loopback test clients need SystemRoot for Winsock; no inherited secrets.
        environment={'SystemRoot':os.environ['SystemRoot']} if os.name=='nt' else {}
        child=subprocess.Popen([sys.executable,"-I","-c",SENDER],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,
                               env=environment,close_fds=True)
        try:
            remaining=5-(time.monotonic()-started)
            require(remaining>0,"PRODUCT_TIMEOUT")
            raw,_=child.communicate(data,timeout=remaining)
            require(child.returncode==0 and len(raw)<=5*1024*1024,"PRODUCT_HTTP_FAILURE")
            result=json.loads(raw)
            elapsed=(time.monotonic()-started)*1000
            require(elapsed<5000,"PRODUCT_TIMEOUT")
            result["latency_ms"]=elapsed
            return result
        except Exception as error:
            category="PRODUCT_TIMEOUT" if isinstance(error,subprocess.TimeoutExpired) or str(error)=="PRODUCT_TIMEOUT" else "PRODUCT_HTTP_FAILURE"
            raise RequestFailure(category,{"operation":method,"status":None,"validation":category,
                "latency_ms":(time.monotonic()-started)*1000,"request_id":request_id}) from None
        finally:
            if child.poll() is None: child.kill()
            child.communicate() # reap only this client; never retry the request.


class Worker:
    def __init__(self,directory,plan_hash,manifest_path,manifest_sha,authorization,predecessor_path,predecessor_sha,transport=None):
        private(directory)
        self.directory=directory
        self.plan=read(directory/"V3_OPERATIONS_PLAN.json",plan_hash)
        self.plan_hash=plan_hash
        policy(self.plan["target_policy"])
        require(self.plan["version"]=="v3-private-operations-plan-v2" and self.plan["validation_method"]==METHOD)
        require(re.fullmatch(r"[0-9a-f]{64}",manifest_sha) is not None)
        require(manifest_path.is_file() and not manifest_path.is_symlink() and manifest_path.stat().st_size<=128*1024*1024)
        raw=manifest_path.read_bytes()
        require(hashlib.sha256(raw).hexdigest()==manifest_sha)
        envelope=json.loads(raw,object_pairs_hook=__import__('persistent_telemetry_worker').pairs)
        require(re.fullmatch(r"[0-9a-f]{64}",predecessor_sha) is not None)
        require(predecessor_path.is_file() and not predecessor_path.is_symlink() and predecessor_path.stat().st_size<=128*1024*1024)
        predecessor_raw=predecessor_path.read_bytes()
        require(hashlib.sha256(predecessor_raw).hexdigest()==predecessor_sha)
        predecessor=json.loads(predecessor_raw,object_pairs_hook=__import__('persistent_telemetry_worker').pairs)
        from phokarta_place_ingestion.canary_manifest import validate_canary_manifest_contract
        validate_canary_manifest_contract(envelope,predecessor=predecessor)
        self.manifest=envelope["manifest"]
        require(self.manifest["method_version"]==METHOD and self.manifest["run_id"]!=OLD_RUN)
        require(self.plan["run_id"]==self.manifest["run_id"] and self.plan["manifest_hash"]==envelope["manifest_hash"])
        require(authorization==self.manifest["authorization_reference"]==self.plan["authorization_reference"])
        self.authorization=authorization
        self.selected=sorted((c for c in self.manifest["candidates"] if c["selected_for_stage"]),key=lambda c:c["selection_rank"])
        self.ids=sorted(c["canonical_place_id"] for c in self.selected)
        require(len(self.ids)==71 and len(set(self.ids))==71 and sorted(self.plan["selected_canonical_ids"])==self.ids)
        require(set(self.plan["product_checks"])==CHECKS and len(self.plan["product_checks"])==len(CHECKS))
        self.attestation=execution_target(directory,self.plan,plan_hash)
        self.provider_ids={s["external_id"] for s in self.manifest["source_records"]}
        self.transport=transport or Transport()
        self.evidence=[]
        self.checks={}
        self.token=self.password=self.user_id=None
        self.cleanup="NOT_NEEDED"
        self.registration_label=None
        self.authorized=False
        self.current()

    def current(self):
        require(execution_target(self.directory,self.plan,self.plan_hash)==self.attestation,"TARGET_ATTESTATION_CHANGED")
        c=read(self.directory/"CURRENT_TARGET.json")
        at=datetime.fromisoformat(c["observed_at"].replace("Z","+00:00"))
        require(c["target"]==self.attestation["target"] and 0<=(datetime.now(timezone.utc)-at).total_seconds()<=30,"TARGET_CHANGED_OR_STALE")
        require(not any((self.directory/p).exists() for p in ("WORKER_FAILED.json","STOP_PROBE.json")),"WORKER_STOPPED")

    def ready(self):
        self.current()
        publish(self.directory/"PRODUCT_WORKER_READY.json",{"version":"v3-authorized-product-workflow-v1","run_id":self.plan["run_id"],
                "manifest_hash":self.plan["manifest_hash"],"validation_method":METHOD,"evidence_schema_version":SCHEMA,"observed_at":now()},heartbeat=True)

    def isolation(self,body):
        forbidden={"provider","providerid","externalid","fsqid","foursquareid","overtureid","sourcerecordid","sourcerecords","provenance"}
        if isinstance(body,dict):
            for k,v in body.items():
                require(re.sub(r"[^a-z0-9]","",k.lower()) not in forbidden,"PROVIDER_FIELD_LEAK")
                self.isolation(v)
        elif isinstance(body,list):
            for v in body: self.isolation(v)
        elif isinstance(body,str): require(body not in self.provider_ids,"PROVIDER_VALUE_LEAK")

    def request(self,method,path,payload=None,authenticated=False,expected=200,scan=True,cleanup=False):
        if not cleanup: self.current()
        if method!='GET': require(self.authorized,"PRODUCT_MUTATION_NOT_AUTHORIZED")
        try: r=self.transport.request(method,path,payload,self.token if authenticated else None)
        except RequestFailure as error:
            self.evidence.append(error.observation); raise
        require(re.fullmatch(r"[A-Za-z0-9_-]{1,100}",r["request_id"]),"UNSAFE_REQUEST_ID")
        e={"operation":method,"status":r["status"],"validation":"INVALID","latency_ms":r["latency_ms"],"request_id":r["request_id"]}
        self.evidence.append(e)
        require(r["status"]==expected and math.isfinite(r["latency_ms"]) and 0<r["latency_ms"]<5000,"HTTP_SEMANTICS_OR_DEADLINE")
        if scan: self.isolation(r["body"])
        e['validation']='VALID'
        return r["body"]

    def passed(self,name,start,ids=None):
        require(len(self.evidence)>start)
        self.checks[name]={"status":"PASS","observed_at":now(),"canonical_place_ids":ids or self.ids,
                           "evidence":self.evidence[start:]}

    def authorize_request(self):
        self.current()
        q=read(self.directory/"PRODUCT_REQUEST.json")
        require(set(q)=={"version","run_id","manifest_hash","requested_at","authorization_reference","validation_method","evidence_schema_version"})
        require(q["version"]=="v3-product-request-v1" and q["run_id"]==self.plan["run_id"] and q["manifest_hash"]==self.plan["manifest_hash"]
                and q["authorization_reference"]==self.authorization and q["validation_method"]==METHOD and q["evidence_schema_version"]==SCHEMA)
        requested=datetime.fromisoformat(q["requested_at"].replace("Z","+00:00"))
        require(0<=(datetime.now(timezone.utc)-requested).total_seconds()<=30)
        # Importer emits the request only AFTER valid POST and committed hard downstream checks.
        receipt=read(self.directory/"POST_RECEIPT.json")
        require(receipt['version']=='persistent-telemetry-receipt-v1' and receipt['run_id']==self.plan['run_id']
                and receipt['manifest_hash']==self.plan['manifest_hash'] and receipt['role']=='POST','PRODUCT_HARD_FAILURE')
        post=read(self.directory/"POST.json",receipt["artifact_sha256"])
        require(post["run_id"]==self.plan["run_id"] and post["manifest_hash"]==self.plan["manifest_hash"] and post["role"]=="POST"
                and post["outcome"]=="COMPLETE" and post["target"]==self.attestation["target"])
        require(receipt["artifact_bytes"]==(self.directory/"POST.json").stat().st_size)
        proof=read(self.directory/"PRODUCT_GRAPH_SAFETY.json")
        require(proof["version"]=="v3-containment-inspection-v1" and proof["run_id"]==self.plan["run_id"]
                and proof["manifest_hash"]==self.plan["manifest_hash"] and proof["validation_method"]==METHOD
                and proof["exposure_count"]==71 and proof["ref_count"]==142 and not proof["already_contained"])
        require(proof["canonical_uuid_digest"]==hashlib.sha256("\n".join(self.ids).encode()).hexdigest())
        for key in ("provider_ref_digest","ownership_hash","action_set_digest"): require(re.fullmatch(r"[0-9a-f]{64}",proof[key]) is not None)
        require(math.isfinite(proof['latency_ms']) and 0<proof['latency_ms']<5000 and re.fullmatch(r'[A-Za-z0-9_-]{1,100}',proof['request_id']) is not None,'GRAPH_SAFETY_EVIDENCE_INVALID')
        self.authorized=True
        return proof

    def observe(self):
        graph=self.authorize_request()
        started=now()
        try:
            start=len(self.evidence); turkish=0
            for c in self.selected:
                name=c["canonical"]["name"]
                b=self.request("GET","/api/v1/places?"+urllib.parse.urlencode({"search":name,"size":100,"sort":"name,asc"}))
                found=[p for p in b["content"] if p["id"]==c["canonical_place_id"]]
                require(len(found)==1 and found[0]["name"]==name and found[0]["category"]==c["canonical"]["category"],"SEARCH_COVERAGE")
                turkish+=bool(re.search("[çğıöşüÇĞİÖŞÜ]",name))
            require(turkish>0,"TURKISH_COVERAGE")
            for check in ("search","turkish_search","selected_place_coverage"): self.passed(check,start)
            start=len(self.evidence)
            bounds=self.request("GET","/api/v1/places/bounds?west=27.1878&south=37.3151&east=27.3478&north=37.4351&limit=200")
            require(len(bounds)<=200 and set(self.ids)<={p["id"] for p in bounds},"BOUNDS_COVERAGE")
            cells={}
            for p in bounds:
                require(37.3151<=p["latitude"]<=37.4351 and 27.1878<=p["longitude"]<=27.3478,"BOUNDS_GEOMETRY")
                cell=(math.floor((p["longitude"]+180)*1000),math.floor((p["latitude"]+90)*1000)); cells[cell]=cells.get(cell,0)+1
            require(max(cells.values(),default=0)<=25,"DENSE_MARKERS")
            require(len(self.request("GET","/api/v1/places/bounds?west=27.1878&south=37.3151&east=27.3478&north=37.4351&limit=1"))<=1,"BOUNDS_LIMIT")
            self.passed("bounds",start)
            start=len(self.evidence)
            nearby=self.request("GET","/api/v1/places/nearby?lat=37.3751&lon=27.2678&radiusMeters=6000&limit=200")
            require(len(nearby)<=200 and set(self.ids)<={p["place"]["id"] for p in nearby},"NEARBY_COVERAGE")
            distances=[p["distanceMeters"] for p in nearby]
            require(all(0<=d<=6001 for d in distances) and distances==sorted(distances),"NEARBY_GEOMETRY_ORDER")
            self.passed("nearby",start)
            start=len(self.evidence)
            for c in self.selected:
                pid=c["canonical_place_id"]
                b=self.request("GET","/api/v1/places/"+pid)
                require(b["id"]==pid and b["ratingCount"]==0 and b.get("averageScore") is None and not b["recentPublicReviews"] and not b["dimensionScores"],"ZERO_DETAIL")
                b=self.request("GET","/api/v2/places/"+pid)
                require(b["place"]["id"]==pid and b["visibleExperienceCount"]==b["communityContributionCount"]==0
                        and not b["primaryExperiences"] and not b["dimensions"] and not b["practicalSignals"]
                        and all(f["contributionCount"]==0 for f in b["feelings"]),"ZERO_AGGREGATE")
            self.passed("zero_experience_detail",start)
            # ALL non-mutating coverage/identity/graph checks finish before account creation.
            for check in ("rollback_safety","graph_safety"):
                self.checks[check]={"status":"PASS","observed_at":now(),"canonical_place_ids":self.ids,
                    "evidence":[{"operation":"READ_ONLY_GRAPH_INSPECTION","status":200,"validation":"VALID",
                                 "latency_ms":graph["latency_ms"],"request_id":graph["request_id"]}]}
            suffix=secrets.token_hex(6); self.password=secrets.token_urlsafe(30)
            self.registration_label="m55b_v3_"+suffix; self.cleanup="OWNER_INTERVENTION_REQUIRED"
            auth=self.request("POST","/api/v1/auth/register",{"email":self.registration_label+"@example.invalid","username":self.registration_label,
                "displayName":"M5.5B synthetic acceptance","password":self.password},expected=201,scan=False)
            require(auth['user']['username']==self.registration_label and auth['user']['email']==self.registration_label+'@example.invalid',
                    'SYNTHETIC_IDENTITY_MISMATCH')
            uid=auth['user']['id']; require(isinstance(uid,str) and str(uuid.UUID(uid))==uid,'SYNTHETIC_IDENTITY_MISMATCH')
            require(isinstance(auth['accessToken'],str) and 0<len(auth['accessToken'])<=8192,'SYNTHETIC_IDENTITY_MISMATCH')
            self.token=auth["accessToken"]; self.user_id=uid; del auth
            self.cleanup="PENDING"
            b=self.request("GET","/api/v1/me/policy-status",authenticated=True)
            if not b["accepted"]: require(self.request("POST","/api/v1/me/policy-acceptance",{"policyVersion":b["requiredVersion"]},authenticated=True)["accepted"])
            pid=next((c["canonical_place_id"] for c in self.selected if c["canonical"]["category"]=="CAFE"),self.ids[0])
            start=len(self.evidence)
            require(self.request("POST","/api/v1/me/saved-places/"+pid,authenticated=True)["place"]["id"]==pid)
            require(any(p["place"]["id"]==pid for p in self.request("GET","/api/v1/me/saved-places",authenticated=True)["content"]))
            self.request("DELETE","/api/v1/me/saved-places/"+pid,authenticated=True,expected=204)
            require(not self.request("GET","/api/v1/me/saved-places",authenticated=True)["content"])
            self.passed("want_to_go",start,[pid])
            start=len(self.evidence)
            b=self.request("POST","/api/v1/me/collections",{"title":"M55B synthetic acceptance","description":"Temporary canary test only","visibility":"PRIVATE","coverImage":""},authenticated=True,expected=201)
            cid=b["id"]
            b=self.request("POST","/api/v1/collections/"+cid+"/places/"+pid,authenticated=True)
            require(any(p["place"]["id"]==pid for p in b["places"]))
            self.request("DELETE","/api/v1/collections/"+cid+"/places/"+pid,authenticated=True,expected=204)
            require(not self.request("GET","/api/v1/collections/"+cid,authenticated=True)["places"])
            self.passed("collections",start,[pid])
            start=len(self.evidence)
            b=self.request("POST","/api/v2/experiences",{"clientMutationId":str(uuid.uuid4()),"placeId":pid,
                "visitDate":datetime.now(timezone.utc).date().isoformat(),"primaryExperienceCode":"KAHVE","overallFeelingCode":"GUZELDI",
                "titleSource":"CUSTOM","title":"M55B synthetic acceptance "+suffix,
                "story":"Temporary automated Phokarta acceptance, not a real customer review.","visibility":"PUBLIC","mediaIds":[]},authenticated=True,expected=201)
            eid=b["id"]; require(b["place"]["id"]==pid and b["classification"]=="NATIVE_V2")
            b=self.request("GET","/api/v2/experiences/"+eid,authenticated=True); require(b["id"]==eid and b["place"]["id"]==pid)
            require(any(v["id"]==eid and v["place"]["id"]==pid for v in self.request("GET","/api/v1/me/visits",authenticated=True)["content"]))
            self.passed("synthetic_v2_experience",start,[pid])
            start=len(self.evidence)
            b=self.request("GET","/api/v2/places/"+pid); require(b["visibleExperienceCount"]==b["communityContributionCount"]==1)
            require(any(e["id"]==eid and e["place"]["id"]==pid for e in self.request("GET","/api/v2/places/"+pid+"/experiences")["items"]))
            self.passed("detail_after_publication",start,[pid])
            start=len(self.evidence)
            b=self.request("GET","/api/v2/experiences/feed?"+urllib.parse.urlencode({"lens":"NEARBY","lat":37.3751,"lon":27.2678,"radiusMeters":6000,"size":20,"q":suffix}))
            require(any(e["id"]==eid for e in b["items"]) and all(e["id"] not in self.ids and {"classification","author","place"}<=set(e) for e in b["items"]))
            self.passed("experience_first_explore",start,[pid])
            self.passed("public_provider_id_isolation",0)
        except Exception:
            # Preserve the failing actual observation before a permitted synthetic cleanup
            # adds its own record. No hidden success/retry and no raw response/error message.
            if self.evidence: self.evidence[-1]['validation']='INVALID'
            raise
        finally:
            if self.token and self.password and self.user_id:
                try:
                    # Cleanup only this owned synthetic graph, never another acceptance mutation/retry.
                    self.request("DELETE","/api/v1/me",{"currentPassword":self.password},authenticated=True,expected=204,cleanup=True)
                    self.cleanup="COMPLETED"
                except Exception: self.cleanup="OWNER_INTERVENTION_REQUIRED"
            self.token=self.password=None
        require(self.cleanup=="COMPLETED","SYNTHETIC_CLEANUP_FAILED")
        b=self.request("GET","/api/v2/places/"+pid); require(b["visibleExperienceCount"]==b["communityContributionCount"]==0)
        require(self.request("GET","/api/v1/places/"+pid)["ratingCount"]==0)
        health=[]
        for path in ("/health/live","/health/ready"):
            start=len(self.evidence); require(self.request("GET",path)["status"]=="UP")
            e=self.evidence[start]; health.append({"path":path,"status":e["status"],"validation":e["validation"],"latency_ms":e["latency_ms"]})
        require(set(self.checks)==CHECKS)
        product={"version":SCHEMA,"run_id":self.plan["run_id"],"manifest_hash":self.plan["manifest_hash"],"started_at":started,
                 "completed_at":now(),"selected_canonical_ids":self.ids,"checks":self.checks,"health_checks":health}
        publish(self.directory/"PRODUCT.json",product)
        reopened=read(self.directory/"PRODUCT.json")
        require(reopened==product,"PRODUCT_READBACK_FAILED")
        validate_evidence(reopened,self.plan)
        raw=(self.directory/"PRODUCT.json").read_bytes()
        publish(self.directory/"PRODUCT_RECEIPT.json",{"run_id":self.plan["run_id"],"manifest_hash":self.plan["manifest_hash"],
                "artifact_sha256":hashlib.sha256(raw).hexdigest(),"artifact_bytes":len(raw)})

    def run(self):
        stop=threading.Event(); errors=[]
        def heartbeat():
            while not stop.wait(2):
                try: self.ready()
                except Exception: errors.append(True); return
        self.ready(); thread=threading.Thread(target=heartbeat,daemon=True); thread.start()
        try:
            deadline=time.monotonic()+65*60
            while not (self.directory/"PRODUCT_REQUEST.json").exists():
                require(time.monotonic()<deadline and not errors)
                self.current(); stop.wait(.1)
            require(not errors)
            self.observe()
        except Exception as error:
            category=str(error) if str(error) in FAILURE_CODES else 'HARD_FAILURE'
            safe={"version":"v3-product-failure-v1","run_id":self.plan["run_id"],"manifest_hash":self.plan["manifest_hash"],
                  "observed_at":now(),"reason":"HARD_FAILURE","failure_code":category,"cleanup":self.cleanup,"observations":self.evidence}
            if self.user_id: safe["synthetic_user_id"]=self.user_id
            elif self.registration_label: safe["synthetic_registration_label"]=self.registration_label
            publish(self.directory/"PRODUCT_FAILED.json",safe)
            raise ValueError("PRODUCT_WORKER_FAILED") from None
        finally: stop.set(); thread.join(timeout=3)


def validate_evidence(product,plan):
    require(set(product)=={"version","run_id","manifest_hash","started_at","completed_at","selected_canonical_ids","checks","health_checks"})
    require(product["version"]==SCHEMA and product["run_id"]==plan["run_id"] and product["manifest_hash"]==plan["manifest_hash"])
    require(product["selected_canonical_ids"]==sorted(plan["selected_canonical_ids"]) and set(product["checks"])==CHECKS)
    started=datetime.fromisoformat(product['started_at'].replace('Z','+00:00')); completed=datetime.fromisoformat(product['completed_at'].replace('Z','+00:00'))
    require(started<=completed<=datetime.now(timezone.utc))
    health=product['health_checks']
    require(isinstance(health,list) and len(health)==2 and {c['path'] for c in health}=={'/health/live','/health/ready'})
    for c in health:
        require(set(c)=={'path','status','validation','latency_ms'} and c['status']==200 and c['validation']=='VALID'
                and math.isfinite(c['latency_ms']) and 0<c['latency_ms']<5000)
    for name,c in product["checks"].items():
        require(set(c)=={"status","observed_at","canonical_place_ids","evidence"} and c["status"]=="PASS" and c["evidence"])
        require(c["canonical_place_ids"] and set(c["canonical_place_ids"])<=set(plan["selected_canonical_ids"]))
        require(len(c['canonical_place_ids'])==len(set(c['canonical_place_ids'])))
        require(started<=datetime.fromisoformat(c['observed_at'].replace('Z','+00:00'))<=completed)
        if name in {'search','turkish_search','nearby','bounds','selected_place_coverage','zero_experience_detail','public_provider_id_isolation','rollback_safety','graph_safety'}:
            require(sorted(c['canonical_place_ids'])==sorted(plan['selected_canonical_ids']))
        ops=set()
        for e in c["evidence"]:
            require(set(e)=={"operation","status","validation","latency_ms","request_id"})
            require(e['operation'] in {'GET','POST','DELETE','READ_ONLY_GRAPH_INSPECTION'})
            require(200<=e["status"]<300 and e["validation"]=="VALID" and math.isfinite(e["latency_ms"]) and 0<e["latency_ms"]<5000
                    and re.fullmatch(r"[A-Za-z0-9_-]{1,100}",e["request_id"]) is not None)
            ops.add(e["operation"])
        needed={"READ_ONLY_GRAPH_INSPECTION"} if name in ("rollback_safety","graph_safety") else {"POST","GET"} if name in ("want_to_go","collections","synthetic_v2_experience") else {"GET"}
        require(needed<=ops)


def main():
    p=argparse.ArgumentParser(description="Explicitly authorized manifest-bound future V3 product IPC worker")
    p.add_argument("--directory",type=Path,required=True); p.add_argument("--plan-sha256",required=True)
    p.add_argument("--manifest-path",type=Path,required=True); p.add_argument("--manifest-sha256",required=True)
    p.add_argument("--predecessor-path",type=Path,required=True); p.add_argument("--predecessor-sha256",required=True)
    p.add_argument("--authorization-reference",required=True)
    a=p.parse_args()
    try:
        require(os.name=="posix" and os.getuid()!=0,"NONROOT_PRIVATE_WORKER_REQUIRED")
        Worker(a.directory,a.plan_sha256,a.manifest_path,a.manifest_sha256,a.authorization_reference,a.predecessor_path,a.predecessor_sha256).run()
        return 0
    except Exception:
        print("PRODUCT_WORKER_FAILED:INVALID_AUTHORIZATION_OR_HARD_EVIDENCE",file=sys.stderr); return 1

if __name__=="__main__": raise SystemExit(main())
