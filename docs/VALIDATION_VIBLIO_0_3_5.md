# Viblio 0.3.5 — tempo total no livro

O card existente Seu ritmo destaca o total das durações das sessões do livro aberto. A média por página continua abaixo. O rótulo é Tempo total de leitura enquanto não concluído e Tempo para concluir quando concluído. Sem sessões, o valor é —, sem inventar uma duração zero. Não cria outro card.

O cálculo usa ReadingSummary e os registros já consultados por bookId. Não inclui intervalo de calendário, datas manuais ou rascunho ainda não salvo. Correções de página não criam tempo. Usa todas as sessões registradas, incluindo releituras; não cria ciclos novos de conclusão. Livros sem páginas conhecidas mantêm a interface de volumes existente.

Nenhuma mudança na API, banco, permissões, dependências, dados de sincronização ou projeção dos amigos. Totais são privados do perfil e aparecem na consulta do próprio livro. Central conserva a interface anterior e seu código compartilhado é compilado; nenhum APK da Central é publicado. Fundos, paletas, metas e sincronização de Amigos não foram alterados.

## Verificação

- Release Viblio e APK de instrumentação, lint release e compilação Kotlin da Central.
- Android: livro sem sessões → —; sessão de 25 min → 25 min; correção de páginas mantém 25 min; segunda sessão de 50 min até a última página → 1h 15min e rótulo Tempo para concluir. A soma persistida é 4.500.000 ms. Captura do card nativo.
- Testes somente no emulador, sem acesso ao servidor ou aparelho pessoal.
- Release não depurável, versionCode 34, assinatura preservada. Atualizar por cima, sem desinstalar. Não exige atualização da API.

[Captura nativa do card](images/viblio-0.3.5-reading-total.png). SHA-256 do APK: `ee5d87b946dd10261f8a641f6d944ee2cb4640cbedc88cbc7ff3d3867b456657`.
