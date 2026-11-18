# Viblio 0.1.7 — Ordem principal, capas e navegação

## Comportamento

A Estante aceita organização manual de livros e títulos de mangás/quadrinhos/light novels. Segurar uma capa abre o modo de organização e permite arrastá-la; também é possível entrar por ⋮ → Organizar estante. Arrastar reposiciona o item e desloca os vizinhos, reutilizando os cards e bitmaps existentes. Há rolagem perto das extremidades, indicação do alvo, Salvar como principal e Cancelar. A pinça fica desativada durante a organização para não conflitar com o arraste.

Salvar limpa pesquisa/filtros/critérios e torna a ordem por IDs a visualização principal daquele tipo de coleção. A ordem sobrevive a renomear títulos, progresso, recriação da tela e reabertura. Itens removidos deixam de ocupar posições; novos títulos entram no fim. Filtros e ordenações continuam disponíveis temporariamente; ⋮ → Minha ordem · limpar filtros restaura a disposição salva. Cancelar não grava a nova ordem. A organização é salva nas preferências privadas do aparelho, por tipo; não faz parte do JSON antigo de backup da biblioteca e não sincroniza com o PostgreSQL.

A pinça do Viblio exige variação de 40% entre etapas, com uma mudança de densidade por etapa, mantendo atualização durante o movimento e a posição da lista. A Central conserva sua sensibilidade padrão de 20%; seu APK não é atualizado nesta entrega.

## Capas

Título e autor são pesquisados em paralelo. Resultados de título aparecem assim que chegam, sem esperar o autor. Resultados duplicados não reconstruem a lista nem reiniciam os downloads. Capas visíveis têm prioridade, com até três tarefas de imagem e sem enfileirar downloads de todas as edições fora da tela.

Miniaturas ficam em memória (8 MB) e no cache privado do Android (até 80 arquivos / 12 MB). A reutilização em disco permite reapresentar uma capa mesmo com a origem offline, sem substituir as capas completas da biblioteca. Limites de tamanho/dimensões, amostragem iniciada em 1 e ausência de credenciais nos requests de imagem continuam valendo. A primeira carga de uma URL depende da rede e da disponibilidade da origem; o cache é descartável e não é incorporado ao backup.

## Navegação

Cadastrar fica no topo da Pesquisa, ao lado do título, e permite escolher Livro, Mangá ou Quadrinho para abrir os editores existentes. Estatísticas sai do cabeçalho da Estante e entra no menu inferior, com as opções Geral / Livros / Mangás / Quadrinhos. Menu: Estante / Pesquisa / Estatísticas / Conta. Conta permanece apenas como prévia, com login desativado.

API 0.1.4 continua compatível. Não é necessário atualizar a API do servidor pessoal.

## Validação

- 97 testes unitários passaram, sem falhas, erros ou ignorados. Os novos testes cobrem movimento com deslocamento dos vizinhos, exclusões/IDs repetidos, novos títulos no fim, renomear sem perder posição, filtragem sem descartar a ordem salva e a sensibilidade maior da pinça com limite de uma etapa.
- Build do Viblio e do APK de instrumentação, lint e compilação Kotlin da Central passaram.
- Instrumentação Android API 28 passou: menu de Estatísticas e retorno à Estante; Cadastrar no topo da Pesquisa; resultado de título antes da resposta por autor; deduplicação; respostas antigas ignoradas; download/decodificação/exibição das capas sem credenciais; cache em memória e nova instância com origem offline usando a miniatura em disco.
- A checagem da organização usa biblioteca vazia no emulador, cria apenas fixtures com IDs próprios, despacha eventos Android de arraste/drop, confere a ordem dos cards, salva e reabre o painel, restaura estado, adiciona um título no fim e cancela uma nova organização. Remove suas fixtures e restaura as preferências ao terminar. Não é uma medição de fluidez do gesto no telefone.
- O script `viblio/scripts/validate-ui.sh` foi executado com sucesso e confere explicitamente o resultado PASS. Restringe-se a emulador API 28. Para construir o evento sintético no teste, altera temporariamente `hidden_api_policy_p_apps` e restaura o valor original em trap; a restauração foi conferida. O aplicativo utiliza APIs públicas de drag-and-drop e não depende dessa configuração. A primeira tentativa de teste falhou pela assinatura/política interna do DragEvent; o harness foi corrigido e as fixtures do emulador foram limpas. A execução final passou.
- Checks anteriores de SQLite, IDs bigint, edição, sessões/progresso/nota, backup e fila também passaram. A API do servidor não mudou e o PostgreSQL pessoal não foi acessado.

## Artefato

APK `br.com.viblio`, versão 0.1.7, versionCode 8, 2640883 bytes. Somente a permissão Internet; assinatura preservada: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`. SHA-256: `ad95d12b0b677f0a1ff3deac6fb409d887872ca46a5fb727eed681bae00c44c6`. Instalar por cima, sem desinstalar.
