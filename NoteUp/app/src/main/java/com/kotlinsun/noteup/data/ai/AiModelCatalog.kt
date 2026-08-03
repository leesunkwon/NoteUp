package com.kotlinsun.noteup.data.ai

import android.os.Build

data class AiModelArtifact(
    val id: String,
    val displayName: String,
    val fileName: String,
    val revision: String,
    val downloadUrl: String,
    val expectedBytes: Long,
    val sha256: String,
)

data class AiModelCompatibility(
    val isSupported: Boolean,
    val selectedAbi: String?,
    val deviceAbis: List<String>,
)

object AiModelCatalog {
    const val ENGINE_CACHE_DIRECTORY_NAME = "litert_lm"
    const val GEMMA_4_E2B_FILE_NAME = "gemma-4-E2B-it.litertlm"
    const val GEMMA_4_E2B_REVISION = "6e5c4f1e395deb959c494953478fa5cec4b8008f"
    const val GEMMA_4_E2B_EXPECTED_BYTES = 2_588_147_712L
    const val GEMMA_4_E2B_SHA256 =
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    val supportedAbis: Set<String> = setOf("arm64-v8a", "x86_64")

    val defaultModel = AiModelArtifact(
        id = "gemma-4-e2b-it",
        displayName = "Gemma 4 E2B",
        fileName = GEMMA_4_E2B_FILE_NAME,
        revision = GEMMA_4_E2B_REVISION,
        downloadUrl = "https://huggingface.co/litert-community/" +
            "gemma-4-E2B-it-litert-lm/resolve/$GEMMA_4_E2B_REVISION/" +
            "$GEMMA_4_E2B_FILE_NAME?download=true",
        expectedBytes = GEMMA_4_E2B_EXPECTED_BYTES,
        sha256 = GEMMA_4_E2B_SHA256,
    )

    fun deviceCompatibility(deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList()): AiModelCompatibility {
        val selectedAbi = deviceAbis.firstOrNull(supportedAbis::contains)
        return AiModelCompatibility(
            isSupported = selectedAbi != null,
            selectedAbi = selectedAbi,
            deviceAbis = deviceAbis,
        )
    }
}
