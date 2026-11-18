# Coleção e notas — JSON versão 5

A Estante conserva seu layout e o seletor fica no topo, ao lado de Estatísticas: Livros, Mangás, Quadrinhos e Todos. Livros existentes continuam individuais. Títulos em collections aparecem uma vez; tocar abre os volumes vinculados pelo collectionId.

## Campos

books continua sendo a lista de itens de leitura. Campos novos: kind (book/manga/comic), collectionId (vazio em livros individuais), publisher, rating (0–5 ou null), pagesKnown e readFinished. Cada volume tem id estável e sequenceNumber. Um volume sem páginas informadas exporta totalPages: null, currentPage: 0 e pagesKnown: false; a UI informa a ausência, não mostra página fictícia e pede páginas para usar o cronômetro. Internamente o contador usa um valor sentinela, fora dos totais de páginas e da exportação.

collections contém id, kind, title, author, genre, publisher, missingVolumes (null quando não informado), sourceReadCount, sourceRating (0–10 original), rating (0–5 atual), coverData e updatedAt. obtainedVolumes e readVolumes são derivados dos volumes. totalVolumes é calculado quando há quantidade faltante; não consulta catálogo editorial. mangaka replica author nos títulos de mangá. Mudanças na nota atual não apagam a nota original da tabela.

Capas permanecem embutidas em base64; sessões, datas, livro atual e metadados anteriores continuam no backup. A leitura aceita versões 1–5 e os formatos legados. A versão 5 requer o APK 0.4.40 ou posterior.

## Tabelas fornecidas

O arquivo adicional mangas-quadrinhos.json contém 138 títulos e 955 exemplares: 122 títulos de mangá (873 volumes), 11 títulos de quadrinho (56 volumes) e 5 títulos Light Novel em Livros (26 volumes). Numeração de 1 até Obtidos, conforme confirmado pelo usuário. Leitura X significa todos os obtidos lidos; Leitura numérica indica os primeiros N lidos. Campo vazio/0 indica nenhum lido. Faltam '-' ou vazio é conservado como desconhecido, não convertido em zero. Notas vazias/'-' são null; notas 0–10 geram o equivalente dividido por dois em cinco estrelas, incluindo meias estrelas.

Grafias de títulos, autores/mangakás e editoras foram conservadas. Classificações como Seinen/Shonen foram mantidas no campo de gênero da tabela, sem inventar gênero narrativo. Nenhuma capa, página, sessão cronometrada ou data de leitura foi inventada.

Importar o arquivo adicional acrescenta apenas esses itens, com IDs determinísticos. Livros existentes que não constam dele ficam intactos. Depois de importar, Exportar JSON completo inclui a biblioteca atual e a coleção nova. Reimportar o mesmo arquivo atualiza os mesmos IDs, sem duplicá-los, mas restaura os valores do snapshot.

minha-estante-colecao.json é um arquivo combinado opcional com o snapshot antigo de 159 livros de minha-estante-corrigida.json. Esse snapshot não representa os 164 livros atuais vistos na captura; para preservar ajustes feitos no celular, prefira importar apenas o arquivo adicional e depois exportar o backup completo no app.

## Notas

Nos detalhes do livro/volume, cinco estrelas ocupam o lugar do título Minha Estante/Livro atual. Toque na estrela desejada para salvar de 1 a 5; segure uma estrela para remover a avaliação. A atualização altera só rating, preservando páginas, cronômetro, datas e sessões. A nota do título é separada das notas de seus volumes.
