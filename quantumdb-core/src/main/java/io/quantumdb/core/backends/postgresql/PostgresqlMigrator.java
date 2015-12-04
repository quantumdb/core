package io.quantumdb.core.backends.postgresql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Table.Cell;
import io.quantumdb.core.backends.DatabaseMigrator;
import io.quantumdb.core.backends.postgresql.migrator.NullRecords;
import io.quantumdb.core.backends.postgresql.migrator.TableCreator;
import io.quantumdb.core.backends.postgresql.planner.GreedyMigrationPlanner;
import io.quantumdb.core.backends.postgresql.planner.Operation;
import io.quantumdb.core.backends.postgresql.planner.Plan;
import io.quantumdb.core.backends.postgresql.planner.PlanValidator;
import io.quantumdb.core.backends.postgresql.planner.Step;
import io.quantumdb.core.schema.definitions.Catalog;
import io.quantumdb.core.schema.definitions.Column;
import io.quantumdb.core.schema.definitions.Table;
import io.quantumdb.core.state.RefLog;
import io.quantumdb.core.state.RefLog.TableRef;
import io.quantumdb.core.utils.QueryBuilder;
import io.quantumdb.core.versioning.MigrationFunctions;
import io.quantumdb.core.versioning.State;
import io.quantumdb.core.versioning.TableMapping;
import io.quantumdb.core.versioning.Version;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class PostgresqlMigrator implements DatabaseMigrator {

	private final PostgresqlBackend backend;

	PostgresqlMigrator(PostgresqlBackend backend) {
		this.backend = backend;
	}

	@Override
	public void migrate(State state, Version from, Version to) throws MigrationException {
		RefLog refLog = state.getRefLog();
		Set<Version> preMigration = refLog.getVersions();
		Plan plan = new GreedyMigrationPlanner().createPlan(state, from, to);

		PlanValidator.validate(plan);
		Set<Version> postMigration = refLog.getVersions();
		Set<Version> intermediateVersions = Sets.newHashSet(Sets.difference(postMigration, preMigration));
		intermediateVersions.remove(to);

		new InternalPlanner(backend, plan, state, from, to, intermediateVersions).migrate();
	}

	@Override
	public void drop(State state, Version version) throws MigrationException {
		RefLog refLog = state.getRefLog();
		List<TableRef> tablesToDrop = refLog.getTableRefs().stream()
				.filter(tableRef -> tableRef.getVersions().contains(version))
				.filter(tableRef -> tableRef.getVersions().size() == 1)
				.collect(Collectors.toList());

		log.info("Determined the following tables will be dropped: {}", tablesToDrop);
		try (Connection connection = backend.connect()) {
			dropTriggers(connection, state.getFunctions(), tablesToDrop);
			dropFunctions(connection, state.getFunctions(), tablesToDrop);
			dropTables(connection, refLog, tablesToDrop);
			backend.persistState(state);
		}
		catch (SQLException e) {
			throw new MigrationException(e);
		}
	}

	private void dropTriggers(Connection connection, MigrationFunctions migrationFunctions,
			List<TableRef> tablesToDrop) throws SQLException {

		for (TableRef table : tablesToDrop) {
			String tableId = table.getTableId();

			com.google.common.collect.Table<String, String, String> triggers = migrationFunctions.getTriggers(tableId);
			for (Cell<String, String, String> function : triggers.cellSet()) {
				String triggerName = function.getValue();
				String sourceTableId = function.getRowKey();
				String targetTableId = function.getColumnKey();
				try (Statement statement = connection.createStatement()) {
					statement.execute("DROP TRIGGER " + triggerName + " ON " + sourceTableId + ";");
				}
				migrationFunctions.removeTrigger(sourceTableId, targetTableId);
			}
		}
	}

	private void dropFunctions(Connection connection, MigrationFunctions migrationFunctions,
			List<TableRef> tablesToDrop) throws SQLException {

		for (TableRef table : tablesToDrop) {
			String tableId = table.getTableId();

			com.google.common.collect.Table<String, String, String> functions = migrationFunctions.getFunctions(tableId);
			for (Cell<String, String, String> function : functions.cellSet()) {
				String functionName = function.getValue();
				String sourceTableId = function.getRowKey();
				String targetTableId = function.getColumnKey();
				try (Statement statement = connection.createStatement()) {
					statement.execute("DROP FUNCTION " + functionName + "();");
				}
				migrationFunctions.removeFunction(sourceTableId, targetTableId);
			}
		}
	}

	private void dropTables(Connection connection, RefLog refLog, List<TableRef> tablesToDrop) throws SQLException {
		for (TableRef table : tablesToDrop) {
			String tableId = table.getTableId();
			try (Statement statement = connection.createStatement()) {
				statement.execute("DROP TABLE " + tableId + " CASCADE;");
			}

			refLog.dropTable(table);
		}
	}

	static class InternalPlanner {

		private final Plan plan;
		private final Set<Version> intermediateVersions;
		private final RefLog refLog;
		private final State state;
		private final NullRecords nullRecords;
		private final Multimap<Table, String> migratedColumns;
		private final PostgresqlBackend backend;
		private final Version from;
		private final Version to;

		private final com.google.common.collect.Table<String, String, SyncFunction> syncFunctions;


		public InternalPlanner(PostgresqlBackend backend, Plan plan, State state, Version from, Version to,
				Set<Version> intermediateVersions) {

			this.backend = backend;
			this.plan = plan;
			this.intermediateVersions = intermediateVersions;
			this.refLog = plan.getRefLog();
			this.state = state;
			this.nullRecords = new NullRecords();
			this.migratedColumns = HashMultimap.create();
			this.syncFunctions = HashBasedTable.create();
			this.from = from;
			this.to = to;
		}

		public void migrate() throws MigrationException {
			createGhostTables();

			Optional<Step> nextStep;
			while ((nextStep = plan.nextStep()).isPresent()) {
				try {
					Step step = nextStep.get();
					execute(step.getOperation());
					step.markAsExecuted();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new MigrationException(e);
				}
			}

			createIndexes();

			synchronizeBackwards();

//			intermediateVersions.forEach(state.getTableMapping()::remove);

			persistState();
		}

		private void persistState() throws MigrationException {
			try {
				backend.persistState(state);
			}
			catch (SQLException e) {
				throw new MigrationException(e);
			}
		}

		private void execute(Operation operation) throws MigrationException, InterruptedException {

			log.info("Executing operation: " + operation);
			try {
				Set<Table> tables = operation.getTables();
				switch (operation.getType()) {
					case ADD_NULL:
						nullRecords.insertNullObjects(backend, tables);
						break;
					case DROP_NULL:
						nullRecords.deleteNullObjects(backend, tables);
						break;
					case COPY:
						Table table = tables.iterator().next();
						Set<String> columns = operation.getColumns();
						Set<String> previouslyMigrated = Sets.newHashSet(this.migratedColumns.get(table));
						Set<String> combined = Sets.union(previouslyMigrated, columns);

						synchronizeForwards(table, Sets.newHashSet(combined));
						copyData(table, previouslyMigrated, columns);
						this.migratedColumns.putAll(table, columns);
						break;
				}
			}
			catch (SQLException e) {
				throw new MigrationException(e);
			}
		}

		private void createGhostTables() throws MigrationException {
			try (Connection connection = backend.connect()) {
				TableCreator creator = new TableCreator();
				creator.create(connection, plan.getGhostTables());
			}
			catch (SQLException e) {
				throw new MigrationException(e);
			}
		}

		private void createIndexes() throws MigrationException {
			try (Connection connection = backend.connect()) {
				TableCreator creator = new TableCreator();
				creator.createIndexes(connection, plan.getGhostTables());
			}
			catch (SQLException e) {
				throw new MigrationException(e);
			}
		}

		private void synchronizeForwards(Table targetTable, Set<String> targetColumns) throws SQLException {
			log.info("Creating forward sync function for table: {}...", targetTable.getName());
			try (Connection connection = backend.connect()) {
				for (DataMapping dataMapping : listDataMappings(Direction.FORWARDS)) {
					if (dataMapping.getTargetTable().equals(targetTable)) {
						ensureSyncFunctionExists(connection, refLog, source, target, catalog, targetColumns);
					}
				}
			}
		}

		private void copyData(Table targetTable, Set<String> migratedColumns, Set<String> columnsToMigrate)
				throws SQLException, InterruptedException {

			for (DataMapping dataMapping : listDataMappings(Direction.FORWARDS)) {
				if (dataMapping.getTargetTable().equals(targetTable)) {
					TableDataMigrator tableDataMigrator = new TableDataMigrator(backend, dataMapping);
					tableDataMigrator.migrateData(nullRecords, migratedColumns, columnsToMigrate);
				}
			}
		}

		private void synchronizeBackwards() throws MigrationException {
			log.info("Creating backwards sync functions...");
			try (Connection connection = backend.connect()) {
				for (DataMapping dataMapping : listDataMappings(DataMappings.Direction.BACKWARDS)) {
					Set<String> columns = dataMapping.getTargetTable().getColumns().stream()
							.map(Column::getName)
							.collect(Collectors.toSet());

					log.info("Creating backward sync function for table: {}...", dataMapping.getTargetTable().getName());
					ensureSyncFunctionExists(connection, dataMapping, columns);
				}
			}
			catch (SQLException e) {
				throw new MigrationException(e);
			}
		}

		private List<DataMapping> listDataMappings(Direction direction) {
			TableMapping tableMapping = state.getTableMapping();
			Version target = direction == Direction.FORWARDS ? from : to;
			ImmutableSet<String> tableIds = tableMapping.getTableIds(target);
			Multimap<String, String> ghostTableIdMapping = tableMapping.getGhostTableIdMapping(from, to);

			Catalog catalog = state.getCatalog();

			List<DataMapping> results = Lists.newArrayList();
			for (String tableId : tableIds) {
				Table table = catalog.getTable(tableId);

				if (direction == Direction.BACKWARDS) {
					dataMappings.getTransitiveDataMappings(table, direction).stream()
							.filter(mapping -> ghostTableIdMapping.containsKey(mapping.getTargetTable().getName()))
							.forEach(results::add);
				}
				else {
					dataMappings.getTransitiveDataMappings(table, direction).stream()
							.filter(mapping -> ghostTableIdMapping.containsValue(mapping.getTargetTable().getName()))
							.forEach(results::add);
				}
			}

			Collections.sort(results, new Comparator<DataMapping>() {
				@Override
				public int compare(DataMapping o1, DataMapping o2) {
					String t1 = o1.getTargetTable().getName();
					String t2 = o2.getTargetTable().getName();

					List<Step> migrationSteps = plan.getSteps();
					for (Step step : migrationSteps) {
						Set<String> tableNames = step.getOperation().getTables().stream()
								.map(Table::getName)
								.collect(Collectors.toSet());

						if (tableNames.contains(t1)) {
							if (tableNames.contains(t2)) {
								return 0;
							}
							else {
								return -1;
							}
						}
						else {
							if (tableNames.contains(t2)) {
								return 1;
							}
						}
					}
					return 0;
				}
			});

			return results;
		}

		void ensureSyncFunctionExists(Connection connection, RefLog refLog, TableRef source,
				TableRef target, Catalog catalog, Set<String> columns) throws SQLException {

			String sourceTableId = source.getTableId();
			String targetTableId = target.getTableId()

			MigrationFunctions functions = state.getFunctions();
			SyncFunction syncFunction = syncFunctions.get(sourceTableId, targetTableId);
			if (syncFunction == null) {
				syncFunction = new SyncFunction(refLog, source, target, catalog, nullRecords);
				syncFunction.setColumnsToMigrate(columns);
				syncFunctions.put(sourceTableId, targetTableId, syncFunction);

				log.info("Creating sync function: {} for table: {}", syncFunction.getFunctionName(), sourceTableId);
				execute(connection, syncFunction.createFunctionStatement());
				functions.putFunction(sourceTableId, targetTableId, syncFunction.getFunctionName());

				log.info("Creating trigger: {} for table: {}", syncFunction.getTriggerName(), sourceTableId);
				execute(connection, syncFunction.createTriggerStatement());
				functions.putTrigger(sourceTableId, targetTableId, syncFunction.getTriggerName());
			}
			else {
				syncFunction.setColumnsToMigrate(columns);

				log.info("Updating sync function: {} for table: {}", syncFunction.getFunctionName(), sourceTableId);
				execute(connection, syncFunction.createFunctionStatement());
				functions.putFunction(sourceTableId, targetTableId, syncFunction.getFunctionName());
			}
		}

		private void execute(Connection connection, QueryBuilder queryBuilder) throws SQLException {
			String query = queryBuilder.toString();
			try (Statement statement = connection.createStatement()) {
				log.debug("Executing: " + query);
				statement.execute(query);
			}
		}
	}
}
