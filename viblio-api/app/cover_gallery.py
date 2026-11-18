"""Read-only cover grouping. Editions and personal choices are never rewritten."""
import re
import uuid
from fastapi import HTTPException

EDITION_LIMIT=1000
UPLOAD_LIMIT=5000
KIND="""CASE WHEN public.viblio_cover_key(v.tipo) IN ('manga','mangas') OR public.viblio_cover_key(v.categoria) IN ('manga','mangas') THEN 'manga' WHEN public.viblio_cover_key(v.tipo) IN ('hq','quadrinho','quadrinhos','comic','comics') OR public.viblio_cover_key(v.categoria) IN ('hq','quadrinho','quadrinhos','comic','comics') THEN 'comic' ELSE 'book' END"""

def canonical_rows(rows,connection,public_url):
    """Expose known shared uploads under /v1 without rewriting their stored URL."""
    ids={match[1] for row in rows if (match:=re.search(r'/covers/([0-9a-f]{32})\.jpg$',(row['capa_url'] or '').strip()))}
    if not ids:return rows
    known={item['id'].hex for item in connection.execute('SELECT id FROM public.viblio_api_covers WHERE id=ANY(%s)',([uuid.UUID(value) for value in ids],)).fetchall()}
    result=[]
    for row in rows:
        item=dict(row);match=re.search(r'/covers/([0-9a-f]{32})\.jpg$',(item['capa_url'] or '').strip())
        if match and match[1] in known:item['capa_url']=f'{public_url}/v1/covers/{match[1]}.jpg'
        result.append(item)
    return result

def gallery(connection,book_id,public_url,limit=30,cursor=0):
    base=connection.execute(f'SELECT id,titulo,autor,editora,isbn,metricas_totais,metrica_tipo,ordem,capa_url,work_key,{KIND} AS kind FROM public.valteca v WHERE id=%s',(book_id,)).fetchone()
    if base is None:raise HTTPException(404,'Livro não encontrado.')
    rows=connection.execute(f"""SELECT id,titulo,autor,editora,isbn,metricas_totais,metrica_tipo,ordem,capa_url FROM public.valteca v
        WHERE v.id=%(id)s OR (({KIND})=%(kind)s AND v.ordem IS NOT DISTINCT FROM %(order)s AND (
            (nullif(btrim(v.work_key),'') IS NOT NULL AND md5(nullif(btrim(v.work_key),''))=md5(nullif(btrim(%(work)s::text),'')) AND btrim(v.work_key)=btrim(%(work)s::text)) OR
            (md5(public.viblio_cover_key(v.titulo))=md5(public.viblio_cover_key(%(title)s)) AND md5(public.viblio_cover_key(v.autor))=md5(public.viblio_cover_key(%(author)s))
             AND public.viblio_cover_key(v.titulo)<>'' AND public.viblio_cover_key(v.titulo)=public.viblio_cover_key(%(title)s) AND public.viblio_cover_key(%(author)s)<>'' AND public.viblio_cover_key(v.autor)=public.viblio_cover_key(%(author)s)
             AND (nullif(btrim(v.work_key),'') IS NULL OR nullif(btrim(%(work)s::text),'') IS NULL OR btrim(v.work_key)=btrim(%(work)s::text)))))
        ORDER BY (v.id=%(id)s) DESC,v.id LIMIT %(limit)s""",dict(id=book_id,kind=base['kind'],order=base['ordem'],work=base['work_key'],title=base['titulo'],author=base['autor'],limit=EDITION_LIMIT+1)).fetchall()
    truncated=len(rows)>EDITION_LIMIT;rows=rows[:EDITION_LIMIT];by_id={r['id']:r for r in rows}
    uploads=connection.execute('SELECT book_id,cover_id,fingerprint FROM public.viblio_book_covers WHERE book_id=ANY(%s) ORDER BY (book_id=%s) DESC,book_id,created_at,cover_id LIMIT %s',(list(by_id),book_id,UPLOAD_LIMIT+1)).fetchall()
    truncated|=len(uploads)>UPLOAD_LIMIT;uploads=uploads[:UPLOAD_LIMIT]
    upload_ids={r['cover_id'].hex:r for r in uploads}
    items=[];seen_urls=set();seen_hashes=set()
    def append(row,url,identifier,primary=False,fingerprint=None):
        url=(url or '').strip()
        if not url or url in seen_urls or fingerprint and fingerprint in seen_hashes:return
        seen_urls.add(url)
        if fingerprint:seen_hashes.add(fingerprint)
        pages=row['metricas_totais'] if (row['metrica_tipo'] or '').strip().lower() in ('pagina','paginas','página','páginas','page','pages') else None
        order=row['ordem']
        items.append(dict(id=identifier,url=url,primary=primary,book_id=str(row['id']),title=row['titulo'],publisher=row['editora'] or '',isbn=row['isbn'] or '',pages=pages if pages and 0<pages<=100000 else None,sequence=int(order) if order is not None and order==int(order) and 1<=order<=10000 else None))
    for row in rows:
        url=(row['capa_url'] or '').strip();match=re.search(r'/covers/([0-9a-f]{32})\.jpg$',url)
        upload=upload_ids.get(match[1]) if match else None
        if upload:url=f"{public_url}/v1/covers/{upload['cover_id'].hex}.jpg"
        append(row,url,'primary' if row['id']==book_id else 'edition:'+str(row['id']),row['id']==book_id,upload['fingerprint'] if upload else None)
    for upload in uploads:
        append(by_id[upload['book_id']],f"{public_url}/v1/covers/{upload['cover_id'].hex}.jpg",str(upload['cover_id']),fingerprint=upload['fingerprint'])
    page=items[cursor:cursor+limit];more=cursor+len(page)<len(items)
    return dict(items=page,has_more=more,next_cursor=cursor+len(page) if more else None,truncated=truncated)
