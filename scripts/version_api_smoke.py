import json, os, urllib.request, urllib.error, http.cookiejar
from pathlib import Path
base=os.environ.get('INSIGHT_BASE_URL','http://127.0.0.1:18110')
env=dict(x.split('=',1) for x in Path('/opt/insight-bi/runtime/local.env').read_text().splitlines() if '=' in x)
opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=''
def req(method,path,obj=None):
 h={'Content-Type':'application/json'}
 if csrf and method!='GET': h['X-CSRF-Token']=csrf
 q=urllib.request.Request(base+'/api'+path,method=method,headers=h,data=json.dumps(obj,ensure_ascii=False).encode() if obj is not None else None)
 try:
  with opener.open(q,timeout=8) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:
  try:return e.code,json.load(e)
  except:return e.code,{}
s,r=req('POST','/login',{'username':'admin','password':env['BI_ADMIN_PASSWORD']})
assert s==200,(s,r);csrf=r['csrf']
s,r=req('POST','/dashboards',{'name':'版本定向测试_自动清理','mode':'grid'})
assert s==200,(s,r);id=r['id']
try:
 s,ds=req('GET','/dashboards/'+id);assert s==200
 c={'mode':'grid','theme':'light','widgets':[{'id':'a','type':'text','title':'原始版本','style':{'text':'已发布的历史内容'}}]}
 s,r=req('PUT','/dashboards/'+id,{'name':'版本一','mode':'grid','content':c,'revision':ds['revision']})
 assert s==200,(s,r)
 s,r=req('POST','/dashboards/'+id+'/publish',{});assert s==200 and r['version']==1,(s,r)
 s,r=req('GET','/dashboards/'+id+'/versions');assert s==200 and len(r)==1
 oldrev=2
 c['widgets'][0]['title']='第二个版本'
 s,r=req('PUT','/dashboards/'+id,{'name':'版本二','mode':'grid','content':c,'revision':oldrev})
 assert s==200,(s,r)
 s,r=req('POST','/dashboards/'+id+'/publish',{});assert s==200 and r['version']==2
 s,r=req('GET','/dashboards/'+id+'/versions');assert s==200 and [i['version_no'] for i in r]==[2,1]
 s,r=req('POST','/dashboards/'+id+'/versions/1/restore',{'revision':2});assert s==409,(s,r)
 s,r=req('POST','/dashboards/'+id+'/versions/1/restore',{'revision':3});assert s==200 and r['revision']==4,(s,r)
 s,restored=req('GET','/dashboards/'+id);assert s==200 and restored['name']=='版本一' and restored['content']['widgets'][0]['title']=='原始版本'
 s,published=req('GET','/dashboards/'+id+'?published=true')
 assert s==200 and published['content']['widgets'][0]['title']=='第二个版本'
 print('PASS: publish snapshots, version list, optimistic conflict, restore draft without changing published snapshot')
finally:
 s,r=req('DELETE','/dashboards/'+id)
 assert s==200,(s,r)
 print('PASS: history and dashboard cleanup')
