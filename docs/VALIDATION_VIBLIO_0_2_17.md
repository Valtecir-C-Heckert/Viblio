# Viblio 0.2.17 — fundo da tela de leitura

Com um livro vermelho como leitura atual, abrir outro livro azul não deve herdar o fundo vermelho nem perder a capa ampliada ao fundo. A tela de leitura passa a usar o livro selecionado, preservando o mesmo desenhador de capa ampliada e escurecida usado anteriormente.

- Todos os livros, incluindo volumes e detalhes de coleções, mantêm sua própria capa ao fundo. Não depende de ser a leitura atual.
- Cor das barras de sistema e do fundo da navegação deriva da capa aberta. Sem capa válida, fundo neutro.
- Telas gerais permanecem na paleta do livro atual. Cards, textos, destaques e cores de progresso não foram alterados.
- Não troca o livro atual ao consultar outro. Sem mudança em dados, sincronização, conta, permissões ou API. Central mantém o comportamento anterior.

## Validação

- Build release e compilação Kotlin da Central aprovados; lint release sem erros (47 avisos preexistentes).
- Regressão nativa aprovada no APK final: abre um livro azul pela estante mantendo um vermelho como atual, confere os pixels azuis do fundo real e a cor das barras, confirma que a leitura atual e seu destaque global continuam vermelhos e retorna ao Livro atual.
- Cinco paletas, fallback neutro, cores de cartões/textos e conservação do progresso também passaram nos testes nativos do emulador API 28.
- Fluxo de Amigos e encerramento HTTPS aprovados durante a correção. Não houve mudança em API/banco; não foi necessário repetir testes de servidor.

## Instalação

APK release 0.2.17, versionCode 29, `br.com.viblio`, depuração desativada, mesmo certificado. Instalar por cima, sem desinstalar ou apagar dados. Nenhuma atualização adicional da API.

## Artefato

SHA-256 do APK: `388c33f0a9f6e0c98bcacea1665b667a59f47adacaaf766aa902070069cda6b0`. Certificado preservado: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`.
