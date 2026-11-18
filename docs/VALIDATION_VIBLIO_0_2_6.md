# Viblio 0.2.6 — estatísticas e histórico diário

APK release `br.com.viblio`, versão 0.2.6/code 18, mantendo o certificado anterior e depuração desativada. API 0.2.3 preservada; sem migrações, pacote de API ou APK novo da Central.

## Interface

Estatísticas seguem o esboço aprovado: seletor Todos/Livros/Mangás/Quadrinhos visível no topo, com preferência persistente e seleção realçada; card Seu ritmo com páginas lidas como métrica principal, duração e páginas por hora; resumo da Estante; barra de concluídos/em andamento/não iniciados; gêneros e subgêneros com três entradas iniciais e expansão. Removidos os antigos seis tiles e a frase “Sua leitura em números” no Viblio. A Central conserva a apresentação anterior por sinalizador desativado.

Páginas lidas somam o progresso atual de exemplares ativos com páginas conhecidas, incluindo o progresso informado sem cronômetro. Não são uma contagem cumulativa de releituras. Tempo soma apenas sessões salvas dos exemplares selecionados. Ritmo usa páginas e duração das sessões de exemplares com páginas conhecidas; não deriva a velocidade do total geral de páginas. Sem dados cronometrados, exibe traço. Volumes sem páginas ficam fora da contagem e do ritmo, com aviso discreto; não recebem estimativa.

Histórico de livros, mangás e quadrinhos mantém um único card externo. Cada dia mostra o total de páginas/tempo e suas sessões com horário inicial/final, duração e intervalo de páginas. Tocar no cabeçalho recolhe/expande o dia; editar datas continua disponível. Registros manuais sem sessão continuam identificados. Coleções identificam também o volume de cada sessão. Sem quantidade de páginas cadastrada, informa a ausência de páginas. Some o contador “sessão(ões)” no Viblio. Quatro dias inicialmente, carregando mais quatro por vez; até vinte sessões por dia inicialmente, com ação para expandir. Nenhum dado é descartado.

## Verificações

- Build assembleRelease, assembleDebugAndroidTest e lintRelease do Viblio; compileDebugKotlin da Central: sucesso.
- Testes nativos verificam páginas de 57 no conjunto de livros/HQs, 40 em Livros, 17 em Quadrinhos e zero em Mangás sem páginas cadastradas, seleção de filtro e ausência do subtítulo antigo.
- Histórico: cinco dias de sessões persistidas, intervalos de páginas e singular, um card sem fundos por dia, recolher/expandir sem perder dados, paginação de quatro para cinco dias e navegação Livro atual → Estante.
- Instrumentação completa contra o APK release em Android API 28 e PostgreSQL/API descartáveis: passou; também executa os fluxos existentes de capas/pesquisa, contas/convites/perfis, sincronização, notas, arquivo/restauração, inventário de volumes e persistência.
- O harness executou 48 testes Python da API/updater e 7 subtestes, todos passaram. Sem nova execução da suíte unitária de modelos: cálculos persistidos, banco e contratos da API não foram alterados.
- Não houve acesso ao servidor pessoal ou a telefones; não contém biblioteca pessoal, tokens ou credenciais.

A primeira execução identificou uma fixture inválida (volume sem coleção/páginas com progresso incompatível). O teste foi corrigido para usar uma coleção real e um volume sem páginas conforme as validações existentes; o produto não afrouxou nenhuma validação. A execução final completa passou. Conferência visual adicional da tela vazia no emulador confirmou filtros e cards legíveis.

Certificado SHA-256: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`. APK SHA-256: `2de278af3ef24d3f14a4662e971d85ef690dc1170fb51f1d1753159aa04dce0f`. APK distribuído idêntico ao usado no teste nativo final.

## Instalar

No GitHub autenticado, abrir Releases → Viblio 0.2.6 → anexo APK. Instalar sobre a versão existente, sem desinstalar. API 0.2.3 mantida, sem ação no servidor. SHA-256 em downloads/SHA256SUMS. A aparência e a legibilidade finais no aparelho do usuário ainda precisam ser conferidas.
