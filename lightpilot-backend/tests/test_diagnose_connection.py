import json
import subprocess

import pytest

from scripts.diagnose_connection import probe


@pytest.mark.parametrize('status,body,passed', [
    (401, {'error': {'code': 'invalid_api_key', 'message': 'private-provider-detail'}}, False),
    (200, {'choices': [{'message': {'content': 'OK'}}]}, True),
    (200, {'output': {'choices': [{'message': {'content': [{'text': 'OK'}]}}]}}, True),
    (200, {}, False),
    (200, [], False),
])
def test_raw_curl_diagnostics_keep_secrets_out_of_arguments_and_results(monkeypatch, status, body, passed):
    def run(command, **kwargs):
        assert 'test-secret' not in ' '.join(command)
        assert 'Authorization: Bearer test-secret' in kwargs['input']
        assert '--insecure' not in command and '--location' not in command
        assert '--noproxy' in command
        return subprocess.CompletedProcess(command, 0, json.dumps(body) +
                                           f'\n__DIAGNOSTIC__{status} 1.1 0.1', '')

    monkeypatch.setattr('scripts.diagnose_connection.subprocess.run', run)
    result = probe('test', 'https://workspace.example.invalid/chat/completions', 'test-secret',
                   {'model': 'test'}, '--http1.1', True, '/usr/bin/curl')
    assert result['passed'] is passed
    assert result['http_status'] == status
    assert 'test-secret' not in json.dumps(result)
    assert 'private-provider-detail' not in json.dumps(result)


def test_transport_failure_does_not_claim_authentication_failure(monkeypatch):
    monkeypatch.setattr('scripts.diagnose_connection.subprocess.run', lambda *a, **k:
                        subprocess.CompletedProcess(a[0], 56, '\n__DIAGNOSTIC__000 0 1.0', 'private detail'))
    result = probe('test', 'https://workspace.example.invalid', 'test-secret', {}, '--http2', False, 'curl')
    assert result['error'] == 'receive_failed'
    assert result['passed'] is False
    assert 'provider_code' not in result
