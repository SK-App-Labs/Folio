import json
import os

json_path = r'c:\Users\sivas\AndroidStudioProjects\Folio\play_store_metadata\all_languages_listing.json'
with open(json_path, 'r', encoding='utf-8') as f:
    translations = json.load(f)

json_str = json.dumps(translations, ensure_ascii=False)

html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Folio — Google Play Store Localized Listings (13 Languages)</title>
  <style>
    :root {{
      --bg: #090b10;
      --card: #121620;
      --border: #22293a;
      --text: #f1f5f9;
      --muted: #94a3b8;
      --accent: #10b981;
      --accent-dim: rgba(16, 185, 129, 0.15);
    }}
    * {{ box-sizing: border-box; margin: 0; padding: 0; }}
    body {{
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      padding: 24px;
      line-height: 1.5;
    }}
    .header {{
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 16px;
      margin-bottom: 24px;
      padding-bottom: 16px;
      border-bottom: 1px solid var(--border);
    }}
    h1 {{ font-size: 22px; font-weight: 700; }}
    .subtitle {{ font-size: 13px; color: var(--muted); }}
    .lang-tabs {{
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 24px;
      background: var(--card);
      padding: 12px;
      border-radius: 12px;
      border: 1px solid var(--border);
    }}
    .tab-btn {{
      background: #1a202e;
      color: var(--text);
      border: 1px solid var(--border);
      padding: 8px 14px;
      border-radius: 8px;
      font-size: 13px;
      cursor: pointer;
      transition: all 0.15s ease;
    }}
    .tab-btn:hover {{ border-color: var(--accent); }}
    .tab-btn.active {{
      background: var(--accent);
      color: #03150e;
      border-color: var(--accent);
      font-weight: 600;
    }}
    .grid {{
      display: grid;
      grid-template-columns: 1fr;
      gap: 20px;
    }}
    .card {{
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 20px;
    }}
    .card-head {{
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 12px;
    }}
    .card-title {{
      font-size: 14px;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--muted);
      font-weight: 600;
    }}
    .badge {{
      font-size: 12px;
      background: #1a202e;
      color: var(--accent);
      padding: 3px 8px;
      border-radius: 6px;
      border: 1px solid var(--border);
      font-family: monospace;
    }}
    .field-box {{
      background: #090c13;
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 14px;
      font-size: 14px;
      white-space: pre-wrap;
      word-break: break-word;
      font-family: inherit;
      color: #e2e8f0;
    }}
    .btn-copy {{
      background: var(--accent-dim);
      color: var(--accent);
      border: 1px solid var(--accent);
      padding: 6px 14px;
      border-radius: 6px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
      transition: all 0.15s ease;
    }}
    .btn-copy:hover {{
      background: var(--accent);
      color: #03150e;
    }}
    .toast {{
      position: fixed;
      bottom: 24px;
      right: 24px;
      background: var(--accent);
      color: #03150e;
      padding: 10px 18px;
      border-radius: 8px;
      font-weight: 600;
      font-size: 13px;
      display: none;
      box-shadow: 0 4px 12px rgba(0,0,0,0.4);
      z-index: 999;
    }}
  </style>
</head>
<body>
  <div class="header">
    <div>
      <h1>Folio: Play Store Translation Hub</h1>
      <p class="subtitle">13 Native Localized Store Listings • All Character Limits Verified</p>
    </div>
  </div>

  <div class="lang-tabs" id="tabs"></div>

  <div class="grid">
    <!-- Title -->
    <div class="card">
      <div class="card-head">
        <span class="card-title">App Title</span>
        <div style="display:flex; gap:8px; align-items:center;">
          <span class="badge" id="titleCount">0 / 30</span>
          <button class="btn-copy" onclick="copyText('titleBox')">Copy Title</button>
        </div>
      </div>
      <div class="field-box" id="titleBox"></div>
    </div>

    <!-- Short Description -->
    <div class="card">
      <div class="card-head">
        <span class="card-title">Short Description</span>
        <div style="display:flex; gap:8px; align-items:center;">
          <span class="badge" id="shortCount">0 / 80</span>
          <button class="btn-copy" onclick="copyText('shortBox')">Copy Short Description</button>
        </div>
      </div>
      <div class="field-box" id="shortBox"></div>
    </div>

    <!-- Full Description -->
    <div class="card">
      <div class="card-head">
        <span class="card-title">Full Description</span>
        <div style="display:flex; gap:8px; align-items:center;">
          <span class="badge" id="fullCount">0 / 4000</span>
          <button class="btn-copy" onclick="copyText('fullBox')">Copy Full Description</button>
        </div>
      </div>
      <div class="field-box" id="fullBox" style="max-height: 480px; overflow-y: auto;"></div>
    </div>
  </div>

  <div class="toast" id="toast">Copied to clipboard!</div>

  <script>
    const data = {json_str};
    let currentLang = 'en-US';

    const tabsContainer = document.getElementById('tabs');
    Object.keys(data).forEach(lang => {{
      const btn = document.createElement('button');
      btn.className = 'tab-btn' + (lang === currentLang ? ' active' : '');
      btn.innerText = data[lang].lang_name;
      btn.onclick = () => selectLang(lang, btn);
      tabsContainer.appendChild(btn);
    }});

    function selectLang(lang, btn) {{
      currentLang = lang;
      document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      render();
    }}

    function render() {{
      const item = data[currentLang];
      document.getElementById('titleBox').innerText = item.app_name;
      document.getElementById('titleCount').innerText = item.app_name.length + ' / 30';

      document.getElementById('shortBox').innerText = item.short_description;
      document.getElementById('shortCount').innerText = item.short_description.length + ' / 80';

      document.getElementById('fullBox').innerText = item.full_description;
      document.getElementById('fullCount').innerText = item.full_description.length + ' / 4000';
    }}

    function copyText(elementId) {{
      const text = document.getElementById(elementId).innerText;
      navigator.clipboard.writeText(text).then(() => {{
        const toast = document.getElementById('toast');
        toast.style.display = 'block';
        setTimeout(() => {{ toast.style.display = 'none'; }}, 2000);
      }});
    }}

    render();
  </script>
</body>
</html>
"""

workspace_html = r'c:\Users\sivas\AndroidStudioProjects\Folio\play_store_metadata\translations_hub.html'
artifact_html = r'C:\Users\sivas\.gemini\antigravity\brain\7e9e726a-fa41-45b7-aafa-c81ba69ec9dc\play_store_translations_hub.html'

with open(workspace_html, 'w', encoding='utf-8') as f:
    f.write(html)

with open(artifact_html, 'w', encoding='utf-8') as f:
    f.write(html)

print('Translation Hub HTML successfully generated in workspace and artifacts!')
