# File Upload Feature - Browser Console Setup

The Enhanced PLC Simulator v1.1.0 includes file upload functionality that can be activated via browser console.

## Quick Setup

1. Navigate to your Ignition Gateway at `http://localhost:9088`
2. Go to **Config → Devices → Create new device** or **Edit existing device**
3. Select **Enhanced PLC Simulator** from the device type dropdown
4. Open browser Developer Tools (F12)
5. Go to the **Console** tab
6. Paste the following script and press Enter:

```javascript
(function(){if(window.plcUploadInjected)return;window.plcUploadInjected=true;function inject(){const t=document.querySelector('textarea[rows]')||findTextarea();if(!t){console.log('PLC Simulator: Retrying...');setTimeout(inject,1000);return}if(t.parentElement.querySelector('.plc-upload-btn'))return;const c=document.createElement('div');c.style.cssText='margin-bottom:12px;padding:12px;background:#f8f9fa;border:1px solid #dee2e6;border-radius:4px';const i=document.createElement('input');i.type='file';i.accept='.l5k,.L5K,.json,.JSON,.csv,.CSV,.xml,.XML';i.style.display='none';const b=document.createElement('button');b.type='button';b.className='plc-upload-btn';b.innerHTML='📁 Upload PLC File';b.style.cssText='background:#007bff;color:white;border:none;padding:8px 16px;border-radius:4px;cursor:pointer;font-size:14px';b.onmouseenter=()=>b.style.background='#0056b3';b.onmouseleave=()=>b.style.background='#007bff';b.onclick=()=>i.click();i.onchange=(e)=>{const f=e.target.files[0];if(!f)return;b.disabled=true;b.innerHTML='⏳ Reading...';const r=new FileReader();r.onload=(ev)=>{t.value=ev.target.result;t.dispatchEvent(new Event('change',{bubbles:true}));t.dispatchEvent(new Event('input',{bubbles:true}));const n=document.querySelector('input[type="text"]');if(n&&!n.value){n.value=f.name;n.dispatchEvent(new Event('change',{bubbles:true}))}b.disabled=false;b.innerHTML=`✅ Loaded (${(f.size/1024).toFixed(1)} KB)`;b.style.background='#28a745';setTimeout(()=>{b.innerHTML='📁 Upload PLC File';b.style.background='#007bff'},3000);i.value=''};r.onerror=()=>{b.disabled=false;b.innerHTML='❌ Error';b.style.background='#dc3545';setTimeout(()=>{b.innerHTML='📁 Upload PLC File';b.style.background='#007bff'},3000)};r.readAsText(f)};c.appendChild(i);c.appendChild(b);t.parentElement.insertBefore(c,t);console.log('✅ PLC file upload button added')}function findTextarea(){const labels=document.querySelectorAll('label');for(const l of labels){if(l.textContent.includes('File Content')||l.textContent.includes('PLC')){const cont=l.closest('div');if(cont){const ta=cont.querySelector('textarea');if(ta)return ta}}}return document.querySelector('textarea')}if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',inject)}else{inject()}})();
```

## What This Does

- Adds an "📁 Upload PLC File" button above the file content textarea
- Clicking the button opens a file browser dialog
- Supports L5K, JSON, CSV, and XML files
- Automatically reads the file and populates the textarea
- Shows upload status with visual feedback

## Alternative: Bookmarklet

Save this as a browser bookmark for one-click activation:

```javascript
javascript:(function(){if(window.plcUploadInjected)return;window.plcUploadInjected=true;function inject(){const t=document.querySelector('textarea[rows]')||findTextarea();if(!t){setTimeout(inject,1000);return}if(t.parentElement.querySelector('.plc-upload-btn'))return;const c=document.createElement('div');c.style.cssText='margin-bottom:12px;padding:12px;background:#f8f9fa;border:1px solid #dee2e6;border-radius:4px';const i=document.createElement('input');i.type='file';i.accept='.l5k,.L5K,.json,.JSON,.csv,.CSV,.xml,.XML';i.style.display='none';const b=document.createElement('button');b.type='button';b.className='plc-upload-btn';b.innerHTML='📁 Upload PLC File';b.style.cssText='background:#007bff;color:white;border:none;padding:8px 16px;border-radius:4px;cursor:pointer;font-size:14px';b.onclick=()=>i.click();i.onchange=(e)=>{const f=e.target.files[0];if(!f)return;b.disabled=true;b.innerHTML='⏳ Reading...';const r=new FileReader();r.onload=(ev)=>{t.value=ev.target.result;t.dispatchEvent(new Event('change',{bubbles:true}));b.innerHTML='✅ Loaded';setTimeout(()=>b.innerHTML='📁 Upload PLC File',3000)};r.readAsText(f)};c.appendChild(i);c.appendChild(b);t.parentElement.insertBefore(c,t)}function findTextarea(){return document.querySelector('textarea')}inject()})();
```

## Notes

- This feature works entirely client-side (browser-based)
- No server-side changes required
- The button needs to be re-added each time you refresh the page
- Compatible with all modern browsers (Chrome, Firefox, Edge, Safari)
