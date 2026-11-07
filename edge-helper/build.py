from pathlib import Path
import zipfile
root=Path(__file__).resolve().parent
header='''// ==UserScript==
// @name         PhotosBeam Selection Helper
// @namespace    https://github.com/silviuk/albumbeam
// @version      0.1.0
// @description  Touch-friendly loaded-item/range selection and experimental browser video handoff.
// @match        https://photos.google.com/*
// @run-at       document-idle
// @noframes
// @grant        none
// ==/UserScript==
'''
(root/'photosbeam-helper.user.js').write_text(header+'\n'+(root/'core.js').read_text()+'\n'+(root/'content.js').read_text())
out=root.parent.parent/'photosbeam-edge-helper-v0.1.0.zip'
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
 for name in ['manifest.json','core.js','content.js','popup.html','README.md']:
  if (root/name).exists():z.write(root/name,name)
print(out)
