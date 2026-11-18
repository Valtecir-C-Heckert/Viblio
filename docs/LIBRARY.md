# Biblioteca da Central — 0.4.0

O cadastro inclui **Número na saga (opcional)**. Esse número aparece nos detalhes e nos cards e é salvo no banco e no backup JSON como `sequenceNumber`. Veja [importação e correção dos títulos antigos](BOOK_METADATA.md).

A estante vertical usa rolagem contínua, sem paginação, e permite escolher um limite de 1 a 6 colunas (padrão 3), adaptado à largura disponível em Sistema → Configurações da Central → Livros. A barra oferece busca e ações para adicionar livro, estatísticas e backup/importação; filtros e livro atual ficam acima dos livros. A navegação inferior é fixa. Detalhes, sessões e formulários também podem rolar.

A área Livros é agora uma biblioteca integrada à Central, inspirada nas referências visuais fornecidas. A biblioteca começa vazia: a biblioteca pessoal não é embutida no APK. O JSON Minha Estante enviado pelo usuário foi validado na 0.3.1: seus 159 registros, progresso, livro atual e duas capas são importáveis. Adicione os livros ou importe um arquivo compatível. A instalação não modifica o app Minha Estante.

## Uso

- **Livros → Adicionar livro:** título e total de páginas obrigatórios; autor, gênero, página atual e capa opcionais. Escolha a capa pelo seletor de arquivos do Android. A Central guarda uma cópia reduzida privada, sem depender da permissão de acesso ao arquivo original. Para uma capa da web, abra a busca, baixe a imagem e escolha o arquivo.
- **Estante:** busca por título/autor, filtros por situação e gênero. Toque em um livro para ver detalhes. “Tornar atual” escolhe o livro mostrado também no Início. A barra deslizante atualiza páginas e porcentagem enquanto você arrasta e salva ao soltar. O menu superior oferece edição, página exata e seleção do livro atual.
- **Livro → Iniciar leitura:** inicia o cronômetro na página atual e torna o livro atual. Pausar conserva o tempo; Continuar retoma. A leitura permanece aberta ao atualizar páginas. “Concluir livro” exige confirmação, marca a última página e salva tempo, páginas, média e histórico numa transação SQLite. Cancelar a confirmação mantém a leitura como estava. Descartar tempo está no menu superior e exige confirmação. Durante uma leitura, o progresso pode voltar até a página inicial, sem gerar páginas cronometradas negativas.
- **Estatísticas:** livros, concluídos, em leitura, autores, páginas lidas, progresso geral, gêneros, tempo registrado e ritmo médio. Os números vêm do banco local, sem dados demonstrativos.
- **Livro aberto:** capa centralizada, título e autor, fundo automático da capa desfocada com camada escura, cronômetro em card com a cor do gênero e histórico recolhido. Sem capa, o fundo é preto. Não há personalização de fundo dentro do livro.
- **Backup / importar:** exporta livros, capas embutidas e sessões salvas. Uma sessão ainda aberta não faz parte do backup: finalize antes para incluí-la. Reimporte o arquivo para restaurar os dados. Importação adiciona registros; IDs já presentes são atualizados. Não apaga os demais livros.

A seção Ajustes continua acessível pela engrenagem fixa. “Configurações do Android” agora fica também no topo dos Ajustes, antes dos campos de servidor. As opções de trocar e desativar Home permanecem disponíveis. Confirme essas saídas no aparelho antes de escolher a Central como launcher.

## Cronômetro e conta por página

Uma sessão usa:

```
páginas lidas = página final − página inicial
ritmo da sessão = tempo ativo ÷ páginas lidas
ritmo geral = soma dos tempos das sessões ÷ soma das páginas cronometradas
```

Exemplo: da página 354 à 364 em 30 minutos: 10 páginas, 3 minutos/página. A média geral é ponderada, não a média simples das médias de cada sessão. Uma sessão com zero páginas conserva seu tempo e não tem média individual; seu tempo faz parte do tempo total. Sem páginas cronometradas, não há divisão por zero e a interface mostra “Sem páginas cronometradas”. Atualizações manuais de progresso não entram no denominador do ritmo.

O cronômetro continua quando você troca de tela, abre outro app ou bloqueia o celular. Usa relógio monotônico, não o horário do sistema. Não depende de serviço em background: persiste a âncora de tempo e pode recuperar o intervalo ao reabrir no mesmo boot, mesmo se o processo tiver sido encerrado. Há checkpoints a cada 5 segundos enquanto a Central está visível, e ao sair dela. Não há atualização a cada segundo quando não existe sessão ativa.

No Android 7+ a contagem de boot permite detectar reinício: a sessão reaparece pausada, com o último tempo salvo; não conta o intervalo em que o aparelho esteve desligado. O intervalo após o último checkpoint até um reinício pode ser perdido. No Android 5/6, sem o contador de boot, a sessão é recuperada pausada no último checkpoint ao recriar a Activity. Pause explicitamente ao parar de ler. Só uma sessão pode estar aberta por vez. Não é possível alterar as páginas do livro durante sua sessão, nem reduzir o total abaixo de uma página já registrada no histórico.

## Persistência

`LibraryRepository` usa SQLite nativo privado (`library.db`), com tabelas `books`, `sessions`, `draft` e `preferences`. Atualizar um livro preserva o histórico; excluir exige confirmação e remove suas sessões/draft por chave estrangeira. Salvar sessão/progresso e importar um conjunto de registros são operações atômicas. Dados sobrevivem a fechar o app e atualizar o APK; desinstalar ou limpar armazenamento os apaga. Faça backup JSON. O APK de testes usa um banco separado; não acompanha o APK distribuído.

## JSON portátil (schemaVersion 1)

```json
{
  "schemaVersion": 1,
  "currentBookId": "livro-1",
  "books": [{
    "id": "livro-1",
    "title": "Meu livro",
    "author": "Autor",
    "genre": "Fantasia",
    "totalPages": 592,
    "currentPage": 354,
    "coverData": "",
    "background": "black",
    "updatedAt": 1791288000000
  }],
  "sessions": [{
    "id": "sessao-1",
    "bookId": "livro-1",
    "startedAt": 1791284400000,
    "finishedAt": 1791286200000,
    "durationMs": 1800000,
    "startPage": 344,
    "endPage": 354
  }]
}
```

Datas são milissegundos Unix; duração é em milissegundos. Fundos: `black`, `graphite`, `slate`, `warm`, `blue`, `wine`, `cover`. Capa exportada: `data:image/jpeg;base64,...`, ou vazio. Não inclui configurações, credenciais, endereços de servidor ou sessão aberta.

Para JSON antigo, aceita array de livros na raiz ou uma lista `books`/`livros`, com estes campos:

| Informação | Campos aceitos |
|---|---|
| Título | `title`, `titulo`, `título` |
| Autor | `author`, `autor` |
| Gênero | `genre`, `genero`, `gênero` |
| Total | `totalPages`, `total_pages`, `totalPaginas`, `total_paginas`, `paginas`, `pages` |
| Página atual | `currentPage`, `current_page`, `paginaAtual`, `pagina_atual`, `paginasLidas`, `paginas_lidas`, `readPages`, `read` |
| Capa embutida | `coverData`, `cover`, `capa`, `coverImage` |

Sem ID, gera um identificador estável por título/autor para não duplicar ao reimportar o mesmo arquivo. Caminhos locais de outro aplicativo, URLs de capa e formatos não reconhecidos são recusados com mensagem; o formato original Minha Estante `version: 1`, `selectedId`, `books[].pages` e `books[].read` foi validado. `selectedId` é usado como livro atual; o campo novo `currentBookId`, quando presente, tem prioridade. A versão 0.3.0 não reconhecia `read`/`selectedId`; use a 0.3.1 antes de importar esse arquivo. Não infere quantidade de páginas a partir de percentuais nem recupera capas de screenshots.

Limites atuais: 32 MB por importação, 5.000 livros/20.000 sessões por arquivo, 12 MB por capa escolhida, total de 1 a 100.000 páginas por livro. Capas são decodificadas com amostragem e recomprimidas em JPEG até cerca de 1.024 px. A leitura de arquivos, a transação de importação no SQLite e a exportação rodam fora da thread da interface. O banco usa WAL para permitir consultas enquanto há escrita; a UI é atualizada ao concluir. Formatos muito grandes devem ser divididos. Importação não é sincronização automática nem faz descoberta da rede.

## Servidor futuro

Ainda não existe servidor/API, conforme confirmado pelo usuário. Esta entrega usa banco local e **não declara integração remota funcionando**. O ponto de acesso aos dados é o repositório, separado das Views. O snapshot acima é o contrato inicial de transporte; não é uma API implementada.

Para o servidor, usar tabelas equivalentes em PostgreSQL ou SQLite conforme hospedagem, IDs estáveis e sessões imutáveis identificadas por ID. Uma etapa de sincronização deverá acrescentar autenticação, revisões do servidor, operações idempotentes, conflitos explícitos de progresso/livro atual, tombstones para exclusões, uploads de capas e fila offline. `updatedAt` local é metadado, não resolve conflitos entre relógios diferentes. Não enviar backups com credenciais nem armazenar imagens como caminho privado do telefone no banco do servidor.

## Aparência local (0.4.3)

Em Sistema → Configurações da Central → Livros, configure livros por linha e cores por gênero. As preferências ficam em `panel_settings`, não mudam livros/sessões no SQLite e não entram no backup JSON. A cor por gênero é compartilhada entre livros do mesmo gênero, ignorando diferenças de maiúsculas e espaços nas extremidades; gênero vazio usa Sem gênero. Há cores sugeridas e entrada #RRGGBB. O texto alterna entre branco/preto por contraste.

Até quatro colunas, cards mostram capa e nome; cinco ou seis usam somente capas. Capas usam proporção 2:3 e FIT_CENTER, títulos têm até três linhas e cards não têm altura fixa. Modos com título preservam pelo menos 96 dp por card (aumentados pela escala de fonte); modos de capas têm mínimo de 48 dp e espaços de 4 dp. Se nem cinco cards couberem, a grade volta ao modo legível com título. Os cards ficam sobre a cor do gênero, mantendo bordas de leitura verde/azul/laranja/cinza. Não há personalização de fundo dentro do livro ou no formulário. A capa desfocada preenche automaticamente a tela inteira, centralizada e preservando a proporção; sem capa, usa preto. A capa principal do detalhe também fica centralizada; o card do detalhe permanece na cor do gênero. Sair do detalhe restaura o fundo preto. A capa desfocada conserva escurecimento para legibilidade.

## Filtros combináveis (0.4.4)

Filtrar abre situação (incluindo somente o atual), gênero, autor, presença/ausência de capa e ordenação. Todos os critérios são aplicados juntos com a busca, que aceita título, autor e gênero sem distinguir acentos ou maiúsculas. Palavras da busca podem corresponder a campos diferentes do mesmo livro. A estante informa quantos livros foram encontrados e os filtros ativos. Limpar/limpar todos remove também busca e ordenação especial.

Ordens: título A–Z/Z–A, autor A–Z, progresso maior/menor (percentual, não páginas absolutas), atualização mais recente e número na saga (sem número ao final). Empates são estáveis por título/ID. Filtros são restaurados ao recriar a Activity; não excluem livros nem alteram o backup. Quatro testes JVM cobrem critérios simultâneos, busca sem acentos, leitura/atual, ordenação e metadados vazios.

## Datas de leitura

O histórico reúne sessões pelo dia local de início e dias informados manualmente. Novas sessões persistem o dia civil para que viagens ou mudanças de fuso não alterem o registro; sessões anteriores à 0.4.30 usam o horário inicial no fuso do celular até uma correção manual. Uma sessão que atravessa meia-noite permanece no dia de início: não distribuímos tempo ou páginas entre dias sem medições.

Adicionar data oferece Um dia, Vários dias e Período (todos os dias entre as duas datas, inclusive; até 3.660 dias). Dias manuais podem ser corrigidos ou removidos no lápis do histórico e não geram sessões ou entram na média. Dias repetidos do mesmo livro são reunidos. Primeiro/último registro não são rotulados como começo/conclusão, pois não temos evidência disso. Para informar somente a conclusão de um livro antigo, registre esse único dia.

SQLite v7 mantém reading_day opcional em sessions e reading_dates com chave estrangeira e unicidade por livro/dia. Migração preserva os dados anteriores. Backup schemaVersion 4 leva datas manuais, correções e subgênero; versões 1/2/3 e bibliotecas legadas continuam aceitas. Importação combina registros sem apagar a biblioteca existente e sem duplicar IDs/dias.

## Resolução dos fundos

As miniaturas da estante continuam pequenas. O fundo dos livros passa a carregar a capa armazenada separadamente, e o efeito compartilhado por Início/Música/Livros preserva até 1.920 pixels na maior dimensão, mantendo o desfoque de 20% e os degradês existentes. O cache de fundos de livros fica limitado a dois itens. Novas imagens importadas usam até 2.048 pixels e JPEG de qualidade 95; arquivos antigos não ganham detalhes que já perderam. Se necessário, selecione novamente o fundo original e reimporte as capas. Não há ampliação artificial por IA.

## Gênero principal e subgênero

Campos separados no editor, com sugestões reaproveitadas da biblioteca. Subgêneros sugeridos são limitados ao gênero principal escolhido; ambos aceitam texto novo. Rótulos antigos reconhecidos, como Fantasia Dark e Fantasia Épica/Epica, são convertidos em Fantasia + Dark/Épica. Classificações desconhecidas permanecem intactas para edição manual, evitando adivinhar categorias.

SQLite v7 mantém subgenre e separa os rótulos reconhecidos sem alterar IDs, progresso, capas, sessões ou dias. JSON v4 inclui subgenres; importação aceita v1/v2/v3 e formatos legados. Busca considera ambos, filtros são separados, e a ordenação por prioridade oferece Subgênero. Estatísticas e cores continuam por gênero principal.

Na primeira abertura, a paleta preserva uma cor já definida para o gênero principal. Se havia somente cores diferentes para subgêneros antigos, reaproveita uma delas pela ordem alfabética das chaves; todas as subdivisões passam a compartilhar a mesma cor. As chaves antigas são mantidas, e a paleta permite escolher outra cor para o principal. Filtros antigos como Fantasia Dark são convertidos em gênero Fantasia + subgênero Dark.

## Formulário de livros e até três subgêneros

Adicionar e editar usam cabeçalho com fonte serifada, cartões transparentes com borda e seções Capa, Livro, Classificação, Saga e edição, Leitura. Total de páginas e página atual ficam lado a lado; salvar/cancelar ficam no fim. Gênero e subgênero abrem seletores com itens da biblioteca e opção Criar novo. Sem gênero limpa também os subgêneros do formulário. Alterações só são persistidas ao salvar o livro.

O livro aceita de zero a três subgêneros únicos. Tiles lado a lado mostram as escolhas e permitem remover com um toque; o próximo tile oferece Adicionar até completar três. A leitura também mostra os tiles. Busca e filtro consideram qualquer um dos três, e a ordenação por subgênero usa a combinação de etiquetas. As cores continuam somente pelo gênero principal.

Autor e saga oferecem sugestões por trechos do nome, ignorando acentos e reconhecendo pequenos erros de digitação, com até 12 resultados. São campos livres para nomes novos. As sugestões não criam nomes por conta própria nem alteram metadados sem seleção/salvamento.

SQLite v7 guarda a lista em JSON, preservando o subgênero único antigo como primeiro item, e mantém o campo antigo espelhado para compatibilidade interna. Backup JSON v4 inclui a lista; leitura aceita backups v1/v2/v3 e o campo antigo único. Lista com mais de três itens ou tipo inválido é recusada antes da importação. IDs, páginas, capas, sessões, cronômetros e dias de leitura são preservados.


## Ajustes de leitura 0.4.31 (código, sem novo APK)

Terror e Não-Ficção são gêneros principais: o sufixo de rótulos antigos vira subgênero, incluindo separadores com hífen/dois-pontos. Fantasia conserva os rótulos conhecidos. A migração v6→v7 atualiza os livros sem substituir registros e preserva páginas, timestamps, capas e histórico. Se combinar rótulo e subgêneros existentes excederia três itens, mantém o registro antigo para edição manual, em vez de apagar um subgênero. A migração da paleta reaproveita cores antigas para os novos principais, preservando qualquer cor principal já escolhida.

Subgêneros no detalhe são tiles centralizados de 28 dp, limitados a 110 dp de largura. No editor, são menores e preservam 48 dp de toque. Seu ritmo, resumo do histórico, datas e registros diários usam o mesmo preenchimento transparente e borda na cor do gênero. Datas da leitura fica dentro do histórico expansível em todos os livros. Abrir o histórico rola para ele; a capa fechada tem mais espaço, respeitando a altura disponível e fonte do aparelho.

Gesto horizontal no detalhe muda para o próximo/anterior livro na ordem filtrada da estante; fora dessa seleção usa a biblioteca completa. Não muda o livro atual nem encerra o cronômetro. Não circula entre último/primeiro. O gesto não captura o SeekBar de páginas e mantém a rolagem vertical. Páginas comuns são salvas sem recriar os controles; transições de conclusão atualizam a tela preservando sua posição.
