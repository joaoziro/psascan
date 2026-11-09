plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "br.psascan.painel"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.psascan.painel"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.0"
    }

    // Chave de release opcional, por variáveis de ambiente (nenhum segredo no repositório):
    //   PSASCAN_KEYSTORE (caminho do .jks), PSASCAN_KEYSTORE_SENHA, PSASCAN_KEY_ALIAS, PSASCAN_KEY_SENHA
    // Sem elas, o release é assinado com a chave de debug da máquina (dá para instalar no próprio celular).
    val keystore = System.getenv("PSASCAN_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("publicacao") {
            storeFile = keystore
            storePassword = System.getenv("PSASCAN_KEYSTORE_SENHA")
            keyAlias = System.getenv("PSASCAN_KEY_ALIAS") ?: "psascan"
            keyPassword = System.getenv("PSASCAN_KEY_SENHA") ?: System.getenv("PSASCAN_KEYSTORE_SENHA")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("publicacao") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

// O perfil da central é copiado para os assets e os vetores de teste são calculados pela versão Python.
val gerarDados by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    commandLine("python3", "gerar_dados.py")
    // perfil, temas, layouts e catálogo de widgets: qualquer mudança regera os assets
    inputs.files(fileTree("../../psascan") { include("*.py") }, fileTree("../../perfis"), fileTree("../../temas"),
        fileTree("../../layouts"), file("../../psascan/static/widgets/catalogo.json"), file("../gerar_dados.py"))
    outputs.files(file("src/main/assets/perfil.json"), file("src/test/resources/vetores.json"))
    outputs.dirs(file("src/main/assets/temas"), file("src/main/assets/layouts"), file("src/main/assets/widgets"))
}
tasks.named("preBuild") { dependsOn(gerarDados) }

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
