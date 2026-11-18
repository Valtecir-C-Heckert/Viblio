# Viblio 0.2.12 — autor horizontal e materiais da estante

## Escopo e aparência

Viblio `br.com.viblio`, versão 0.2.12 / versionCode 24. Não há API, migração ou APK novo da Central.

- Autor na horizontal no topo: primeiro nome/iniciais na primeira linha, restante abaixo. Reduz a fonte para manter palavras inteiras; não deixa a última letra sozinha. Usa os metadados existentes, sem editar nomes no banco. Quando não há autor, não reserva um bloco vazio.
- Separador e título vertical no sentido do esboço aprovado. Títulos curtos têm mais destaque; títulos extensos continuam completos com ajuste de fonte/quebra de linha. Descrição acessível inclui título e autor.
- Textura e base de madeira extraídas em tempo de execução da imagem aprovada, empacotada localmente em `viblio/src/main/assets/shelf-materials.png`. A textura se adapta à cor da capa; cada fileira recebe a base de madeira com saliência lateral. Fundo verde escuro no modo Lombadas sem filtro de cor por gênero.
- A textura usa uma região de 120×160 sem letras; a madeira usa uma região de 973×48 sem silhuetas de livros. A imagem inteira é liberada após a primeira leitura; apenas os materiais pequenos ficam no cache do processo. Não há downloads de textura ou decodificação de imagem por quadro. Layouts de texto são recalculados apenas ao redimensionar; medição aceita imediatamente textos que já cabem.
- Preto/branco e a cor de fundo são escolhidos considerando as duas extremidades de luminosidade da textura, com contraste mínimo de 4,5:1. Sombreamento nas bordas evita reduzir o contraste da área das letras.
- Preserva largura por faixas de 100 páginas, zoom, filtros, ordem, alternância Capas/Lombadas, dados pessoais, capas e sessões. Não adiciona permissões, dependências, rotas de API ou alterações no servidor.

**Não é uma reprodução idêntica pixel a pixel.** A textura foi amostrada e suavizada/repetida sobre as cores reais das capas; a madeira foi redimensionada. Tipografia Android, nomes, larguras, zoom e densidade variam. O cabeçalho e a navegação existentes foram mantidos. Essa limitação foi comunicada ao usuário durante a implementação.

Fonte dos materiais: a imagem aprovada e o arquivo empacotado têm o mesmo SHA-256: `d5990ff88bada398ead779d89942ec829cdd1be978ecf7d54386bbfd1b04e98b`.

## Verificação

SDK `/workspace/toolchains/android-sdk`, `ANDROID_USER_HOME=/workspace/toolchains/android-user`, `GRADLE_USER_HOME=/workspace/toolchains/gradle-home`:

```sh
/workspace/toolchains/gradle-8.9/bin/gradle \
  :viblio:assembleRelease :viblio:assembleDebugAndroidTest \
  :viblio:lintRelease :viblio:testDebugUnitTest :app:compileDebugKotlin
```

Build aprovado; 128 testes unitários aprovados. Novos casos verificam nomes compostos/iniciais, campos vazios e contraste nos extremos da textura em 4.096 cores. Lint de release sem erros; Kotlin da Central compilado.

```sh
VIBLIO_TEST_APK=viblio/build/outputs/apk/release/viblio-release.apk \
VIBLIO_RESET_EMULATOR_DATA=1 ANDROID_HOME=/workspace/toolchains/android-sdk \
./viblio/scripts/validate-ui.sh emulator-5554
```

Resultado: **PASS** em emulador API 28 com dados descartáveis e APK de release. Verificados: asset empacotado, autores completos em duas linhas no exemplo aprovado, palavras sem quebra da última letra, título completo, medidas crescentes das lombadas, madeira por fileira, filtros/ordem preservados na alternância e regressões da biblioteca. A primeira captura revelou uma quebra indevida em “Lispector”; o ajuste foi corrigido e a suíte passou após a correção. Nenhuma API de produção ou biblioteca pessoal foi alterada.

Captura do desenho nativo do APK de release, com os quatro livros públicos do esboço e dados sintéticos (sem alterações em bibliotecas pessoais): [resultado real](images/viblio-0.2.12-materials.png).

Certificado SHA-256 preservado: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`. APK não depurável.

## Instalação

Instale por cima do Viblio existente, sem desinstalar. Usa a API 0.2.9 atual, sem ZIP novo nem operação no servidor. Checksum do APK em `downloads/SHA256SUMS`. GitHub autenticado → Releases → Viblio 0.2.12 → anexo APK.
