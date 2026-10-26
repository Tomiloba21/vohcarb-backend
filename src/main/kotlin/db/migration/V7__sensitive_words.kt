package db.migration

import app.vohcarb.catalog.SensitiveWords
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/**
 * Adds word.is_sensitive and marks existing words. Written in Kotlin (not SQL) so the labels live in one place,
 * [SensitiveWords], shared with the importer.
 */
@Suppress("ClassName")
class V7__sensitive_words : BaseJavaMigration() {

    override fun migrate(context: Context) {
        context.connection.createStatement().use {
            // Not plain "sensitive": that's a reserved word in MariaDB/MySQL.
            it.execute("ALTER TABLE word ADD COLUMN is_sensitive BOOLEAN NOT NULL DEFAULT FALSE")
            it.executeUpdate(SensitiveWords.MARK_SQL)
        }
    }
}
