from pathlib import Path
from dotenv import load_dotenv
load_dotenv(Path(__file__).resolve().parents[2] / '.env')
"""Shared Qwen-Agent runtime. Additional voice routes can mount on this same app."""
from fastapi import FastAPI
from quote_assistant import router
from request_assistant import router as request_router
app = FastAPI(title='OnDemand Agent')
app.include_router(router)
app.include_router(request_router)


@app.get('/quote-assistant/health')
def quote_health():
    import json
    import os
    import urllib.request
    from fastapi.responses import JSONResponse

    model = os.environ.get('QUOTE_LLM_MODEL', 'qwen3:0.6b')
    url = os.environ.get('QUOTE_LLM_URL', 'http://127.0.0.1:11434/v1').rstrip('/')
    try:
        with urllib.request.urlopen(url + '/models', timeout=3) as response:
            models = json.load(response).get('data', [])
        ready = any(row.get('id') == model for row in models)
    except Exception:
        ready = False
    return JSONResponse({'status': 'ready' if ready else 'unavailable', 'model': model},
                        status_code=200 if ready else 503)

