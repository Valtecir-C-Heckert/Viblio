# Viblio 0.4.4 — tamanho do livro no ícone do aplicativo

A alteração é só no desenho do livro dentro do ícone de abertura. O pacote,
a assinatura, o nome, a área externa, o fundo #151515 e os cantos permanecem.
As abas e os ícones dentro do aplicativo não mudam.

Na 0.4.3 a margem era absoluta e o bitmap nodpi tinha 1254×1254 pixels; a redução
nominal em dp não garantia proporção consistente nos tamanhos usados pelo launcher.
A partir do Android 8, o desenho usa margens proporcionais de 30% de cada lado,
ocupando aproximadamente 40% da largura do botão em qualquer tamanho de desenho.
O recurso legado Android 5–7 usa margem maior, preservando compatibilidade minSdk 21.
Não foi introduzido ícone adaptativo/alias nem mudança de cor ou formato externo.

## Verificação

- Build/lint release aprovados; versionCode 40, versionName 0.4.4 e certificado
  idêntico ao da 0.4.3. APK sem depuração.
- Comparação nativa dos drawables reais empacotados na versão anterior e na nova,
  desenhados em 108, 216 e 324 pixels. Verifica redução do livro, proporção constante
  e igualdade dos pixels de fundo fora do desenho. As imagens comparadas são do
  recurso do aplicativo, sem dados pessoais. A comparação passou em emulador
  Android API 28 contra os recursos do APK 0.4.3 publicado.

Somente APK. A API 0.4.2 permanece compatível e não precisa de atualização.
