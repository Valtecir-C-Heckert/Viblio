# Viblio 0.2.5 — miniatura do Livro atual

Livro atual na navegação inferior usa a capa local do livro atual em miniatura vertical de 20 × 28 dp, com cantos discretamente arredondados. Mantém o rótulo, a área de toque e o destaque da aba selecionada. Sem capa local, mostra o ícone da Estante em cinza.

A leitura e decodificação usam o cache limitado de BookImages em executor de uma thread, fora da interface. O resultado só é aplicado à imagem ainda anexada e ao mesmo painel/perfil; tarefas encerram com a Activity. Não há nova busca na rede ou alteração de capas, bibliotecas, contas e API. A Central apenas compartilha o método de miniatura, sem alteração visual ou novo APK.

Verificação: assembleRelease e lintRelease do Viblio, compileDebugKotlin da Central. Certificado de instalação preservado, versão 0.2.5/code 17 e depuração desativada. Sem nova execução da instrumentação completa para esta alteração de apresentação; a aparência final no telefone ainda precisa ser conferida.

Instalar por cima do Viblio existente, sem desinstalar. API 0.2.3 mantida, sem novo ZIP ou migração. Download pelo anexo APK em Releases → Viblio 0.2.5 no GitHub autenticado. SHA-256 publicado em downloads/SHA256SUMS.
