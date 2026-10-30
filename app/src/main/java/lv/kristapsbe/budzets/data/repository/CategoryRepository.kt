package lv.kristapsbe.budzets.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import lv.kristapsbe.budzets.data.local.BudzetsDatabase
import lv.kristapsbe.budzets.data.local.toDomain
import lv.kristapsbe.budzets.data.local.toEntity
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CategoryId
import java.time.Clock
import java.time.YearMonth

class CategoryRepository(
    private val db: BudzetsDatabase,
    private val clock: Clock,
    private val newId: () -> String,
) {
    private val dao = db.categoryDao()

    /** All categories, archived and deleted included, in display order. */
    val categories: Flow<List<Category>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun get(id: CategoryId): Category? = dao.get(id.value)?.toDomain()

    suspend fun create(name: String, iconKey: String? = null): Category = db.withTransaction {
        val now = clock.instant()
        val category = Category(
            id = CategoryId(newId()),
            name = validName(name),
            iconKey = iconKey,
            sortOrder = dao.maxSortOrder() + 1,
            archivedFrom = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.insert(category.toEntity())
        category
    }

    /**
     * Creates editable starter categories, but only while there are no categories (deleted ones
     * don't count).
     * @return false if categories already existed.
     */
    suspend fun createStarterCategories(names: List<String>): Boolean = db.withTransaction {
        if (dao.hasCategories()) return@withTransaction false
        names.forEach { create(it) }
        true
    }

    suspend fun rename(id: CategoryId, name: String) = modify(id) { it.copy(name = validName(name)) }

    /** Rewrites sortOrder to match [order]. Categories not listed keep their position after the listed ones. */
    suspend fun reorder(order: List<CategoryId>) = db.withTransaction {
        val listed = order.toSet()
        val rest = dao.getAll().map { it.toDomain() }.filter { it.id !in listed }.map { it.id }
        (order + rest).forEachIndexed { index, id -> modify(id) { it.copy(sortOrder = index) } }
    }

    /** Drops the category from budget plans from [fromMonth] on. Its history stays visible. */
    suspend fun archive(id: CategoryId, fromMonth: YearMonth = YearMonth.now(clock)) =
        modify(id) { it.copy(archivedFrom = it.archivedFrom ?: fromMonth) }

    suspend fun unarchive(id: CategoryId) = modify(id) { it.copy(archivedFrom = null) }

    private suspend fun modify(id: CategoryId, change: (Category) -> Category) = db.withTransaction {
        val current = requireNotNull(dao.get(id.value)) { "Unknown category" }.toDomain()
        dao.update(change(current).copy(updatedAt = clock.instant()).toEntity())
    }

    private fun validName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Category name must not be blank" }
        require(trimmed.length <= MAX_NAME_LENGTH) { "Category name is too long" }
        return trimmed
    }

    companion object {
        const val MAX_NAME_LENGTH = 40
    }
}
