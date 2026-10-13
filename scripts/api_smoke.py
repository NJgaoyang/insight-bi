import os,json,urllib.request,urllib.error,http.cookiejar
url=os.environ.get('INSIGHT_BASE_URL','http://127.0.0.1:18110')
cj=http.cookiejar.CookieJar();opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
csrf=''
def call(method,path,body=None,token=True):
 headers={'Content-Type':'application/json'}
 if token and csrf and method!='GET':headers['X-CSRF-Token']=csrf
 payload=json.dumps(body,ensure_ascii=False).encode() if body is not None else None
 q=urllib.request.Request(url+path,data=payload,headers=headers,method=method)
 try:
  with opener.open(q,timeout=10) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:
  try: data=json.load(e)
  except Exception:data={}
  return e.code,data
assert call('GET','/api/dashboards')[0]==401
status,login=call('POST','/api/login',{'username':'admin','password':os.environ['BI_ADMIN_PASSWORD']})
assert status==200,(status,login)
csrf=login['csrf']
assert call('GET','/api/connections')[0]==200
assert call('GET','/api/datasets')[0]==200
assert call('POST','/api/dashboards',{'name':'forbidden','mode':'grid'},False)[0]==403
status,c=call('POST','/api/dashboards',{'name':'测试用例_自动清理','mode':'free'})
assert status==200,(status,c)
id=c['id']
try:
 status,item=call('GET','/api/dashboards/'+id)
 assert status==200 and item['content']['widgets']==[] and item['mode']=='free',(status,item)
 content={'mode':'free','theme':'dark','widgets':[{'id':'test-text','type':'text','title':'文字组件','style':{'text':'真实保存测试'}}]}
 status,r=call('PUT','/api/dashboards/'+id,{'name':'测试修改','mode':'free','revision':item['revision'],'content':content})
 assert status==200 and r['revision']==2,(status,r)
 assert call('PUT','/api/dashboards/'+id,{'name':'stale','mode':'free','revision':1,'content':content})[0]==409
 status,x=call('GET','/api/dashboards/'+id)
 assert status==200 and x['content']['widgets'][0]['style']['text']=='真实保存测试'
 assert call('POST','/api/dashboards/'+id+'/publish',{})[0]==200
 status,p=call('GET','/api/dashboards/'+id+'?published=true')
 assert status==200 and p['content']['widgets'][0]['id']=='test-text'
 assert call('POST','/api/dashboards/'+id+'/unpublish',{})[0]==200
 assert call('POST','/api/query',{'datasetId':'does-not-exist','dimensions':['x']})[0]==404
 print('PASS unauthentication / login / CSRF / list / new free-canvas / save / revision-conflict / publish / unpublish / query-validation')
finally:
 assert call('DELETE','/api/dashboards/'+id)[0]==200
 print('PASS cleanup; no sample dashboard left')
