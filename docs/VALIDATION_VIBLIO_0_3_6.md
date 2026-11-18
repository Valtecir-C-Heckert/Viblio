# Viblio 0.3.6 — zoom local, progresso sincronizado

## Problema e resultado

Uma divergência em `/appearance/SHELF_SPINE_ZOOM` gerava HTTP 409 e o marcador local `remote_changed` impedia novas tentativas automáticas. Assim, um zoom de 0,75 no aparelho contra 1,15 no servidor podia impedir o envio das páginas, mesmo sem sessão de leitura aberta.

Agora esse ajuste fica somente no aparelho/perfil. AccountLibrary o exclui da captura e do hash compartilhado, preservando o valor local ao aplicar snapshots antigos. Não exclui outras preferências nem modifica o protocolo de metas. Uma sessão nova reavalia o marcador legado. Conflitos reais confirmados continuam pausando a automação naquele perfil, inclusive durante a escolha manual.

LibrarySyncPolicy centraliza o envio e remove o zoom legado do payload. Diante de conflito exclusivamente nesse campo, pede sua remoção com `resolutions` e `against_revision`, usando a revisão indicada pelo servidor. São no máximo três tentativas, com request_id novo por tentativa. Se o servidor mudar, a tentativa automática só pode descartar a escolha de zoom e verificar novamente. Resoluções de páginas ou outros dados autorizadas pelo usuário não são reaplicadas a uma revisão nova.

Se houver zoom junto com conflito real, o diálogo mostra somente os conflitos reais. A escolha enviada inclui a limpeza do zoom. Metas, ordenação manual, títulos, páginas e qualquer outro caminho não recebem resolução automática. A API existente já suporta esse mecanismo: nenhuma mudança de runtime, migração ou ZIP de servidor.

Ao confirmar igualdade dos dados, o marcador antigo é limpo. O estado de restauração histórica continua protegido; conferir um backup restaurado exige publicá-lo, em vez de declarar incorretamente que já foi enviado. Nenhuma mudança visual ou em fundos, contadores, capas ou sessões.

## Verificação

- Viblio release, APK de instrumentação, lint release e testes unitários.
- 155 testes unitários, incluindo dez casos da política: remoção exata, payload original preservado, metas/ordenação mantidas, revisão vinculada, UUIDs diferentes, conflito misto, caminhos semelhantes, nova divergência de página, limite de tentativas e escolha manual não reaplicada após mudança do servidor.
- API/PostgreSQL descartável: 69 testes e 20 subtestes. Simula cliente antigo, divergência de zoom, envio da página 61 e leitura pelo amigo; um conflito de página 40 versus 61 continua retornando 409, sem alteração do servidor.
- Android API 28: conflito legado com marcador antigo recuperado automaticamente, página enviada e zoom 0,75 preservado. Caso misto mantém 72 local e 80 remoto até o usuário escolher; diálogo tem uma decisão de página, sem zoom; Usar servidor aplica 80 e mantém 0,75 local. Mantém testes de fila ocupada e retorno ao app.
- Perfis/restauração: dois perfis mantêm 0,75 e 1,25 ao receber snapshot legado com 1,15. Zoom não altera a igualdade compartilhada, não sai na captura e não vaza entre perfis.
- Suíte nativa geral para biblioteca, navegação, leitura, metas, amigos e isolamento. Apenas emulador e PostgreSQL `viblio_test`; nenhum servidor ou aparelho pessoal acessado.

APK versionCode 35, não depurável e certificado preservado. Instalar por cima e abrir conectado. Não exige atualizar a API. Em aparelho com conflito real de páginas/dados ou restauração histórica pendente, a decisão/publicação manual continua necessária.

SHA-256 do APK: `353a0ba49cd0997f46b23944dfe74b7e7aeb2293ca4bcd047512ff593d164c00`.
