package com.pocketllm.domain.download

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path

/** HuggingFace 原始源 */
open class HuggingFaceSource : ModelSource {
    override val id = "hf"
    override val displayNameZh = "HuggingFace（海外）"
    override val displayNameEn = "HuggingFace (Global)"
    override val baseUrl = "https://huggingface.co"

    private val api: HfApi = retrofitBuild(baseUrl).create(HfApi::class.java)

    override suspend fun listGgufFiles(repo: String): List<RemoteGgufFile> {
        val tree = api.listTree(repo, "main")
        return tree.filter { it.path.endsWith(".gguf") }.map {
            RemoteGgufFile(it.path, it.size ?: 0, it.lastCommit?.date ?: "")
        }
    }

    override fun fileUrl(repo: String, path: String): String =
        "$baseUrl/$repo/resolve/main/$path"
}

@JsonClass(generateAdapter = true)
data class HfTreeItem(
    @Json(name = "path") val path: String,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "oid") val oid: String? = null,
    @Json(name = "lastCommit") val lastCommit: HfCommit? = null,
    @Json(name = "type") val type: String? = null
)

@JsonClass(generateAdapter = true)
data class HfCommit(
    @Json(name = "date") val date: String? = null,
    @Json(name = "oid") val oid: String? = null
)

interface HfApi {
    @GET("api/models/{repo}/tree/main?recursive=true")
    suspend fun listTree(@Path("repo") repo: String, @Path("_") branch: String = "main"): List<HfTreeItem>
}

internal fun retrofitBuild(baseUrl: String): retrofit2.Retrofit {
    return retrofit2.Retrofit.Builder()
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(com.squareup.retrofit2.converter.moshi.MoshiConverterFactory.create(
            com.squareup.moshi.Moshi.Builder().build()
        ))
        .build()
}
