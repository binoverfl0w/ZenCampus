package io.github.binoverfl0w.zencampus.model

import kotlinx.serialization.Serializable

@Serializable enum class CatalogueField(val category: String) { ALL("-1"), TITLE("200"), AUTHOR("100"), ISBN("490") }
@Serializable data class CatalogueQuery(val text: String, val field: CatalogueField = CatalogueField.ALL)
@Serializable data class CatalogueBook(
    val id: String, val title: String, val author: String = "", val year: String = "", val edition: String = "",
    val media: String = "", val isbn: String = "", val recordQuery: String = "",
)
@Serializable data class CatalogueResults(val query: CatalogueQuery, val page: Int, val books: List<CatalogueBook>,
    val total: Int? = null, val hasNext: Boolean = false, val fetchedAt: Long, val sample: Boolean = false)
@Serializable data class CatalogueProperty(val label: String, val value: String)
@Serializable data class CatalogueCopy(val callNumber: String = "", val location: String = "", val branch: String = "",
    val status: String = "", val notes: String = "")
@Serializable data class CatalogueLink(val label: String, val url: String)
@Serializable data class CatalogueRecord(val book: CatalogueBook, val properties: List<CatalogueProperty> = emptyList(),
    val copies: List<CatalogueCopy> = emptyList(), val holdingsNote: String = "", val holdingsUnavailable: Boolean = false,
    val links: List<CatalogueLink> = emptyList(), val fetchedAt: Long, val sample: Boolean = false,
    val holdingsFetchedAt: Long? = null)
data class CatalogueSearchState(val query: CatalogueQuery? = null, val results: CatalogueResults? = null,
    val loading: Boolean = false, val error: FailureKind? = null)
data class CatalogueRecordState(val id: String = "", val record: CatalogueRecord? = null,
    val loading: Boolean = false, val error: FailureKind? = null)
