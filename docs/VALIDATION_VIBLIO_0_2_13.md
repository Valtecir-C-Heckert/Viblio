# Viblio 0.2.13 — Amigos, formas retas e cor da leitura atual

## Resultado e compatibilidade

- Viblio `br.com.viblio`, 0.2.13 / versionCode 25, release não depurável. Certificado SHA-256 mantido: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`.
- API do pacote 0.2.13 (`api_version: 5`, `friends_api: 1`), migração nova `007_friends.sql`. Migrações 001–006 comparadas com o ZIP publicado 0.2.9: conteúdo idêntico. Dados, sessões, catálogo, snapshots, Compose e volumes mantidos.
- Compilação dos arquivos compartilhados na Central aprovada. Estilo reto e cor dinâmica limitados ao pacote Viblio; não há APK novo da Central.
- Nenhuma mudança no esquema SQLite ou na sincronização de livros. Não precisa importar JSON. Nenhuma permissão ou dependência nova.

## Amigos

Navegação inferior com Livro atual, Estante, Pesquisa, Estatísticas e Amigos. Reaproveita o ícone anterior de Conta. Conta é a última opção dos menus reais da Estante e do livro, com retorno à biblioteca.

Pedidos por nome de usuário exato, aceite/recusa de recebidos, cancelamento de enviados, lista paginada de amigos e remoção da amizade. Capa pessoal do livro atual ao lado do nome. Consulta da estante com busca, filtros de tipo, páginas, progresso e volumes de coleções. Não escreve na biblioteca local nem permite editar a do amigo. Dados mostrados são os da última sincronização daquele amigo; conexão e conta são necessárias. Não há envio de e-mail ou push nesta implementação.

API verifica sessão e amizade aceita em cada consulta e em cada miniatura, inclusive antes de retornar conteúdo do cache. Nenhuma autorização implícita por convite, ser administrador ou conhecer UUID. Projeção permite apenas metadados de livros, progresso e capas: não retorna e-mail, notas, avaliação pessoal, histórico detalhado, snapshots ou configurações. Arquivados e coleções removidas ficam excluídos. URLs de capas de revisões antigas são recusadas. Remoção revoga novas leituras nos dois sentidos.

Par de UUIDs único e travas ordenadas impedem pedidos duplicados e criação concorrente oposta. Até 200 amizades e 100 pedidos pendentes por conta. Paginação de 30 amigos/40 itens de estante, campos/consultas limitados e revisão conferida ao continuar uma lista. Rate limiting autenticado separado, sem confiar em cabeçalhos de IP do cliente. Miniaturas JPEG privadas até 160×240, 96 KiB, sem metadados de imagem, com no-store. Cache do servidor e concorrência limitados; no cliente, só memória e limpeza ao sair/trocar conta/erro de consulta. Cabeçalhos de autorização nunca seguem para URLs externas ou redirecionamentos.

## Aparência

Fundo escuro neutro; controles e cartões com raios de até 1 dp, abas sublinhadas e títulos principais mais discretos. Mantém dimensões/organização das estantes, materiais das lombadas e madeira. No Livro atual, preserva capa, progresso, timer, sessões e ações; painéis ficam mais retos, com linhas finas. Não é uma cópia pixel a pixel das imagens: mantém estrutura, fontes do Android e dados reais do aplicativo.

Cor dominante da capa do **próprio livro atual** aplicada aos destaques de navegação, linhas, números, seleção e botões. Extração local fora da thread de UI, com invalidação ao trocar livro/capa/perfil. Sem capa útil, dourado neutro. Cor ajustada em luminosidade para contraste de texto de pelo menos 4,5:1 nos controles escuros. Livro atual de um amigo não muda o tema pessoal. Respostas atrasadas de outro perfil não aplicam o tema. Renderizações pendentes ficam suspensas com a atividade fora de tela, preservando o encerramento da consulta de Amigos.

## Verificação executada

```sh
ANDROID_HOME=/workspace/toolchains/android-sdk \
ANDROID_USER_HOME=/workspace/toolchains/android-user \
GRADLE_USER_HOME=/workspace/toolchains/gradle-home \
/workspace/toolchains/gradle-8.9/bin/gradle \
  :viblio:assembleRelease :viblio:assembleDebugAndroidTest \
  :viblio:lintRelease :viblio:testDebugUnitTest :app:compileDebugKotlin
```

Resultado: build aprovado, **129 testes unitários aprovados**, lint sem erros e Kotlin da Central compilado. Teste novo de contraste do destaque da leitura em superfícies escuras; regressões da biblioteca, ISBN, cores e períodos mantidas.

```sh
DOCKER_CONFIG=/workspace/toolchains/viblio-docker-config \
VIBLIO_TEST_PYTHON=/workspace/toolchains/viblio-api-venv/bin/python \
./viblio-api/scripts/test-api.sh
```

Resultado: **65 testes e 20 subtestes aprovados**, em PostgreSQL 17 descartável `viblio_test`, sem dados pessoais. Novos casos: aceite obrigatório, solicitante não aceita o próprio pedido, concorrência oposta, recusa/cancelamento, duplicação idempotente, inexistentes e limites de entrada, limites de taxa, leitura sem permissão, ausência de campos privados, revogação após aquecer cache, revisões antigas, arquivos/coleções removidos, paginação, busca e fallback da capa da coleção. Autenticação, snapshots, conflitos, segurança e atualizador também passaram.

```sh
VIBLIO_TEST_APK=viblio/build/outputs/apk/release/viblio-release.apk \
VIBLIO_RESET_EMULATOR_DATA=1 ANDROID_HOME=/workspace/toolchains/android-sdk \
./viblio/scripts/validate-ui.sh emulator-5554
```

Resultado: **PASS**, em API 28 descartável com o APK de release. Navegação nova; Conta como última opção dos menus reais; pedido/aceite/listagem/remoção; capa autenticada ao lado do amigo; estante e volumes em consulta; repositório pessoal preservado; URL externa recusada. Interface de Amigos usa uma fixture HTTP autenticada em localhost; nome inexistente mostra o erro correto, sem confundir um HTTP 404 de cadastro com API desatualizada. API real foi verificada separadamente nos testes PostgreSQL; não foi executado fluxo com contas ou servidor de produção.

Teste nativo de tema verificou troca entre dois livros/capas (vermelho/azul), números de Estatísticas e controles de Pesquisa acompanhando o destaque, timer/botão de leitura na cor atual, cantos de até 1 dp, preservação do progresso e retorno ao dourado sem livro atual. Demais regressões nativas de capas, histórico, organização, perfis e scanner passaram.

## Capturas do desenho nativo

Dados, capas sólidas e valores são **fixtures ilustrativas**, não bibliotecas pessoais:

- [Estante e controles retos](images/viblio-0.2.13-shelf.png)
- [Estatísticas com dourado neutro](images/viblio-0.2.13-statistics.png)
- [Destaque vermelho](images/viblio-0.2.13-statistics-red.png) / [destaque azul](images/viblio-0.2.13-statistics-blue.png)
- [Livro atual mais quadrado](images/viblio-0.2.13-current.png)
- [Pesquisa](images/viblio-0.2.13-search.png)
- [Amigos e capa atual](images/viblio-0.2.13-friends.png) / [estante em consulta](images/viblio-0.2.13-friend-shelf.png)

## Implantação

Não foi alterado o servidor do usuário, DNS ou túnel. **Atualize a API antes do APK**, seguindo [UPDATE_0_2_13.md](../viblio-api/UPDATE_0_2_13.md): localizar ZIP no Nextcloud, verificar checksum, backup de banco/.env/capas/snapshots, confirmar arquivo da migração e usar o Compose existente. Preserva `/opt/viblio/compose.yaml`; não roda configure.py nem down -v. Checksums em downloads/SHA256SUMS.

Amigos usa o mesmo `https://api.viblio.com.br`; não precisa abrir outra porta ou recriar contas. O amigo também precisa do APK atualizado para aceitar o pedido. Confira aparência, toque e sincronização entre os celulares depois da implantação, com as contas reais.
