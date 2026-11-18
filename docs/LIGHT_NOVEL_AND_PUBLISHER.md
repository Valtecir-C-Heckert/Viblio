# Light novels e editora

Adicionar usa Livro, Mangá e Quadrinho. Em Livro, a seleção de gênero oferece Light novel · por volumes; nomes legados Light novel/Light novels também abrem esse fluxo. A opção é um formato de coleção, não um gênero: mantém o gênero anterior quando válido, permitindo Fantasia/Terror com subgêneros. Light novels são collections com kind=book e seus volumes são books com kind=book e collectionId, portanto aparecem em Livros e usam o modelo agrupado existente. Sem mudança de schema SQLite ou JSON; estes já suportam editora e coleções do tipo book.

Conversão começa com um volume e permanece em rascunho até Salvar. Mantém o ID do livro como volume 1, páginas, progresso, nota e capa; grava com UPDATE, conservando sessões, datas, seleção atual e sessão pausada quando as páginas não foram alteradas. Os demais exemplares são numerados desde 2 com páginas desconhecidas. Transação reverte a criação inteira em caso de erro. Livros previamente cadastrados como mangá não são movidos automaticamente.

Editora foi acrescentada ao formulário e à página de livro/volume, com sugestões das editoras existentes e captura/restauração do formulário. Ordem de identificação: Título, Autor, Editora. O campo já era exportado/importado no JSON.

186 testes JVM passaram sem falhas, erros ou ignorados, incluindo classificação de light novel, capa do volume 1 e conservação do identificador/progresso na conversão. Compilação e lint passaram; o ajuste final de apresentação foi recompilado. Não houve execução Android da transação SQLite nem conferência visual no celular.

Conferir no aparelho: novo Livro → gênero → Light novel; escolher gênero e informar volumes; cancelar conversão de livro existente; salvar conversão com histórico e conferir volume 1; exportar/reimportar JSON; cadastro normal de livro com editora. Nenhum APK novo foi gerado nesta etapa.
