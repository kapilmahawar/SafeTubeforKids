package tv.safetubeforkids.app.data.catalog

import androidx.room.TypeConverter

/**
 * Stores [ContentItemType] by name, so a stored value stays readable and reordering the enum cannot
 * silently reinterpret it.
 *
 * Since W1b nothing persists [ContentItemType]: the stored catalog is `catalog_nodes`, whose node
 * type is [CatalogNodeType] and whose converter is [CatalogNodeConverters]. This converter is kept as
 * the value-level rule for the type - it is what turns a bad string into a loud
 * `IllegalArgumentException` instead of a silent default - and it is deliberately not attached to any
 * entity.
 */
class CatalogConverters {
    @TypeConverter
    fun fromContentItemType(type: ContentItemType): String = type.name

    @TypeConverter
    fun toContentItemType(raw: String): ContentItemType =
        ContentItemType.entries.firstOrNull { it.name == raw }
            ?: throw IllegalArgumentException("Unknown catalog content type: $raw")
}
