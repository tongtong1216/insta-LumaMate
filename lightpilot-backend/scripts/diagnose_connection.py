"""Compare curl protocols without FastAPI or the OpenAI SDK; never print credentials."""

import argparse
import getpass
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import urlsplit

from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parent.parent


def curl_quote(value: str) -> str:
    # Config travels through stdin, never process arguments or a temporary secret file.
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r') + '"'


def probe(name: str, url: str, key: str, payload: dict | None, protocol: str,
          direct: bool, curl: str) -> dict:
    config = [
        'url = ' + curl_quote(url),
        'header = ' + curl_quote('Authorization: Bearer ' + key),
        'header = "Content-Type: application/json"',
    ]
    if payload is not None:
        config.append('data = ' + curl_quote(json.dumps(payload)))
    command = [curl, '--silent', '--show-error', '--connect-timeout', '10', '--max-time', '25',
               protocol, '--config', '-', '--write-out', '\n__DIAGNOSTIC__%{http_code} %{http_version} %{time_total}']
    if direct:
        command.extend(['--noproxy', '*'])
    try:
        result = subprocess.run(command, input='\n'.join(config) + '\n', capture_output=True,
                                text=True, timeout=30)
    except subprocess.TimeoutExpired:
        return {'test': name, 'passed': False, 'error': 'curl_process_timeout'}
    body, _, trailer = result.stdout.rpartition('\n__DIAGNOSTIC__')
    fields = trailer.split()
    status = int(fields[0]) if fields and fields[0].isdigit() else 0
    output = {'test': name, 'http_status': status,
              'http_version': fields[1] if len(fields) > 1 else None,
              'elapsed_seconds': float(fields[2]) if len(fields) > 2 else None,
              'curl_exit_code': result.returncode, 'passed': False}
    if result.returncode:
        output['error'] = {5: 'proxy_resolution_failed', 6: 'dns_failed', 7: 'connect_failed',
                           28: 'timeout', 35: 'tls_failed', 52: 'empty_reply', 56: 'receive_failed',
                           60: 'certificate_failed'}.get(result.returncode, 'curl_failed')
        return output
    try:
        data = json.loads(body)
    except json.JSONDecodeError:
        output['error'] = 'non_json_response'
        return output
    if not isinstance(data, dict):
        output['error'] = 'unexpected_response_shape'
        return output
    error = data.get('error', data)
    if isinstance(error, dict):
        code = error.get('code')
        # Only bounded error identifiers, never free-form messages or model output.
        if isinstance(code, (str, int)) and re.fullmatch(r'[A-Za-z0-9_.-]{1,80}', str(code)):
            output['provider_code'] = code
    if 200 <= status < 300:
        if payload is None:
            output['passed'] = isinstance(data.get('data'), list)
            output['model_count'] = len(data['data']) if output['passed'] else None
        else:
            choices = data.get('choices')
            native = data.get('output')
            output['passed'] = bool(choices or isinstance(native, dict) and
                                    (native.get('choices') or native.get('text')))
        if not output['passed']:
            output['error'] = 'unexpected_response_shape'
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prompt-key', action='store_true',
                        help='Paste the original console key invisibly; do not change .env')
    parser.add_argument('--direct', action='store_true', help='Bypass curl proxy environment settings')
    args = parser.parse_args()
    values = dotenv_values(ROOT / '.env')
    key = os.environ.get('BAILIAN_API_KEY', values.get('BAILIAN_API_KEY', '')) or ''
    project_key = key
    base = (os.environ.get('BAILIAN_BASE_URL', values.get('BAILIAN_BASE_URL', '')) or '').strip().rstrip('/')
    model = os.environ.get('BAILIAN_MODEL_ID', values.get('BAILIAN_MODEL_ID', 'qwen3.8-flash'))
    if args.prompt_key:
        key = getpass.getpass('请粘贴控制台原始 API Key（输入不可见，不保存）：').strip()
    if not key or any(c.isspace() for c in key):
        print('API Key 为空或包含空白字符。')
        return 2
    url = urlsplit(base)
    if (url.scheme != 'https' or not url.hostname or url.username or url.password
            or url.query or url.fragment or url.path != '/compatible-mode/v1'):
        print('请配置有效的 HTTPS OpenAI-compatible 根地址，路径为 /compatible-mode/v1。')
        return 2
    curl = shutil.which('curl')
    if not curl:
        print('找不到 curl，请先安装 curl。')
        return 2
    chat = {'model': model, 'messages': [{'role': 'user', 'content': 'Reply with OK.'}],
            'max_tokens': 32, 'enable_thinking': False}
    native = {'model': model, 'input': {'messages': [
        {'role': 'user', 'content': [{'text': 'Reply with OK.'}]}]},
        'parameters': {'max_tokens': 32, 'enable_thinking': False, 'result_format': 'message'}}
    origin = f'{url.scheme}://{url.netloc}'
    cases = [
        ('model_list', base + '/models', None, '--http1.1'),
        ('compatible_http1', base + '/chat/completions', chat, '--http1.1'),
        ('compatible_http2', base + '/chat/completions', chat, '--http2'),
        ('dashscope_native', origin + '/api/v1/services/aigc/multimodal-generation/generation', native, '--http1.1'),
    ]
    print(json.dumps({'credential_source': 'hidden_prompt' if args.prompt_key else 'project_config',
                      'key_matches_project_config': key == project_key,
                      'model': model, 'direct': args.direct, 'sdk': 'none'}, ensure_ascii=False))
    results = []
    for name, endpoint, payload, protocol in cases:
        result = probe(name, endpoint, key, payload, protocol, args.direct, curl)
        results.append(result)
        print(json.dumps(result), flush=True)
    # A list endpoint alone cannot prove inference works.
    return 0 if any(r['passed'] for r in results[1:]) else 1


if __name__ == '__main__':
    raise SystemExit(main())
