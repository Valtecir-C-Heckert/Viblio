# Viblio 0.1.8 — Capas alternativas

## Comportamento

O mesmo ID de catálogo pode ter uma capa principal e alternativas. `valteca.capa_url` permanece preservada; a tabela aditiva `viblio_book_covers` liga uploads ao livro. A migração 003 recupera imagens anteriormente enviadas pela API, deduplica relações pela impressão da imagem convertida e pode ser repetida. Arquivos nunca enviados continuam apenas no aparelho. ISBNs diferentes podem representar edições distintas.

Pesquisa → Ver capas mostra a galeria e permite escolher antes de Adicionar à Estante. No editor de itens vinculados, Capas do catálogo baixa a escolha e mantém os demais campos; Salvar confirma a alteração pessoal. O editor de coleção usa o vínculo do volume 1, sua capa principal. A biblioteca, progresso, notas e histórico não são sincronizados nem substituídos. Conta continua desativada. A Central não recebe um novo APK.

Arquivos do celular enviados para um item vinculado são associados ao mesmo ID sem duplicar o registro. Novos volumes sem vínculo exigem numeração; a resolução distingue tipo e volume para não misturar imagens de volumes diferentes. Galerias listam até 100 alternativas por registro; paginação além desse limite ainda não está implementada. Uma imagem repetida pode ocupar outro arquivo de upload, mas é listada uma vez por livro. Não há limpeza de arquivos antigos/orfãos nesta edição.

## Compatibilidade e instalação

APK 0.1.8, versionCode 9, pacote `br.com.viblio`. Instalar por cima, sem desinstalar. A API precisa da migração 003 e das fontes atualizadas: [guia](../viblio-api/UPDATE_0_1_8.md). Preservar `.env`, nome de projeto Compose e volume. As fontes/ZIP não incluem chave de instalação, credenciais, banco ou biblioteca pessoal. Busca e cadastro de livros comuns mantêm compatibilidade; com API antiga a galeria pede atualização e envios de volumes aguardam uma API compatível. Repetições de pedidos antigos preservam o fingerprint anterior para idempotência.

## Evidência de validação

- 99 testes unitários do Viblio passaram: zero falhas, erros ou ignorados. Incluem contrato HTTP da galeria, identidade, ID bigint acima de 2^53, autenticação e versão da API, além dos testes existentes da coleção e fila.
- 13 testes da API passaram em PostgreSQL 17 isolado. Incluem migração repetida/backfill, principal intacta, duas alternativas, imagem repetida, autenticação, URLs de arquivos, IDs bigint, tipos e volumes separados. Uma advertência de depreciação da dependência de testes; nenhuma falha.
- Build do APK e instrumentação, lint e compilação Kotlin da Central passaram.
- Instrumentação em emulador Android API 28 passou: galeria com duas escolhas, seleção da segunda e fechamento; navegação; pesquisa/cache de miniaturas; organização manual com eventos de arraste e salvamento; SQLite, vínculo, edições, sessões, backup e fila. Fixtures próprias removidas e preferências restauradas. A política temporária do harness para DragEvent foi restaurada para `null`; o produto não depende dessa configuração.
- A seleção completa contra a API do servidor pessoal e a apresentação no telefone ainda precisam de conferência após a atualização. O servidor Debian do usuário não foi acessado nem alterado.
- ZIP conferido: migração 003 e guia presentes, nenhum `.env`, integridade CRC válida. Wheels Linux x86_64/Python 3.12 reaproveitadas; dependências não mudaram.

## Artefatos

APK: 2.497.162 bytes; minSdk 21, targetSdk 35, somente permissão Internet. Assinatura preservada: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`. SHA-256: `d99f6aa9e58cf08d6101a1a91934c746958c31e066abbf63a843c1fd14aa05f1`.

API ZIP: 15.958.284 bytes. SHA-256: `3bc4ca39cd09fe9522010a49094f436fb63cf067ad932d4be5a446d1f497f1ec`.
