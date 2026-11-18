# Estante e estatísticas — alterações sem novo APK

A moldura da estante usa FrameLayout com borda de progresso no foreground, desenhada depois da capa. Preenchimento separado, 4 dp de espaço interno e capa com cantos arredondados/clipToOutline. Mantém cores e progresso existentes e compatibilidade com Android API 21.

Estatísticas usam padding de fonte para preservar ascendentes/descendentes, valores com até duas linhas e tamanho menor. Mantêm dois medidores por linha. Distribuições têm títulos/textos de altura automática e contagens sem largura fixa. Sem acesso ao Redmi nem emulador funcional para comprovar visualmente o recorte reportado.

Nova seção Subgêneros mais presentes: conta até três tags de cada livro, cada classificação apenas uma vez por livro; agrupa nomes equivalentes (maiúsculas/acentos/espaços), ignora vazios, aceita subgênero único legado. Ordena por quantidade decrescente e nome; exibe cinco e permite expandir. Não altera dados persistidos.

compileDebugKotlin, testDebugUnitTest e lintDebug passaram em /tmp/central-shelf-stats-final.log. 112 testes JVM, zero falhas/erros/ignorados. Lint sem erros. Uma incompatibilidade NewApi detectada na primeira execução foi corrigida com getOrPut. Não foi executado assembleDebug nem alterado o APK de downloads; versão permanece 0.4.34. Hash do APK preservado: d1ab3e9d76f4ca39ce2b8f85cb99f95c7b3b7858a0d10aa218b5ed4ea7189046.

Esta etapa não modifica a reprodução de câmera. Na 0.4.34, o ajuste HLS é compartilhado entre perfis quando Vídeo compatível está desligado. O usuário confirmou substream funcionando na Central; não se deve afirmar ausência de impacto desse ajuste no main/substream sem teste.
