import dataclasses
import json
import pathlib
import tempfile
import threading
import unittest
from consumer import Consumer, Ledger, Envelope, DenyOwnerAuthority, canonical, digest


class Crash(BaseException):
    pass


class FakeAuthority:
    """Test-only evidence. Not a production grant or owner approval resolver."""
    def __init__(self): self.allowed = True
    def verify_original(self, envelope, snapshot):
        if not self.allowed: raise PermissionError('needs_owner_authority')


class FakeApproval:
    def __init__(self, e):
        self.e, self.state, self.access, self.claim_id = e, 'authorized', 'active', None
        self.claims, self.results = [], []
        self.after_claim = lambda: None
    def current(self, e):
        return dict(actor_id=self.e.actor_id, key_id=self.e.key_id, owner_id=self.e.owner_id,
                    consumer_access=self.access, expires_epoch=self.e.expires_epoch,
                    snapshot_json=self.e.snapshot_json, state=self.state, claim_id=self.claim_id)
    def claim(self, e, client_id, operation_id):
        if self.claim_id: raise PermissionError('approval_used')
        self.claims.append((client_id, operation_id));self.state='claimed';self.claim_id='fixture_claim'
        self.after_claim()
        return self.claim_id
    def result(self, e, claim_id, operation_id, outcome, evidence):
        self.results.append((claim_id, operation_id, outcome, evidence));self.state=outcome


class FakeProvider:
    """In-memory synthetic provider. Has no network, mail or money implementation."""
    def __init__(self):
        self.submissions, self.operations = [], {}
        self.lost_response, self.changed, self.before_commit_crash = False, False, False
    def inspect_exact(self, snapshot):
        if self.changed: raise ValueError('live_parameters_changed')
    def submit_once(self, operation_id, snapshot):
        self.submissions.append(operation_id)
        if self.before_commit_crash: raise Crash()
        self.operations[operation_id] = 'fixture_provider_reference'
        if self.lost_response: raise TimeoutError('response_lost_after_commit')
    def reconcile(self, operation_id):
        if operation_id in self.operations:
            return 'succeeded', {'provider_reference': self.operations[operation_id], 'verified_state': 'fixture_committed'}
        return 'outcome_unknown', {'provider_reference': None, 'verified_state': 'fixture_no_conclusive_result'}


class ConsumerContractTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.path=pathlib.Path(self.temp.name)/'ledger.sqlite'
        vectors=json.loads((pathlib.Path(__file__).parents[1]/'app/src/test/resources/hr15-action-vectors.json').read_text())
        self.purchase=next(v['content'] for v in vectors if v['action']['kind']=='purchase')
        self.now=1000
        self.e=Envelope('fixture_approval','fixture_owner','fixture_ai','fixture_key',digest(self.purchase),
                        digest(self.purchase['action']),1900,canonical(self.purchase))
        self.authority=FakeAuthority();self.approval=FakeApproval(self.e);self.provider=FakeProvider()
        self.ledgers=[]
    def tearDown(self):
        for l in self.ledgers:l.db.close()
        self.temp.cleanup()
    def consumer(self, checkpoint=lambda _: None, authority=None):
        l=Ledger(self.path);self.ledgers.append(l)
        return Consumer(l,authority or self.authority,self.approval,self.provider,lambda:self.now,checkpoint)
    def test_deny_by_default_even_if_receipt_source_claims_owner_session(self):
        with self.assertRaises(PermissionError): self.consumer(authority=DenyOwnerAuthority()).execute(self.e)
        self.assertEqual([],self.provider.submissions);self.assertEqual([],self.approval.claims)
    def test_success_requires_provider_read_and_never_resubmits_on_reopen(self):
        self.assertEqual('succeeded',self.consumer().execute(self.e))
        self.assertEqual('succeeded',self.consumer().execute(self.e))
        self.assertEqual(1,len(self.provider.submissions));self.assertEqual(1,len(self.approval.claims))
    def test_lost_submit_response_reconciles_commit_without_retry(self):
        self.provider.lost_response=True
        self.assertEqual('succeeded',self.consumer().execute(self.e))
        self.assertEqual(1,len(self.provider.submissions))
    def test_crash_at_each_boundary_never_calls_submit_twice(self):
        for stage in ('reserved','claim_returned','claimed','submit_started','provider_returned','provider_reconciled'):
            with self.subTest(stage=stage):
                self.path=pathlib.Path(self.temp.name)/(stage+'.sqlite')
                self.approval=FakeApproval(self.e);self.provider=FakeProvider()
                def crash(at):
                    if at==stage:raise Crash()
                with self.assertRaises(Crash):self.consumer(crash).execute(self.e)
                self.consumer().execute(self.e)
                self.assertLessEqual(len(self.provider.submissions),1)
                if stage in ('reserved','claim_returned','claimed','submit_started'):
                    self.assertEqual([],self.provider.submissions)
    def test_expiry_before_claim_and_after_claim_prevent_first_submit(self):
        self.now=1900
        with self.assertRaises(PermissionError):self.consumer().execute(self.e)
        self.assertEqual([],self.approval.claims)
        self.path=pathlib.Path(self.temp.name)/'expired_after_claim.sqlite';self.now=1000
        self.approval.after_claim=lambda:setattr(self,'now',1900)
        with self.assertRaises(PermissionError):self.consumer().execute(self.e)
        self.assertEqual([],self.provider.submissions)
    def test_late_reconciliation_after_expiry_does_not_reopen_or_retry(self):
        def crash(stage):
            if stage=='provider_returned':raise Crash()
        with self.assertRaises(Crash):self.consumer(crash).execute(self.e)
        self.now=9999
        self.assertEqual('succeeded',self.consumer().execute(self.e))
        self.assertEqual(1,len(self.provider.submissions))
    def test_scope_changes_in_both_directions_are_not_tolerances(self):
        for amount in (99,101):
            changed=json.loads(self.e.snapshot_json);changed['action']['details']['final_total_minor']=amount
            modified=dataclasses.replace(self.e,snapshot_json=canonical(changed))
            with self.assertRaises(ValueError):self.consumer().execute(modified)
        self.assertEqual([],self.provider.submissions)
    def test_changed_owner_actor_or_original_key_blocks(self):
        for field in ('owner_id','actor_id','key_id'):
            with self.subTest(field=field):
                self.path=pathlib.Path(self.temp.name)/(field+'.sqlite')
                self.approval.e=dataclasses.replace(self.e,**{field:'other'})
                with self.assertRaises(PermissionError):self.consumer().execute(self.e)
        self.assertEqual([],self.approval.claims)
    def test_revocation_after_claim_blocks_submit_and_replacement_recovery(self):
        self.approval.after_claim=lambda:setattr(self.approval,'access','revoked_after_claim')
        with self.assertRaises(PermissionError):self.consumer().execute(self.e)
        self.assertEqual([],self.provider.submissions)
        with self.assertRaises(PermissionError):self.consumer().execute(self.e)
    def test_current_provider_parameters_change_after_claim_blocks_submit(self):
        self.approval.after_claim=lambda:setattr(self.provider,'changed',True)
        with self.assertRaises(ValueError):self.consumer().execute(self.e)
        self.assertEqual([],self.provider.submissions)
    def test_crash_inside_provider_stays_unknown_no_retry(self):
        self.provider.before_commit_crash=True
        with self.assertRaises(Crash):self.consumer().execute(self.e)
        self.assertEqual('outcome_unknown',self.consumer().execute(self.e))
        self.assertEqual(1,len(self.provider.submissions))
    def test_ledger_two_connection_race_has_one_fresh_reservation(self):
        barrier=threading.Barrier(2);results=[]
        def worker():
            ledger=Ledger(self.path)
            try:barrier.wait();results.append(ledger.reserve(self.e))
            finally:ledger.db.close()
        # Initialize schema before racing independent durable connections.
        first=Ledger(self.path);first.db.close()
        threads=[threading.Thread(target=worker) for _ in range(2)]
        for t in threads:t.start()
        for t in threads:t.join()
        self.assertEqual([False,True],sorted(results))
    def test_no_reference_cannot_be_reported_as_success(self):
        self.provider.reconcile=lambda _:('succeeded',{'provider_reference':None})
        with self.assertRaises(ValueError):self.consumer().execute(self.e)
        self.assertEqual([],self.approval.results)
    def test_same_approval_id_cannot_rebind_to_new_action(self):
        self.consumer().execute(self.e)
        modified=dataclasses.replace(self.e,expires_epoch=2000)
        with self.assertRaises(ValueError):self.consumer().execute(modified)
        self.assertEqual(1,len(self.provider.submissions))
    def test_terminal_success_cannot_be_overwritten(self):
        self.consumer().execute(self.e)
        self.provider.reconcile=lambda _:('failed_no_effect',{'provider_reference':None})
        with self.assertRaises(ValueError):self.consumer().execute(self.e)


if __name__=='__main__':unittest.main(verbosity=2)
