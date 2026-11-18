# Viblio 0.2.11 — lombadas e períodos

## Alterações

- APK Viblio `br.com.viblio`, versão 0.2.11 / versionCode 23. Sem novo pacote da Central ou alteração de API/migração.
- Lombadas por faixas de 100 páginas: 1–100 = 28 dp; 101–200 = 32 dp; 201–300 = 36 dp; 901–1.000 = 64 dp; 1.901–2.000 = 104 dp. A pinça escala essas medidas. Valores extremos têm limite defensivo e a largura nunca ultrapassa a linha. Não são medidas físicas de espessura.
- Sem páginas conhecidas: 34 dp; coleção: 52 dp, mantendo a representação conjunta dos volumes.
- Título completo em layout de texto com quebra de linha e ajuste de fonte ao comprimento e à largura disponíveis. Não aplica reticências. Títulos muito extensos em lombadas finas terão fonte menor; o título também continua disponível na descrição de acessibilidade e na tela do livro. Layout calculado no redimensionamento, sem repetir a medição ao mudar a cor da capa.
- Texto preto ou branco conforme contraste com a cor real do fundo. Área do título uniforme, sem o sombreado que anteriormente reduzia o contraste. Sombra e brilho restritos às bordas. Contraste mínimo de 4,5:1 para o texto.
- Ícone ao lado de ⋮ alterna diretamente Capas/Lombadas e mostra o modo atual. Descrição acessível indica a ação seguinte. Mantém a preferência por tipo, os filtros e a organização; fica desativado durante a edição da ordem.
- Seleção Dia/Semana/Mês/Ano com abas sublinhadas e cores de Estatísticas; navegação de período em verde e título serifado, sem botões arredondados na seleção. Cálculo dos períodos preservado.
- Não adiciona permissões, dependências, uploads ou rotas na API. Nenhuma operação no servidor de produção.

## Verificação

SDK em `/workspace/toolchains/android-sdk`, `ANDROID_USER_HOME=/workspace/toolchains/android-user`, `GRADLE_USER_HOME=/workspace/toolchains/gradle-home`:

```sh
/workspace/toolchains/gradle-8.9/bin/gradle \
  :viblio:assembleRelease :viblio:assembleDebugAndroidTest \
  :viblio:lintRelease :viblio:testDebugUnitTest :app:compileDebugKotlin
```

Resultado: build aprovado, **126 testes unitários aprovados**, lint de release sem erros e Kotlin da Central compilado. Testes novos cobrem fronteiras 100/101/200/201 páginas, diferença entre 100/1.000/2.000 páginas, totais ausentes/extremos e contraste em 4.096 cores.

```sh
VIBLIO_TEST_APK=viblio/build/outputs/apk/release/viblio-release.apk \
VIBLIO_RESET_EMULATOR_DATA=1 ANDROID_HOME=/workspace/toolchains/android-sdk \
./viblio/scripts/validate-ui.sh emulator-5554
```

Resultado: **PASS**, em emulador API 28 descartável com APK de release e dados sintéticos. Verificados: títulos completos sem reticências e dentro da largura da lombada; fonte reduzida para título extenso; lombadas de 100, 1.000 e 2.000 páginas com larguras crescentes e sem espaços; alternância pelo ícone nas duas direções, preferência salva e filtro preservado; totais de Dia/Semana/Mês/Ano mantidos; regressões da navegação, histórico, perfis, catálogo e biblioteca.

Capturas do desenho nativo com fixtures a 360 dp:

- [Lombadas e título extenso](images/viblio-0.2.11-spines.png)
- [Seleção de períodos](images/viblio-0.2.11-periods.png)

APK não depurável, com certificado SHA-256 preservado: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`.

## Instalação

Instale o APK por cima do Viblio existente, sem desinstalar. Usa a API 0.2.9 atual, sem ZIP novo ou ação no servidor. SHA-256 do APK em `downloads/SHA256SUMS`. Download autenticado no GitHub em Releases → Viblio 0.2.11 → anexo APK.
