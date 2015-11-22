package io.quantumdb.core.migration.operations;

import io.quantumdb.core.schema.definitions.Catalog;
import io.quantumdb.core.schema.definitions.ForeignKey;
import io.quantumdb.core.schema.definitions.Table;
import io.quantumdb.core.schema.operations.CopyTable;
import io.quantumdb.core.state.RefLog;
import io.quantumdb.core.state.RefLog.TableRef;
import io.quantumdb.core.utils.RandomHasher;
import io.quantumdb.core.versioning.Version;

class CopyTableMigrator implements SchemaOperationMigrator<CopyTable> {

	@Override
	public void migrate(Catalog catalog, RefLog refLog, Version version, CopyTable operation) {
		String tableId = RandomHasher.generateTableId(refLog);
		String sourceTableName = operation.getSourceTableName();
		String targetTableName = operation.getTargetTableName();

		refLog.prepareFork(version);
		TableRef sourceTableRef = refLog.getTableRef(version.getParent(), sourceTableName);
		refLog.copyTable(version, sourceTableName, targetTableName, tableId);

		Table sourceTable = catalog.getTable(sourceTableRef.getTableId());
		Table targetTable = sourceTable.copy().rename(tableId);
		for (ForeignKey foreignKey : sourceTable.getForeignKeys()) {
			String referredTableId = foreignKey.getReferredTableName();
			String referredTableName = tableMapping.getTableName(version, referredTableId);
			referredTableId = tableMapping.getTableId(version, referredTableName);

			Table referredTable = catalog.getTable(referredTableId);
			targetTable.addForeignKey(foreignKey.getReferencingColumns().toArray(new String[0]))
					.named(foreignKey.getForeignKeyName())
					.onUpdate(foreignKey.getOnUpdate())
					.onDelete(foreignKey.getOnDelete())
					.referencing(referredTable, foreignKey.getReferredColumns().toArray(new String[0]));
		}

		catalog.addTable(targetTable);
		tableMapping.copy(version, sourceTableName, targetTableName, tableId);

		// TODO: Add pipeline mappings...
	}

}
