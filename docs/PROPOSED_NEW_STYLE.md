<style>
:root{--ab-navy:#00205B;--ab-deep:#005670;--ab-mid:#0085AD;--ab-sky:#48A9C5;--ab-light:#74D2E7;--ab-mist:#8DB9CA;--ab-green:#009F4D;--ab-red:#E4002B;--paper:#FFFFFF;--shell:#EBF0F4;--line:#D5DEE5;--line-soft:#E8EDF1;--ink:#12212E;--ink-2:#4A5B68;--ink-3:#7C8B97}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--shell);color:var(--ink);font-family:Inter,'Segoe UI',Roboto,Arial,Helvetica,sans-serif;font-size:14px;line-height:1.55}
.mono{font-family:Consolas,'Courier New',monospace;font-variant-ligatures:none}
.sheet{max-width:1120px;margin:0 auto;padding:26px 26px 60px}
.paper{background:var(--paper);border:1px solid var(--line);border-radius:3px;padding:24px 26px 28px}
.paper.lead{border-top:3px solid var(--ab-navy);padding-top:22px}
.eyebrow{margin:0;font-size:10.5px;letter-spacing:.18em;text-transform:uppercase;color:var(--ink-3)}
.chapter{margin:13px 0 0}
.chapter span{display:block;font-size:11px;font-weight:600;letter-spacing:.16em;text-transform:uppercase;color:var(--ab-mid)}
.chapter h1{margin:5px 0 0;font-size:21px;line-height:1.25;font-weight:600;color:var(--ab-navy);overflow-wrap:anywhere}
.root-facts{margin:20px 0 0;padding:0}
.root-facts div{display:flex;flex-wrap:wrap;gap:12px;padding:8px 0;border-bottom:1px solid var(--line-soft)}
.root-facts div:first-child{border-top:1px solid var(--line-soft)}
.root-facts dt{flex:none;width:145px;font-size:14px;color:var(--ink-3)}
.root-facts dd{flex:1 1 240px;margin:0;font-size:14px;color:var(--ink);overflow-wrap:anywhere}
.root-facts .key{font-weight:600;color:var(--ab-navy)}
.field-label{font-size:10px;font-weight:600;letter-spacing:.13em;text-transform:uppercase;color:var(--ink-3)}
.doc-body{margin:18px 0 0}
.toolbar{position:sticky;top:10px;z-index:20;margin:14px 0;background:var(--paper);border:1px solid var(--line);border-radius:3px;box-shadow:0 2px 6px rgba(0,32,91,.09)}
.toolbar-inner{display:flex;flex-wrap:wrap;align-items:center;gap:10px;padding:9px 14px}
.btn{font:inherit;font-size:12.5px;font-weight:500;color:var(--ab-navy);background:var(--paper);border:1px solid var(--line);border-radius:2px;padding:7px 14px;cursor:pointer}
.btn:hover{border-color:var(--ab-mid);color:var(--ab-mid)}
.btn:focus-visible{outline:2px solid var(--ab-mid);outline-offset:2px}
.btn-group{display:flex}
.btn-group .btn{border-radius:0}
.btn-group .btn:first-child{border-radius:2px 0 0 2px}
.xattrs{margin:14px 0 0;padding:10px 12px;background:#F7FAFB;border-left:2px solid var(--ab-mid);border-radius:0 2px 2px 0}
.xattr{display:flex;flex-wrap:wrap;gap:10px;padding:3px 0}
.xattr dt{flex:none;width:150px;font-size:10px;font-weight:600;letter-spacing:.13em;text-transform:uppercase;color:var(--ab-deep)}
.xattr dd{flex:1 1 220px;margin:0;font-size:13px;color:var(--ink);overflow-wrap:anywhere}
.xattr .missing{color:var(--ink-3);font-style:italic}
.btn-group .btn:last-child{border-radius:0 2px 2px 0;margin-left:-1px}
.btn[aria-pressed='true']{background:var(--ab-navy);border-color:var(--ab-navy);color:#fff}
.stats{margin-left:auto;display:flex;gap:18px;font-size:11.5px;color:var(--ink-2)}
.stats b{font-weight:600;color:var(--ab-navy)}
.sec-title{margin:0 0 14px;font-size:11px;font-weight:600;letter-spacing:.15em;text-transform:uppercase;color:var(--ink-3)}
.tree,.children{list-style:none;margin:0;padding:0}
.node{--rail:var(--ab-mist)}
.node[data-level='1']{--rail:var(--ab-navy)}
.node[data-level='2']{--rail:var(--ab-deep)}
.node[data-level='3']{--rail:var(--ab-mid)}
.node[data-level='4']{--rail:var(--ab-sky)}
.node[data-level='5']{--rail:var(--ab-light)}
.children{margin:0 0 0 15px;padding:6px 0 0 22px;border-left:1px solid var(--line)}
.node>.children{border-left-color:var(--rail)}
.children:empty{display:none;padding:0}
.node.leaf>.children{display:none}
.card{border:1px solid var(--line);border-left:3px solid var(--rail);border-radius:2px;background:var(--paper);margin:0 0 8px}
.card-head{display:flex;align-items:center;flex-wrap:wrap;gap:10px;padding:9px 12px;cursor:pointer;user-select:none}
.card-head:hover{background:#F4F8FA}
.card-head:focus-visible{outline:2px solid var(--ab-mid);outline-offset:-2px}
.twisty{width:0;height:0;border-left:5px solid var(--ink-3);border-top:4px solid transparent;border-bottom:4px solid transparent;transform:rotate(90deg);transition:transform .12s ease;flex:none}
.node.collapsed>.card>.card-head .twisty{transform:rotate(0deg)}
.node.leaf>.card>.card-head .twisty{border-left-color:var(--line)}
.level{flex:none;min-width:26px;text-align:center;font-size:10px;font-weight:700;letter-spacing:.06em;color:#fff;background:var(--rail);border-radius:2px;padding:2px 5px}
.folder{font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-2);border:1px solid var(--line);border-radius:2px;padding:2px 7px}
.reqid{font-size:13.5px;font-weight:600;color:var(--ab-navy)}
.flow{font-size:11.5px;color:var(--ink-3)}
.flow b{font-weight:600;color:var(--ink-2)}
.count{margin-left:auto;font-size:11px;color:var(--ink-3);display:none}
.node.collapsed>.card>.card-head .count{display:inline}
.card-body{padding:2px 14px 14px 14px;border-top:1px solid var(--line-soft)}
.node.collapsed>.card>.card-body{display:none}
.node.collapsed>.children{display:none}
.text{white-space:pre-wrap;overflow-wrap:anywhere;margin:12px 0 0;font-size:13.5px;color:var(--ink)}
.doc-body .text{margin-top:6px;font-size:14px}
.empty{color:var(--ink-3);font-style:italic}
.verif{margin:14px 0 0;padding:10px 12px;background:#F4F8FA;border-left:2px solid var(--ab-green);border-radius:0 2px 2px 0}
.verif-none{border-left-color:var(--line);background:#F7F9FA}
.verif-label{font-size:10px;letter-spacing:.13em;text-transform:uppercase;color:var(--ab-deep);font-weight:600}
.verif-none .verif-label{color:var(--ink-3)}
.verif-head{display:flex;flex-wrap:wrap;align-items:center;gap:12px}
.vmethod{display:inline-flex;align-items:center;gap:6px;font-size:10.5px;font-weight:600;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-2)}
.vmethod b{display:inline-block;min-width:17px;text-align:center;font-size:10.5px;font-weight:700;color:#fff;background:var(--ab-deep);border-radius:2px;padding:1px 5px}
.verif .text{margin-top:6px;font-size:13px}
body.hide-verification .verif{display:none}
.warn{border-left-color:var(--ab-red)}
.warn .card-head{cursor:default}
.warn .reqid{color:var(--ab-red)}
.meta{margin-top:14px}
.meta-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:18px 26px;margin:0 0 24px;padding:0 0 20px;border-bottom:1px solid var(--line-soft)}
.meta-grid dt{font-size:10px;letter-spacing:.13em;text-transform:uppercase;color:var(--ink-3)}
.meta-grid dd{margin:3px 0 0;font-size:13px;overflow-wrap:anywhere}
.panel h3{margin:0 0 4px;font-size:13px;font-weight:600;color:var(--ab-navy);letter-spacing:.02em}
.panel p{margin:0 0 12px;font-size:11.5px;color:var(--ink-3)}
.modlist{list-style:none;margin:0;padding:0;display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:6px}
.modlist li{font-family:Consolas,'Courier New',monospace;font-size:12px;color:var(--ink-2);background:#F4F8FA;border:1px solid var(--line-soft);border-radius:2px;padding:6px 10px;overflow-wrap:anywhere}
.foot{margin:20px 0 0;font-size:11px;letter-spacing:.1em;text-transform:uppercase;color:var(--ink-3);text-align:center}
@media (max-width:720px){.sheet{padding:14px 12px 40px}.paper{padding:18px 14px 22px}.children{margin-left:6px;padding-left:12px}.stats{width:100%;margin-left:0}.root-facts dt{width:100%}.chapter h1{font-size:18px}}
@media (prefers-reduced-motion:reduce){.twisty{transition:none}}
@media print{body{background:#fff}.toolbar{display:none}.paper{border:0;padding:0 0 18px}.node.collapsed>.card>.card-body,.node.collapsed>.children{display:block}.card{break-inside:avoid}}
</style>