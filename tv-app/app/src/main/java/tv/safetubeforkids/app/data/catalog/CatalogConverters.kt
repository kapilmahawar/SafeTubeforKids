package tv.safetubeforkids.app.data.catalog

import androidx.room.TypeConverter

/** Stores [ContentItemType] by name so the column stays readable in the database. */
class CatalogConverters {
    @TypeConverter
    fun fromContentItemType(type: ContentItemType): String = type.name

    @TypeConverter
    fun toContentItemType(raw: String): ContentItemType =
        ContentItemType.entries.firstOrNull { it.name == raw }
            ?: throw IllegalArgumentException("Unknown catalog content type: $raw")
}
