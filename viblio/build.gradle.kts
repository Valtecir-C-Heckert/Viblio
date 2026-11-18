import java.net.URI

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
// Public origin selected by the owner; deployment must pass the external-access checks before release.
val publicApiUrl=providers.gradleProperty("viblioPublicApiUrl").orElse(providers.environmentVariable("VIBLIO_PUBLIC_API_URL")).orElse("https://api.viblio.com.br").get().trim().trimEnd('/')
if(publicApiUrl.isNotBlank()) {
    val origin=URI(publicApiUrl)
    require(origin.scheme=="https" && origin.host?.contains('.')==true && origin.userInfo==null && origin.query==null && origin.fragment==null && origin.path.orEmpty().isEmpty() && origin.port in listOf(-1,443)) {"Viblio public API must be an HTTPS origin without credentials, path, query or fragment."}
    val host=origin.host.lowercase()
    require(host.length<=253 && host.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) } && host.substringAfterLast('.').any {it in 'a'..'z'} && !host.endsWith(".local") && !host.endsWith(".localhost") && !host.endsWith(".ts.net")) {"Use the verified public Viblio domain, without local IPs or Tailscale."}
}
android {
    namespace = "br.com.centralcasa"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "br.com.viblio"; minSdk = 21; targetSdk = 35; versionCode = 41; versionName = "0.4.5"; testInstrumentationRunner = "br.com.viblio.CatalogInstrumentation" }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            // Preserve the existing installation certificate for this private beta.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("main").java.srcDir(layout.buildDirectory.dir("generated/server"))
    }
}
dependencies { implementation("com.google.zxing:core:3.5.3"); implementation("com.squareup.okhttp3:okhttp:4.12.0"); implementation("com.google.code.gson:gson:2.11.0"); testImplementation("junit:junit:4.13.2") }

val generateServerConfig by tasks.registering {
    inputs.property("publicApiUrl",publicApiUrl)
    val output=layout.buildDirectory.file("generated/server/br/com/viblio/ViblioServer.kt")
    outputs.file(output)
    doLast {
        output.get().asFile.apply {parentFile.mkdirs();writeText("package br.com.viblio\nobject ViblioServer { const val PUBLIC_API_URL = \"$publicApiUrl\" }\n")}
    }
}
tasks.named("preBuild") { dependsOn(generateServerConfig) }
