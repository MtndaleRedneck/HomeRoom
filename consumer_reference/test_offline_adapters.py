import copy
import hashlib
import json
import pathlib
import tempfile
import unittest
from consumer import Consumer, DenyOwnerAuthority, Envelope, Ledger, canonical, digest
from offline_adapters import MemoryProvider, OfflineEmailAdapter, OfflinePurchaseAdapter
from test_consumer import FakeAuthority, FakeApproval


class AdapterContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.vectors = json.loads((pathlib.Path(__file__).parents[1]/'app/src/test/resources/hr15-action-vectors.json').read_text())
        self.ledger = Ledger(pathlib.Path(self.temp.name)/'ledger.sqlite')
        self.provider = MemoryProvider()
        self.audiences = []
        self.wallet_checks = []

    def tearDown(self):
        self.ledger.db.close()
        self.temp.cleanup()

    def setup_action(self, kind, modify=lambda _: None):
        snapshot = copy.deepcopy(next(v['content'] for v in self.vectors if v['action']['kind']==kind))
        modify(snapshot['action'])
        snapshot['action_sha256'] = digest(snapshot['action'])
        self.snapshot = snapshot
        self.current = copy.deepcopy(snapshot['action'])
        self.envelope = Envelope('synthetic_approval', 'synthetic_owner', 'fixture_ai', 'fixture_key',
                                 digest(snapshot), snapshot['action_sha256'], 1900, canonical(snapshot))
        self.approvals = FakeApproval(self.envelope)
        self.authority = FakeAuthority()
        if kind=='email':
            self.connected = copy.deepcopy(self.current['details']['sending_identity'])
            self.bytes = {'attachment': b'exact synthetic attachment'}
            def audience(file_id, contents, recipients):
                self.audiences.append((file_id, contents, recipients))
                if self.deny_attachment: raise PermissionError('source_owner_restricts_audience')
            self.deny_attachment = False
            self.adapter = OfflineEmailAdapter(snapshot['action'], lambda:self.current,
                                               self.provider, lambda:self.connected,
                                               lambda key:self.bytes.get(key), audience)
        else:
            self.payment = copy.deepcopy(self.current['details']['payment_identity'])
            self.wallet_allowed = True
            def wallet(identity, amount, currency):
                self.wallet_checks.append((identity, amount, currency))
                if not self.wallet_allowed: raise PermissionError('independent_wallet_approval_missing')
            self.adapter = OfflinePurchaseAdapter(snapshot['action'], lambda:self.current,
                                                  self.provider, lambda:self.payment, wallet)
        return Consumer(self.ledger,self.authority,self.approvals,self.adapter,lambda:1000)

    def attachment(self, action):
        d=action['details'];d['cc']=[{'name':'CC','address':'cc@example.invalid'}]
        d['bcc']=[{'name':'BCC','address':'bcc@example.invalid'}]
        contents=b'exact synthetic attachment'
        d['attachments']=[{'file_id':'attachment','name':'fixture.txt','byte_size':len(contents),
                           'sha256':hashlib.sha256(contents).hexdigest()}]

    def assert_no_claim_or_submit(self):
        self.assertEqual([],self.approvals.claims)
        self.assertEqual([],self.provider.submissions)

    def test_email_full_account_audience_words_and_attachment_pass_unchanged(self):
        c=self.setup_action('email',self.attachment)
        self.assertEqual('succeeded',c.execute(self.envelope))
        self.assertEqual(self.snapshot['action'],self.provider.submissions[0][1])
        self.assertEqual(b'exact synthetic attachment',self.audiences[0][1])
        self.assertEqual(self.snapshot['action']['details']['bcc'],self.audiences[0][2]['bcc'])

    def test_changed_account_blocks_before_claim(self):
        c=self.setup_action('email');self.connected['account_id']='different'
        with self.assertRaises(PermissionError):c.execute(self.envelope)
        self.assert_no_claim_or_submit()

    def test_missing_or_changed_attachment_never_drops_it(self):
        for contents in (None,b'wrong synthetic attachment'):
            with self.subTest(contents=contents):
                c=self.setup_action('email',self.attachment);self.bytes['attachment']=contents
                with self.assertRaises(ValueError):c.execute(self.envelope)
                self.assert_no_claim_or_submit()

    def test_attachment_hash_is_not_audience_permission(self):
        c=self.setup_action('email',self.attachment);self.deny_attachment=True
        with self.assertRaises(PermissionError):c.execute(self.envelope)
        self.assert_no_claim_or_submit()

    def test_changed_bcc_after_claim_blocks_submit(self):
        c=self.setup_action('email',self.attachment)
        self.approvals.after_claim=lambda:self.current['details']['bcc'].append({'name':'Extra','address':'extra@example.invalid'})
        with self.assertRaises(ValueError):c.execute(self.envelope)
        self.assertEqual([],self.provider.submissions)

    def test_identical_subject_and_recipients_do_not_identify_operation(self):
        c=self.setup_action('email');self.provider.lose_response=True
        self.provider.operations['other_intentional_message']='synthetic:unrelated'
        self.assertEqual('succeeded',c.execute(self.envelope))
        reference=self.approvals.results[-1][3]['provider_reference']
        self.assertNotEqual('synthetic:unrelated',reference)
        self.assertEqual(1,len(self.provider.submissions))

    def test_no_exact_operation_match_remains_unknown(self):
        self.setup_action('email')
        self.assertEqual('outcome_unknown',self.adapter.reconcile('absent')[0])

    def test_purchase_has_separate_wallet_check_and_exact_components(self):
        c=self.setup_action('purchase')
        self.assertEqual('succeeded',c.execute(self.envelope))
        self.assertTrue(self.wallet_checks)
        self.assertEqual(100,self.wallet_checks[-1][1])
        self.assertEqual('USD',self.wallet_checks[-1][2])

    def test_home_room_permission_cannot_replace_wallet_approval(self):
        c=self.setup_action('purchase');self.wallet_allowed=False
        with self.assertRaises(PermissionError):c.execute(self.envelope)
        self.assert_no_claim_or_submit()

    def test_saved_method_label_is_not_account_mapping(self):
        c=self.setup_action('purchase');self.payment['method_id']='different'
        with self.assertRaises(PermissionError):c.execute(self.envelope)
        self.assert_no_claim_or_submit()

    def test_lower_and_higher_current_totals_both_block(self):
        for amount in (99,101):
            c=self.setup_action('purchase');self.current['details']['final_total_minor']=amount
            with self.assertRaises(ValueError):c.execute(self.envelope)
            self.assert_no_claim_or_submit()

    def test_changed_undo_terms_after_claim_blocks(self):
        c=self.setup_action('purchase')
        self.approvals.after_claim=lambda:self.current['details']['undo_terms'].update(terms_text='Different cost to undo')
        with self.assertRaises(ValueError):c.execute(self.envelope)
        self.assertEqual([],self.provider.submissions)

    def test_noninteger_money_and_inconsistent_components_fail(self):
        for value in (True,100.0,-1):
            c=self.setup_action('purchase',lambda a:a['details'].update(final_total_minor=value))
            with self.assertRaises(ValueError):c.execute(self.envelope)
            self.assert_no_claim_or_submit()

    def test_deny_default_authority_even_with_valid_mock_adapter(self):
        c=self.setup_action('purchase');c.authority=DenyOwnerAuthority()
        with self.assertRaises(PermissionError):c.execute(self.envelope)
        self.assert_no_claim_or_submit()

    def test_mock_adapter_rejects_non_memory_provider(self):
        self.setup_action('purchase')
        with self.assertRaises(TypeError):OfflinePurchaseAdapter(self.current,lambda:self.current,object(),lambda:self.payment,lambda *_:None)


if __name__=='__main__':unittest.main(verbosity=2)
