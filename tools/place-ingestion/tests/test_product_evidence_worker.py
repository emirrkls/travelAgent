"""Isolated future-run fixtures only. Never contacts beta or writes an operational seal."""
import copy
import hashlib
import http.server
import importlib.util
import json
import os
import subprocess
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from test_canary_manifest import _envelope
from phokarta_place_ingestion.canary_manifest import _sha256_json, _candidate_payload_hash

sys.path.insert(0,str(Path(__file__).parents[1]/'operations'))
import product_evidence_worker as worker
import persistent_telemetry_worker as shared
import test_persistent_telemetry_worker as telemetry_fixtures


class FakeProductAPI:
    def __init__(self,selected,fault=None):
        self.selected=selected; self.fault=fault; self.calls=[]; self.saved=False; self.in_collection=False; self.experience=False
        self.pid=selected[0]['canonical_place_id']; self.eid='99999999-1111-4111-8111-999999999999'
    def request(self,method,path,payload=None,token=None):
        from urllib.parse import urlparse,parse_qs
        p=urlparse(path); q=parse_qs(p.query); path=p.path; self.calls.append((method,path))
        body=None; status=200
        if path=='/api/v1/places':
            match=[c for c in self.selected if c['canonical']['name']==q['search'][0]]
            body={'content':[{'id':c['canonical_place_id'],'name':c['canonical']['name'],'category':'CAFE'} for c in match]}
            if self.fault in {'search','turkish_search','selected_place_coverage'}: body['content']=[]
            if self.fault=='public_provider_id_isolation': body['externalId']='forbidden'
        elif path.endswith('/bounds'):
            body=[{'id':c['canonical_place_id'],'latitude':c['canonical']['latitude'],'longitude':c['canonical']['longitude']} for c in self.selected][:int(q['limit'][0])]
            if self.fault=='bounds': body[0]['latitude']=0
        elif path.endswith('/nearby'):
            body=[{'place':{'id':c['canonical_place_id']},'distanceMeters':i} for i,c in enumerate(self.selected)]
            if self.fault=='nearby': body[0]['distanceMeters']=6002
        elif path.startswith('/api/v1/places/'):
            body={'id':path.rsplit('/',1)[1],'ratingCount':0,'averageScore':None,'recentPublicReviews':[],'dimensionScores':[]}
            if self.fault=='zero_experience_detail': body['ratingCount']=1
        elif path.startswith('/api/v2/places/'):
            if path.endswith('/experiences'): body={'items':[{'id':self.eid,'place':{'id':self.pid}}]}
            else:
                body={'place':{'id':path.rsplit('/',1)[1]},'visibleExperienceCount':int(self.experience),'communityContributionCount':int(self.experience),
                      'primaryExperiences':[],'dimensions':[],'practicalSignals':[],'feelings':[]}
                if self.fault=='detail_after_publication' and self.experience: body['visibleExperienceCount']=0
        elif path=='/api/v1/auth/register':
            status=201; body={'accessToken':'sensitive-test-token','user':{'id':'99999999-2222-4222-8222-999999999999','username':payload['username'],'email':payload['email']}}
            if self.fault=='synthetic_identity': body['user']['username']='another-legitimate-user'
        elif path=='/api/v1/me/policy-status': body={'accepted':True}
        elif path.startswith('/api/v1/me/saved-places'):
            if method=='POST': self.saved=True; body={'place':{'id':self.pid}}
            elif method=='DELETE': self.saved=False; status=204
            else: body={'content':[{'place':{'id':self.pid}}] if self.saved else []}
            if self.fault=='want_to_go' and method=='POST': body['place']['id']='wrong'
        elif path=='/api/v1/me/collections': status=201; body={'id':'99999999-3333-4333-8333-999999999999'}
        elif path.startswith('/api/v1/collections/'):
            if method=='POST': self.in_collection=True
            if method=='DELETE': self.in_collection=False; status=204
            body=None if status==204 else {'places':[{'place':{'id':self.pid}}] if self.in_collection else []}
            if self.fault=='collections' and method=='POST': body['places']=[]
        elif path=='/api/v2/experiences' and method=='POST':
            self.experience=True; status=201; body={'id':self.eid,'place':{'id':self.pid},'classification':'NATIVE_V2'}
            if self.fault=='synthetic_v2_experience': body['classification']='INVALID'
        elif path=='/api/v2/experiences/feed':
            body={'items':[{'id':self.eid,'place':{'id':self.pid},'author':{'id':'synthetic'},'classification':'NATIVE_V2'}]}
            if self.fault=='experience_first_explore': body={'items':[{'id':self.pid}]}
        elif path.startswith('/api/v2/experiences/'): body={'id':self.eid,'place':{'id':self.pid}}
        elif path=='/api/v1/me/visits': body={'content':[{'id':self.eid,'place':{'id':self.pid}}]}
        elif path=='/api/v1/me' and method=='DELETE': self.experience=False; status=204
        elif path in ('/health/live','/health/ready'): body={'status':'UP'}
        else: raise AssertionError((method,path))
        return {'status':status,'body':body,'latency_ms':10.0,'request_id':'isolated-fixture-request'}


class ProductEvidenceWorkerTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.directory=Path(self.temp.name)
        if os.name=='posix': self.directory.chmod(0o700)
        self.old=_envelope(71); self.old['manifest']['run_id']=shared.OLD_RUN
        for i,c in enumerate(self.old['manifest']['candidates']):
            lat=37.370+(i//10)*.001; lon=27.260+(i%10)*.001
            c['canonical'].update(latitude=lat,longitude=lon); c['candidate_hash']=_candidate_payload_hash(c)
            for s in self.old['manifest']['source_records']:
                if s['source_record_id'] in c['source_record_ids']: s.update(latitude=lat,longitude=lon)
        self.old['manifest_hash']=_sha256_json(self.old['manifest'])
        self.new=copy.deepcopy(self.old)
        self.new['manifest'].update(run_id='11111111-2222-4333-8444-555555555555',method_version=worker.METHOD,
            authorization_reference='isolated-v3-authority',reauthorizes_run_id=shared.OLD_RUN,predecessor_manifest_hash=self.old['manifest_hash'],
            canonical_identity_method_version='didim-autonomous-validation-v2',performance_policy='ADVISORY_ONLY')
        self.new['manifest_hash']=_sha256_json(self.new['manifest'])
        self.selected=sorted(self.new['manifest']['candidates'],key=lambda c:c['selection_rank'])
        self.ids=sorted(c['canonical_place_id'] for c in self.selected)
        self.plan={'version':'v3-private-operations-plan-v2','validation_method':worker.METHOD,'run_id':self.new['manifest']['run_id'],
            'manifest_hash':self.new['manifest_hash'],'authorization_reference':'isolated-v3-authority','target_policy':telemetry_fixtures.PersistentTelemetryWorkerTests().policy(),
            'selected_canonical_ids':self.ids,'product_checks':sorted(worker.CHECKS)}
        shared.publish(self.directory/'V3_OPERATIONS_PLAN.json',self.plan); self.plan_hash=self.digest('V3_OPERATIONS_PLAN.json')
        self.target=telemetry_fixtures.PersistentTelemetryWorkerTests().target()
        a={'version':'v3-execution-target-v1','run_id':self.plan['run_id'],'manifest_hash':self.plan['manifest_hash'],
           'plan_sha256':self.plan_hash,'observed_at':shared.now(),'source_sha':'c'*40,'image_ref':'phokarta-backend:'+'c'*40,'target':self.target,
           'health_checks':[{'path':p,'status':200,'validation':'VALID','latency_ms':10} for p in ('/actuator/health/liveness','/actuator/health/readiness')]}
        shared.publish(self.directory/'EXECUTION_TARGET.json',a)
        shared.publish(self.directory/'EXECUTION_TARGET_RECEIPT.json',{'run_id':self.plan['run_id'],'manifest_hash':self.plan['manifest_hash'],
            'plan_sha256':self.plan_hash,'artifact_sha256':self.digest('EXECUTION_TARGET.json'),'artifact_bytes':(self.directory/'EXECUTION_TARGET.json').stat().st_size})
        shared.publish(self.directory/'CURRENT_TARGET.json',{'observed_at':shared.now(),'target':self.target})
        shared.publish(self.directory/'fixture-manifest.json',self.new); shared.publish(self.directory/'fixture-predecessor.json',self.old)
        shared.publish(self.directory/'POST.json',{'run_id':self.plan['run_id'],'manifest_hash':self.plan['manifest_hash'],'role':'POST','outcome':'COMPLETE','target':self.target})
        shared.publish(self.directory/'POST_RECEIPT.json',{'version':'persistent-telemetry-receipt-v1','run_id':self.plan['run_id'],
            'manifest_hash':self.plan['manifest_hash'],'role':'POST','artifact_sha256':self.digest('POST.json'),'artifact_bytes':(self.directory/'POST.json').stat().st_size})
        shared.publish(self.directory/'PRODUCT_GRAPH_SAFETY.json',{'version':'v3-containment-inspection-v1','run_id':self.plan['run_id'],
            'manifest_hash':self.plan['manifest_hash'],'validation_method':worker.METHOD,'exposure_count':71,'ref_count':142,'already_contained':False,
            'canonical_uuid_digest':hashlib.sha256('\n'.join(self.ids).encode()).hexdigest(),'provider_ref_digest':'a'*64,'ownership_hash':'b'*64,
            'action_set_digest':'c'*64,'request_id':'safe-readonly-graph-inspection','latency_ms':1})
        self.request={'version':'v3-product-request-v1','run_id':self.plan['run_id'],'manifest_hash':self.plan['manifest_hash'],
            'requested_at':shared.now(),'authorization_reference':'isolated-v3-authority','validation_method':worker.METHOD,'evidence_schema_version':worker.SCHEMA}
        shared.publish(self.directory/'PRODUCT_REQUEST.json',self.request)
    def digest(self,name): return hashlib.sha256((self.directory/name).read_bytes()).hexdigest()
    def producer(self,api=None,authorization='isolated-v3-authority',**overrides):
        args={'directory':self.directory,'plan_hash':self.plan_hash,'manifest_path':self.directory/'fixture-manifest.json',
              'manifest_sha':self.digest('fixture-manifest.json'),'authorization':authorization,'predecessor_path':self.directory/'fixture-predecessor.json',
              'predecessor_sha':self.digest('fixture-predecessor.json'),'transport':api or FakeProductAPI(self.selected)}
        args.update(overrides); return worker.Worker(**args)
    def replace_fixture(self,name,value):
        (self.directory/name).unlink(); shared.publish(self.directory/name,value)
    def test_actual_product_checks_receipt_readback_and_secret_exclusion(self):
        api=FakeProductAPI(self.selected); p=self.producer(api); p.run()
        product=shared.read(self.directory/'PRODUCT.json'); receipt=shared.read(self.directory/'PRODUCT_RECEIPT.json')
        worker.validate_evidence(product,self.plan)
        self.assertEqual(set(product['checks']),worker.CHECKS)
        self.assertEqual(receipt['artifact_sha256'],self.digest('PRODUCT.json'))
        self.assertEqual(receipt['artifact_bytes'],(self.directory/'PRODUCT.json').stat().st_size)
        self.assertEqual(p.cleanup,'COMPLETED')
        for file in self.directory.glob('PRODUCT*.json'):
            text=file.read_text(); self.assertNotIn('sensitive-test-token',text); self.assertNotIn('currentPassword',text); self.assertNotIn('raw_body',text)
        self.assertEqual(len([c for c in api.calls if c[0]=='POST' and c[1]=='/api/v1/auth/register']),1)
    def test_ready_binds_run_hash_method_schema_after_validation(self):
        p=self.producer(); p.ready(); ready=shared.read(self.directory/'PRODUCT_WORKER_READY.json')
        for k in ('run_id','manifest_hash'): self.assertEqual(ready[k],self.plan[k])
        self.assertEqual(ready['validation_method'],worker.METHOD); self.assertEqual(ready['evidence_schema_version'],worker.SCHEMA)
        self.assertFalse((self.directory/'PRODUCT_RECEIPT.json').exists())
    def test_invalid_authorization_predecessor_and_manifest_never_ready(self):
        for args in ({'authorization':'wrong'},{'manifest_sha':'0'*64},{'predecessor_sha':'0'*64},{'plan_hash':'0'*64}):
            with self.subTest(args=args),self.assertRaises(ValueError): self.producer(**args)
        self.assertFalse((self.directory/'PRODUCT_WORKER_READY.json').exists())
    def test_unbound_mutation_no_standalone_production_mode(self):
        api=FakeProductAPI(self.selected); p=self.producer(api)
        with self.assertRaisesRegex(ValueError,'NOT_AUTHORIZED'): p.request('POST','/api/v1/auth/register',scan=False)
        self.assertEqual(api.calls,[])
        (self.directory/'PRODUCT_REQUEST.json').unlink()
        with self.assertRaises(ValueError): p.observe()
        self.assertEqual(api.calls,[])
    def test_private_cli_fails_closed_without_any_authorization(self):
        result=subprocess.run([sys.executable,str(Path(worker.__file__))],capture_output=True,timeout=5)
        self.assertEqual(result.returncode,2); self.assertEqual(result.stdout,b'')
        self.assertIn(b'--authorization-reference',result.stderr); self.assertIn(b'--predecessor-sha256',result.stderr)
    def test_wrong_request_run_hash_method_authority_schema_prevents_any_mutation(self):
        for field in ('run_id','manifest_hash','validation_method','authorization_reference','evidence_schema_version'):
            q=copy.deepcopy(self.request); q[field]='wrong'; self.replace_fixture('PRODUCT_REQUEST.json',q)
            api=FakeProductAPI(self.selected); p=self.producer(api)
            with self.subTest(field=field),self.assertRaises(ValueError): p.observe()
            self.assertEqual(api.calls,[])
    def test_invalid_or_slow_graph_proof_stops_before_product_mutation(self):
        good=shared.read(self.directory/'PRODUCT_GRAPH_SAFETY.json')
        for field,value in (('latency_ms',5000),('canonical_uuid_digest','0'*64),('ref_count',141),('already_contained',True)):
            bad=dict(good); bad[field]=value; self.replace_fixture('PRODUCT_GRAPH_SAFETY.json',bad)
            api=FakeProductAPI(self.selected); p=self.producer(api)
            with self.subTest(field=field),self.assertRaises(ValueError): p.observe()
            self.assertEqual(api.calls,[]); self.assertFalse((self.directory/'PRODUCT_RECEIPT.json').exists())
    def test_each_product_failure_has_no_false_pass_and_preserves_failure(self):
        for check in ('search','turkish_search','selected_place_coverage','bounds','nearby','zero_experience_detail','public_provider_id_isolation',
                      'want_to_go','collections','synthetic_v2_experience','detail_after_publication','experience_first_explore'):
            api=FakeProductAPI(self.selected,check); p=self.producer(api)
            with self.subTest(check=check),self.assertRaises(ValueError): p.run()
            self.assertFalse((self.directory/'PRODUCT_RECEIPT.json').exists())
            failed=shared.read(self.directory/'PRODUCT_FAILED.json'); self.assertEqual(failed['reason'],'HARD_FAILURE')
            self.assertNotIn('sensitive-test-token',json.dumps(failed))
            (self.directory/'PRODUCT_FAILED.json').unlink() # independent fixture observation, never live retry
    def test_readback_tamper_never_finalizes_receipt(self):
        real_read=worker.read
        def tamper(path,*args):
            b=real_read(path,*args)
            if path.name=='PRODUCT.json': b['manifest_hash']='0'*64
            return b
        with patch.object(worker,'read',side_effect=tamper),self.assertRaises(ValueError): self.producer().run()
        self.assertFalse((self.directory/'PRODUCT_RECEIPT.json').exists())
    def test_unknown_exception_message_cannot_enter_sanitized_evidence(self):
        api=FakeProductAPI(self.selected); api.request=lambda *args,**kwargs: (_ for _ in ()).throw(ValueError('TEST_SECRET'))
        with self.assertRaises(ValueError): self.producer(api).run()
        failed=shared.read(self.directory/'PRODUCT_FAILED.json')
        self.assertEqual(failed['failure_code'],'HARD_FAILURE'); self.assertNotIn('TEST_SECRET',json.dumps(failed))
    def test_mismatched_synthetic_identity_never_mutates_or_deletes_other_user_graph(self):
        api=FakeProductAPI(self.selected,'synthetic_identity'); p=self.producer(api)
        with self.assertRaises(ValueError): p.run()
        failed=shared.read(self.directory/'PRODUCT_FAILED.json')
        self.assertEqual(failed['cleanup'],'OWNER_INTERVENTION_REQUIRED')
        self.assertEqual(failed['failure_code'],'SYNTHETIC_IDENTITY_MISMATCH')
        self.assertNotIn(('DELETE','/api/v1/me'),api.calls)
        self.assertNotIn(('POST','/api/v2/experiences'),api.calls)
        self.assertNotIn('synthetic_user_id',failed)
    def test_schema_rejects_missing_checks_wrong_run_sensitive_fields_bad_deadline(self):
        self.producer().run(); good=shared.read(self.directory/'PRODUCT.json')
        mutations=[lambda p:p.update(raw_body='secret'),lambda p:p.update(run_id=shared.OLD_RUN),
                   lambda p:p['checks'].pop('search'),lambda p:p['checks']['search']['evidence'][0].update(latency_ms=5000),
                   lambda p:p.update(health_checks=[]),lambda p:p['checks']['selected_place_coverage'].update(canonical_place_ids=self.ids[:1])]
        for mutate in mutations:
            p=copy.deepcopy(good); mutate(p)
            with self.subTest(mutate=mutate),self.assertRaises(ValueError): worker.validate_evidence(p,self.plan)
    def test_process_network_or_health_change_blocks_requests(self):
        p=self.producer(); c=shared.read(self.directory/'CURRENT_TARGET.json'); c['target']['java_identity']='7:99999'
        self.replace_fixture('CURRENT_TARGET.json',c)
        with self.assertRaises(ValueError): p.ready()
    def test_real_transport_five_second_complete_body_deadline_no_retry(self):
        count=[]
        class SlowBody(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_GET(self):
                count.append(True); self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.flush()
                time.sleep(5.4)
                try: self.wfile.write(b'{"status":"UP"}')
                except OSError: pass
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),SlowBody); server.daemon_threads=True
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            began=time.monotonic()
            with self.assertRaises(worker.RequestFailure) as failed: worker.Transport('http://127.0.0.1:'+str(server.server_port)).request('GET','/')
            self.assertGreaterEqual(time.monotonic()-began,4.8); self.assertLess(time.monotonic()-began,6.0)
            self.assertEqual(str(failed.exception),'PRODUCT_TIMEOUT'); self.assertEqual(len(count),1)
            self.assertEqual(failed.exception.observation['validation'],'PRODUCT_TIMEOUT')
        finally: server.shutdown(); server.server_close()


if __name__=='__main__': unittest.main()
