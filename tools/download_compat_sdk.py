"""Download a read-only verification SDK subset from official JetBrains ZIPs.
No installation or IDE launch. HTTP Range avoids downloading unrelated IDE plugins.
"""
from __future__ import annotations
import argparse, io, json, re, struct, urllib.request, zipfile, zlib, hashlib
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
class RemoteZip(io.RawIOBase):
    def __init__(self,url):
        self.url=url
        request=urllib.request.Request(url,method='HEAD')
        with urllib.request.urlopen(request,timeout=40) as r:
            self.size=int(r.headers['Content-Length']); self.url=r.url
        self.pos=0; self.downloaded=0; self.cache={}
    def readable(self):return True
    def seekable(self):return True
    def tell(self):return self.pos
    def seek(self,offset,whence=0):
        self.pos=offset if whence==0 else self.pos+offset if whence==1 else self.size+offset
        return self.pos
    def range(self,start,length):
        if length<=0:return b''
        key=(start,length)
        if key in self.cache:return self.cache[key]
        request=urllib.request.Request(self.url,headers={'Range':f'bytes={start}-{start+length-1}'})
        with urllib.request.urlopen(request,timeout=90) as r:
            if r.status!=206:raise ValueError(f'Expected HTTP206, got {r.status}; refusing full download')
            data=r.read()
            if len(data)!=length:raise ValueError('Incomplete HTTP range')
        self.downloaded+=length
        if length<4_000_000:self.cache[key]=data
        return data
    def read(self,size=-1):
        if size<0:size=self.size-self.pos
        size=min(size,self.size-self.pos)
        result=self.range(self.pos,size);self.pos+=len(result);return result
    def extract(self,info,destination):
        header=self.range(info.header_offset,30)
        signature,version,flags,method,mtime,mdate,crc,csize,usize,nlen,elen=struct.unpack('<IHHHHHIIIHH',header)
        assert signature==0x04034b50 and not(flags&1)
        offset=info.header_offset+30+nlen+elen
        raw=self.range(offset,info.compress_size)
        data=zlib.decompress(raw,-15) if method==8 else raw if method==0 else None
        if data is None:raise ValueError(f'Unsupported ZIP method {method}')
        assert len(data)==info.file_size and zlib.crc32(data)==info.CRC,info.filename
        destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(data)
        return hashlib.sha256(data).hexdigest()

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--version',required=True);ap.add_argument('--list-only',action='store_true');ap.add_argument('--notebooks-only',action='store_true');args=ap.parse_args()
    assert re.fullmatch(r'20\d\d\.\d(?:\.\d+)?',args.version)
    url=f'https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/pycharm/pycharmPY/{args.version}/pycharmPY-{args.version}.zip'
    remote=RemoteZip(url)
    with zipfile.ZipFile(remote) as archive:
        entries=archive.infolist()
    selected=[]
    for entry in entries:
        name=entry.filename
        if name.startswith('plugins/') and any(s in name for s in ('notebooks','jupyter','markdown')) and name.endswith('.jar'):selected.append(entry)
        elif not args.notebooks_only and (name.startswith('lib/') and name.endswith('.jar') and name.count('/')==1 or name in ('product-info.json','build.txt','modules/module-descriptors.jar')):selected.append(entry)
    print(json.dumps({'url':url,'size':remote.size,'selected_compressed':sum(x.compress_size for x in selected),'selected_files':[(x.filename,x.file_size,x.compress_size) for x in selected]},indent=2),flush=True)
    if args.list_only:return
    target=ROOT/'.build'/'compat-sdks'/('pycharmPY-'+args.version)
    records=[]
    for entry in selected:
        dest=target/entry.filename
        if dest.exists() and dest.stat().st_size==entry.file_size and zlib.crc32(dest.read_bytes())==entry.CRC:
            print('Cached',entry.filename,flush=True)
            sha=hashlib.sha256(dest.read_bytes()).hexdigest()
        else:
            print('Download',entry.filename,entry.compress_size,flush=True)
            sha=remote.extract(entry,dest)
        records.append({'path':entry.filename,'sha256':sha,'size':entry.file_size})
    target.mkdir(parents=True,exist_ok=True)
    (target/'download-provenance.json').write_text(json.dumps({'source':url,'distribution_bytes':remote.size,'downloaded_bytes_this_run':remote.downloaded,'subset':True,'entries':records},indent=2),encoding='utf-8')
    print('Saved',target,'HTTP bytes:',remote.downloaded,flush=True)
if __name__=='__main__':main()
