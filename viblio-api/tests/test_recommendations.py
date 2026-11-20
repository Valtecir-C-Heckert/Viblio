"""Tests for current-book anchored recommendations, strict section isolation, owned-edition filtering, wishlist CRUD, and 'added' feed events."""
import time
import psycopg
from test_accounts import DSN, api, headers, snapshot, sync, result
from test_friends import setup, accept
from test_social import get


def _seed_catalog_books():
    with psycopg.connect(DSN, autocommit=True) as db:
        rows = [
            (770001, '1984', 'George Orwell', 'Ficção Científica', 'Distopia', 'Companhia', 'https://example.org/1984.jpg', 'Livro', 'Livro', 'wk-1984', '9788535914849', None),
            (770002, 'A Revolução dos Bichos', 'George Orwell', 'Ficção Científica', 'Sátira', 'Companhia', 'https://example.org/bichos.jpg', 'Livro', 'Livro', 'wk-bichos', '9788535909555', None),
            (770003, 'Admirável Mundo Novo', 'Aldous Huxley', 'Ficção Científica', 'Distopia', 'Biblioteca Azul', 'https://example.org/admiravel.jpg', 'Livro', 'Livro', 'wk-admiravel', '9788525056009', None),
            (770004, 'Eu, Robô', 'Isaac Asimov', 'Ficção Científica', 'Robótica', 'Aleph', 'https://example.org/robo.jpg', 'Livro', 'Livro', 'wk-robo', '9788576572008', None),
            (770005, 'Akira Vol. 1', 'Katsuhiro Otomo', 'Ficção Científica', 'Distopia', 'JBC', 'https://example.org/akira.jpg', 'Mangá', 'Mangá', 'wk-akira-1', '9788545702870', 1),
            # Martha Wells & Vagabond regression fixture:
            (770010, 'Alerta Vermelho', 'Martha Wells', 'Ficção Científica', 'Space Opera', 'Aleph', 'https://example.org/alerta1.jpg', 'Livro', 'Livro', 'wk-alerta', '9786586064018', 1),
            (770011, 'Alerta Vermelho (Diários de um Robô-Assassino Livro 1)', 'Martha Wells', 'Ficção Científica', 'Space Opera', 'Aleph', 'https://example.org/alerta2.jpg', 'Livro', 'Livro', 'wk-alerta', '9786586064999', 1),
            (770012, 'Condição Artificial', 'Wells, Martha', 'Ficção Científica', 'Space Opera', 'Aleph', 'https://example.org/condicao.jpg', 'Livro', 'Livro', 'wk-condicao', '9786586064025', 2),
            (770013, 'Protocolo Rebelde', 'Martha Wells (Autor)', 'Ficção Científica', 'Space Opera', 'Aleph', 'https://example.org/protocolo.jpg', 'Livro', 'Livro', 'wk-protocolo', '9786586064032', 3),
            (770014, 'Vagabond Vol. 1', 'Takehiko Inoue', 'Ação', 'Samurai', 'Panini', 'https://example.org/vagabond1.jpg', 'Mangá', 'Mangá', 'wk-vaga-1', '9788542600018', 1),
            (770015, 'Vagabond Vol. 2', 'Takehiko Inoue', 'Ação', 'Samurai', 'Panini', 'https://example.org/vagabond2.jpg', 'Mangá', 'Mangá', 'wk-vaga-2', '9788542600025', 2),
            (770016, 'A Guerra dos Mundos', 'H. G. Wells', 'Ficção Científica', 'Invasão', 'Suma', 'https://example.org/guerra.jpg', 'Livro', 'Livro', 'wk-hgwells', '9788556510000', None),
        ]
        for r in rows:
            db.execute(
                """INSERT INTO public.valteca(id,titulo,autor,genero,subgenero,editora,capa_url,categoria,tipo,work_key,isbn,ordem)
                   VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                   ON CONFLICT (id) DO NOTHING""",
                r,
            )


def test_recommendations_anchor_isolation_editions_and_wishlist(api):
    _seed_catalog_books()
    a, b, _ = setup(api)
    snap = snapshot()
    snap['library']['books'][0].update(
        id='cur-1984',
        catalogId='770001',
        title='1984',
        author='George Orwell',
        genre='Ficção Científica',
        subgenre='Distopia',
        subgenres=['Distopia'],
        currentPage=80,
        totalPages=328,
        pagesKnown=True,
    )
    snap['library']['currentBookId'] = 'cur-1984'
    result(sync(api, a, snap))

    rec = get(api, a, '/v1/books/recommendations?kind=book')
    assert rec['anchor_title'] == '1984'
    assert rec['anchor_kind'] == 'current'
    by_key = {s['key']: s for s in rec['sections']}
    assert 'author' in by_key and 'George Orwell' in by_key['author']['title']
    assert 'subgenre' in by_key and 'Distopia' in by_key['subgenre']['title']
    assert 'genre' in by_key

    all_ids = {item['id'] for s in rec['sections'] for item in s['items']}
    assert '770001' not in all_ids
    assert '770005' not in all_ids
    assert '770002' in {item['id'] for item in by_key['author']['items']}
    assert '770003' in {item['id'] for item in by_key['subgenre']['items']}

    manga_rec = get(api, a, '/v1/books/recommendations?kind=manga')
    manga_ids = {item['id'] for s in manga_rec['sections'] for item in s['items']}
    assert '770005' in manga_ids

    # Martha Wells & Vagabond strict author isolation + edition/work_key exclusion on user B.
    snap_b = snapshot()
    snap_b['library']['books'] = [
        {
            **snap_b['library']['books'][0],
            'id': 'cur-martha',
            'catalogId': '770010',
            'title': 'Alerta Vermelho',
            'author': 'Martha Wells',
            'genre': 'Ficção Científica',
            'subgenre': 'Space Opera',
            'subgenres': ['Space Opera'],
            'currentPage': 40,
            'totalPages': 160,
            'pagesKnown': True,
        },
        {
            **snap_b['library']['books'][0],
            'id': 'owned-vaga-1',
            'catalogId': '770014',
            'title': 'Vagabond Vol. 1',
            'author': 'Takehiko Inoue',
            'genre': 'Ação',
            'subgenre': 'Samurai',
            'subgenres': ['Samurai'],
            'kind': 'manga',
            'sequenceNumber': 1,
            'currentPage': 200,
            'totalPages': 200,
            'pagesKnown': True,
        },
    ]
    snap_b['library']['currentBookId'] = 'cur-martha'
    result(sync(api, b, snap_b))

    rec_b = get(api, b, '/v1/books/recommendations?kind=all')
    by_key_b = {s['key']: s for s in rec_b['sections']}
    assert 'author' in by_key_b and 'Martha Wells' in by_key_b['author']['title']

    author_ids = {item['id'] for item in by_key_b['author']['items']}
    all_ids_b = {item['id'] for s in rec_b['sections'] for item in s['items']}
    assert {'770012', '770013'} == author_ids
    assert '770014' not in author_ids and '770015' not in author_ids and '770016' not in author_ids
    assert '770010' not in all_ids_b and '770011' not in all_ids_b and '770014' not in all_ids_b

    vaga_rec = get(
        api,
        b,
        '/v1/books/recommendations?kind=manga&anchor_title=Vagabond+Vol.+1&anchor_author=Takehiko+Inoue&anchor_genre=A%C3%A7%C3%A3o&anchor_subgenre=Samurai',
    )
    vaga_author = {item['id'] for s in vaga_rec['sections'] if s['key'] == 'author' for item in s['items']}
    assert vaga_author == {'770015'}

    # Wishlist CRUD, isolation & health checks reusing users A and B.
    assert get(api, a, '/v1/me/wishlist')['items'] == []
    bad = api.post(
        '/v1/me/wishlist',
        headers=headers(a),
        json={'catalog_id': '770002', 'title': 'A Revolução dos Bichos', 'cover_url': 'javascript:alert(1)'},
    )
    assert bad.status_code == 422

    created = api.post(
        '/v1/me/wishlist',
        headers=headers(a),
        json={
            'catalog_id': '770002',
            'title': 'A Revolução dos Bichos',
            'author': 'George Orwell',
            'genre': 'Ficção Científica',
            'subgenre': 'Sátira',
            'pages': 152,
            'cover_url': 'https://example.org/bichos.jpg',
        },
    )
    assert created.status_code == 201
    assert created.json()['item']['id'] == '770002'
    assert len(get(api, a, '/v1/me/wishlist')['items']) == 1
    assert get(api, b, '/v1/me/wishlist')['items'] == []
    assert api.delete('/v1/me/wishlist/770002', headers=headers(a)).status_code == 200
    assert get(api, a, '/v1/me/wishlist')['items'] == []

    health = get(api, a, '/v1/health')
    assert health['api_version'] == 11
    assert health['recommendations_api'] == 1
    assert health['wishlist_api'] == 1


def test_feed_records_added_book_but_skips_initial_sync_and_bulk_import(api):
    a, b, _ = setup(api)
    accept(api, a, b)
    now = int(time.time() * 1000)

    # 1) Initial sync must NOT emit 'added' events.
    s = snapshot()
    s['library']['books'][0]['updatedAt'] = now
    result(sync(api, b, s))
    assert get(api, a, '/v1/friends/feed')['items'] == []

    # 2) Adding 1 new book emits an 'added' event visible to accepted friends.
    s['library']['books'].append({
        **s['library']['books'][0],
        'id': 'new-book-1',
        'title': 'Condição Artificial',
        'author': 'Martha Wells',
        'currentPage': 0,
        'totalPages': 160,
        'updatedAt': now + 1000,
    })
    result(sync(api, b, s, 1))
    feed = get(api, a, '/v1/friends/feed')['items']
    assert len(feed) == 1
    assert feed[0]['kind'] == 'added'
    assert feed[0]['book']['title'] == 'Condição Artificial'

    # 3) Bulk import (> 5 books added in a single sync) must NOT flood the feed.
    for idx in range(6):
        s['library']['books'].append({
            **s['library']['books'][0],
            'id': f'bulk-{idx}',
            'title': f'Livro Importado {idx}',
            'currentPage': 0,
            'totalPages': 120,
            'updatedAt': now + 2000 + idx,
        })
    result(sync(api, b, s, 2))
    feed_after = get(api, a, '/v1/friends/feed')['items']
    assert len(feed_after) == 1
    assert feed_after[0]['book']['title'] == 'Condição Artificial'
