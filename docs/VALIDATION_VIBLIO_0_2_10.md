# Viblio 0.2.10 — ISBN, lombadas e estatísticas

## Escopo

- Somente Viblio, `br.com.viblio`, versão 0.2.10 / versionCode 22. Central compila os arquivos compartilhados, sem APK novo da Central.
- API 0.2.9 mantida: não há ZIP, migração nem alteração do banco do servidor nesta versão.
- ISBN na Pesquisa e no formulário de adicionar livro. ZXing core 3.5.3 empacotado; reconhece EAN-13 e códigos lineares com ISBN válido, rejeita códigos de produto/URLs e limita os quadros processados. A pesquisa tenta o ISBN-10/13 equivalente somente se a primeira consulta estiver vazia.
- Um resultado da mesma categoria preenche os dados disponíveis. Várias edições exigem escolha. Nada é salvo antes de confirmar o cadastro; edição de outra categoria orienta usar a Pesquisa. Sem resultado/conexão, o cadastro manual continua disponível.
- Câmera opcional, permissão solicitada somente ao abrir o leitor. Activity interna não exportada; resultado revalidado e vinculado ao perfil e formulário que abriram o leitor. Imagens não são persistidas nem enviadas; somente o ISBN é usado na consulta autenticada existente. Captura termina ao sair/pausar; lanterna é liberada junto com a câmera. Decodificação em um único worker, com resolução e frequência limitadas.
- Menu da Estante → Visualização da estante → Capas/Lombadas. Preferência por tipo; filtros e ordem continuam ativos. Lombadas desenhadas individualmente, lado a lado sem espaços, com título vertical e cor dominante da capa. Coleções usam a capa da coleção e mostram volumes possuídos. Segurar abre o organizador por capas existente. A pinça ajusta o tamanho das lombadas sem alterar o número de capas por linha.
- Estatísticas seguem a referência: fundo verde escuro, títulos serifados, abas com linha, páginas em destaque, faixa da Estante e progresso com divisões. Gêneros e subgêneros expansíveis mantêm nomes, barras e contagens legíveis. Calendário no cabeçalho abre Dia/Semana/Mês/Ano e o histórico mensal já existente.
- Histórico compacto: horário e duração dividem uma linha sem sobreposição, páginas embaixo e inclusão de datas no cabeçalho. Bordas internas usam a cor da capa com contraste ajustado, sem alterar os dados de leitura.

## Verificação

Comando usado, com SDK/Gradle locais:

```sh
./gradle :viblio:assembleRelease :viblio:assembleDebugAndroidTest \
  :viblio:lintRelease :viblio:testDebugUnitTest :app:compileDebugKotlin
```

Neste ambiente, o executável Gradle foi `/workspace/toolchains/gradle-8.9/bin/gradle`, com `ANDROID_HOME=/workspace/toolchains/android-sdk`, `ANDROID_USER_HOME=/workspace/toolchains/android-user` e `GRADLE_USER_HOME=/workspace/toolchains/gradle-home`.

- 123 testes unitários aprovados: ISBN/checksum, equivalência 10/13, códigos gerados reconhecidos em duas orientações, quadros inválidos, códigos de produto e imagem vazia; contraste em superfícies claras/escuras e regressões da biblioteca.
- Lint de release: 0 erros; avisos registrados no relatório Android.
- Compilação Kotlin da Central aprovada.
- APK não depurável. Certificado SHA-256 preservado: `5953e6c8ec5145afca3d40fb202e54522418b1599015dda43361e08b7757ede0`.

A validação nativa usa apenas um emulador API 28 descartável, dados sintéticos e o APK de release:

```sh
VIBLIO_TEST_APK=viblio/build/outputs/apk/release/viblio-release.apk \
VIBLIO_RESET_EMULATOR_DATA=1 ANDROID_HOME=/workspace/toolchains/android-sdk \
./viblio/scripts/validate-ui.sh emulator-5554
```

Resultado: **PASS**. Verificados navegação, perfis isolados, histórico/dias recolhíveis, horário e duração sem sobreposição a 320 dp, filtros e totais das estatísticas, gêneros/subgêneros expansíveis, períodos, organização manual, pesquisa por ISBN e fallback, preenchimento sem salvar, três lombadas sem espaços e abertura de livro, câmera emulada/permissão/cancelamento e regressões da biblioteca. O teste da câmera não confirmou leitura óptica de um livro real. Não foram feitos pedidos autenticados à API de produção nem alterações no servidor.

Capturas do desenho nativo com dados sintéticos a 360 dp (os números e títulos são fixtures):

- [Estatísticas](images/viblio-0.2.10-statistics.png)
- [Lombadas](images/viblio-0.2.10-spines.png)

## Limites e instalação

Foco, iluminação, lanterna e leitura de livros físicos ainda precisam de confirmação no celular. Testes com imagens geradas e câmera emulada não substituem essa verificação. Busca de metadados depende da conexão e da existência do ISBN no catálogo. Sem integração MangaDex ou KOReader.

Instale o APK por cima do Viblio existente, sem desinstalar, preservando contas e estantes. A API 0.2.9 deve continuar em execução; não é necessário executar migrações ou reconstruir o servidor por causa deste APK.

O anexo é disponibilizado em Releases → Viblio 0.2.10. O arquivo `downloads/SHA256SUMS` registra seu SHA-256.
