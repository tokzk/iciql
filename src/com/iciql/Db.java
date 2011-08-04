/*
 * Copyright 2004-2011 H2 Group.
 * Copyright 2011 James Moger.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.iciql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.sql.DataSource;

import com.iciql.DbUpgrader.DefaultDbUpgrader;
import com.iciql.Iciql.IQVersion;
import com.iciql.Iciql.IQTable;
import com.iciql.SQLDialect.DefaultSQLDialect;
import com.iciql.SQLDialect.H2Dialect;
import com.iciql.util.JdbcUtils;
import com.iciql.util.StringUtils;
import com.iciql.util.Utils;
import com.iciql.util.WeakIdentityHashMap;

/**
 * This class represents a connection to a database.
 */

public class Db {

	/**
	 * This map It holds unique tokens that are generated by functions such as
	 * Function.sum(..) in "db.from(p).select(Function.sum(p.unitPrice))". It
	 * doesn't actually hold column tokens, as those are bound to the query
	 * itself.
	 */
	private static final Map<Object, Token> TOKENS;

	private static final Map<String, Class<? extends SQLDialect>> DIALECTS;

	private final Connection conn;
	private final Map<Class<?>, TableDefinition<?>> classMap = Collections
			.synchronizedMap(new HashMap<Class<?>, TableDefinition<?>>());
	private final SQLDialect dialect;
	private DbUpgrader dbUpgrader = new DefaultDbUpgrader();
	private final Set<Class<?>> upgradeChecked = Collections.synchronizedSet(new HashSet<Class<?>>());

	static {
		TOKENS = Collections.synchronizedMap(new WeakIdentityHashMap<Object, Token>());
		DIALECTS = Collections.synchronizedMap(new HashMap<String, Class<? extends SQLDialect>>());
		DIALECTS.put("org.h2", H2Dialect.class);
	}

	private Db(Connection conn) {
		this.conn = conn;
		dialect = getDialect(conn.getClass().getCanonicalName());
		dialect.configureDialect(conn);
	}

	public static void registerDialect(Connection conn, Class<? extends SQLDialect> dialectClass) {
		registerDialect(conn.getClass().getCanonicalName(), dialectClass);
	}

	public static void registerDialect(String connClass, Class<? extends SQLDialect> dialectClass) {
		DIALECTS.put(connClass, dialectClass);
	}

	SQLDialect getDialect(String clazz) {
		// try dialect by connection class name
		Class<? extends SQLDialect> dialectClass = DIALECTS.get(clazz);
		int lastDot = 0;
		while (dialectClass == null) {
			// try dialect by package name
			int nextDot = clazz.indexOf('.', lastDot);
			if (nextDot > -1) {
				String pkg = clazz.substring(0, nextDot);
				lastDot = nextDot + 1;
				dialectClass = DIALECTS.get(pkg);
			} else {
				dialectClass = DefaultSQLDialect.class;
			}
		}
		return instance(dialectClass);
	}

	static <X> X registerToken(X x, Token token) {
		TOKENS.put(x, token);
		return x;
	}

	static Token getToken(Object x) {
		return TOKENS.get(x);
	}

	private static <T> T instance(Class<T> clazz) {
		try {
			return clazz.newInstance();
		} catch (Exception e) {
			throw new IciqlException(e);
		}
	}

	public static Db open(String url, String user, String password) {
		try {
			Connection conn = JdbcUtils.getConnection(null, url, user, password);
			return new Db(conn);
		} catch (SQLException e) {
			throw convert(e);
		}
	}

	/**
	 * Create a new database instance using a data source. This method is fast,
	 * so that you can always call open() / close() on usage.
	 * 
	 * @param ds
	 *            the data source
	 * @return the database instance.
	 */
	public static Db open(DataSource ds) {
		try {
			return new Db(ds.getConnection());
		} catch (SQLException e) {
			throw convert(e);
		}
	}

	public static Db open(Connection conn) {
		return new Db(conn);
	}

	public static Db open(String url, String user, char[] password) {
		try {
			Properties prop = new Properties();
			prop.setProperty("user", user);
			prop.put("password", password);
			Connection conn = JdbcUtils.getConnection(null, url, prop);
			return new Db(conn);
		} catch (SQLException e) {
			throw convert(e);
		}
	}

	private static Error convert(Exception e) {
		return new Error(e);
	}

	public <T> void insert(T t) {
		Class<?> clazz = t.getClass();
		define(clazz).createTableIfRequired(this).insert(this, t, false);
	}

	public <T> long insertAndGetKey(T t) {
		Class<?> clazz = t.getClass();
		return define(clazz).createTableIfRequired(this).insert(this, t, true);
	}

	/**
	 * Merge usually INSERTS if the record does not exist or UPDATES the record
	 * if it does exist. Not all databases support MERGE and the syntax varies
	 * with the database.
	 * 
	 * If the dialect does not support merge an IciqlException will be thrown.
	 * 
	 * @param t
	 */
	public <T> void merge(T t) {
		if (!getDialect().supportsMerge()) {
			throw new IciqlException("Merge is not supported by this SQL dialect");
		}
		Class<?> clazz = t.getClass();
		define(clazz).createTableIfRequired(this).merge(this, t);
	}

	public <T> void update(T t) {
		Class<?> clazz = t.getClass();
		define(clazz).createTableIfRequired(this).update(this, t);
	}

	public <T> void delete(T t) {
		Class<?> clazz = t.getClass();
		define(clazz).createTableIfRequired(this).delete(this, t);
	}

	public <T extends Object> Query<T> from(T alias) {
		Class<?> clazz = alias.getClass();
		define(clazz).createTableIfRequired(this);
		return Query.from(this, alias);
	}

	@SuppressWarnings("unchecked")
	public <T> List<T> buildObjects(Class<? extends T> modelClass, ResultSet rs) {
		List<T> result = new ArrayList<T>();
		TableDefinition<T> def = (TableDefinition<T>) define(modelClass).createTableIfRequired(this);
		try {
			while (rs.next()) {
				T item = Utils.newObject(modelClass);
				def.readRow(item, rs);
				result.add(item);
			}
		} catch (SQLException e) {
			throw new IciqlException(e);
		}
		return result;
	}

	Db upgradeDb() {
		if (!upgradeChecked.contains(dbUpgrader.getClass())) {
			// flag as checked immediately because calls are nested.
			upgradeChecked.add(dbUpgrader.getClass());

			IQVersion model = dbUpgrader.getClass().getAnnotation(IQVersion.class);
			if (model.value() > 0) {
				DbVersion v = new DbVersion();
				DbVersion dbVersion =
				// (SCHEMA="" && TABLE="") == DATABASE
				from(v).where(v.schema).is("").and(v.table).is("").selectFirst();
				if (dbVersion == null) {
					// database has no version registration, but model specifies
					// version: insert DbVersion entry and return.
					DbVersion newDb = new DbVersion(model.value());
					insert(newDb);
				} else {
					// database has a version registration:
					// check to see if upgrade is required.
					if ((model.value() > dbVersion.version) && (dbUpgrader != null)) {
						// database is an older version than the model
						boolean success = dbUpgrader
								.upgradeDatabase(this, dbVersion.version, model.value());
						if (success) {
							dbVersion.version = model.value();
							update(dbVersion);
						}
					}
				}
			}
		}
		return this;
	}

	<T> void upgradeTable(TableDefinition<T> model) {
		if (!upgradeChecked.contains(model.getModelClass())) {
			// flag is checked immediately because calls are nested
			upgradeChecked.add(model.getModelClass());

			if (model.tableVersion > 0) {
				// table is using iciql version tracking.
				DbVersion v = new DbVersion();
				String schema = StringUtils.isNullOrEmpty(model.schemaName) ? "" : model.schemaName;
				DbVersion dbVersion = from(v).where(v.schema).like(schema).and(v.table).like(model.tableName)
						.selectFirst();
				if (dbVersion == null) {
					// table has no version registration, but model specifies
					// version: insert DbVersion entry
					DbVersion newTable = new DbVersion(model.tableVersion);
					newTable.schema = schema;
					newTable.table = model.tableName;
					insert(newTable);
				} else {
					// table has a version registration:
					// check if upgrade is required
					if ((model.tableVersion > dbVersion.version) && (dbUpgrader != null)) {
						// table is an older version than model
						boolean success = dbUpgrader.upgradeTable(this, schema, model.tableName,
								dbVersion.version, model.tableVersion);
						if (success) {
							dbVersion.version = model.tableVersion;
							update(dbVersion);
						}
					}
				}
			}
		}
	}

	<T> TableDefinition<T> define(Class<T> clazz) {
		TableDefinition<T> def = getTableDefinition(clazz);
		if (def == null) {
			upgradeDb();
			def = new TableDefinition<T>(clazz);
			def.mapFields();
			classMap.put(clazz, def);
			if (Iciql.class.isAssignableFrom(clazz)) {
				T t = instance(clazz);
				Iciql table = (Iciql) t;
				Define.define(def, table);
			} else if (clazz.isAnnotationPresent(IQTable.class)) {
				// annotated classes skip the Define().define() static
				// initializer
				T t = instance(clazz);
				def.mapObject(t);
			}
		}
		return def;
	}

	public synchronized void setDbUpgrader(DbUpgrader upgrader) {
		if (!upgrader.getClass().isAnnotationPresent(IQVersion.class)) {
			throw new IciqlException("DbUpgrader must be annotated with " + IQVersion.class.getSimpleName());
		}
		this.dbUpgrader = upgrader;
		upgradeChecked.clear();
	}

	SQLDialect getDialect() {
		return dialect;
	}

	public Connection getConnection() {
		return conn;
	}

	public void close() {
		try {
			conn.close();
		} catch (Exception e) {
			throw new IciqlException(e);
		}
	}

	public <A> TestCondition<A> test(A x) {
		return new TestCondition<A>(x);
	}

	public <T> void insertAll(List<T> list) {
		for (T t : list) {
			insert(t);
		}
	}

	public <T> List<Long> insertAllAndGetKeys(List<T> list) {
		List<Long> identities = new ArrayList<Long>();
		for (T t : list) {
			identities.add(insertAndGetKey(t));
		}
		return identities;
	}

	public <T> void updateAll(List<T> list) {
		for (T t : list) {
			update(t);
		}
	}

	public <T> void deleteAll(List<T> list) {
		for (T t : list) {
			delete(t);
		}
	}

	PreparedStatement prepare(String sql, boolean returnGeneratedKeys) {
		try {
			if (returnGeneratedKeys) {
				return conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			}
			return conn.prepareStatement(sql);
		} catch (SQLException e) {
			throw new IciqlException(e);
		}
	}

	@SuppressWarnings("unchecked")
	<T> TableDefinition<T> getTableDefinition(Class<T> clazz) {
		return (TableDefinition<T>) classMap.get(clazz);
	}

	/**
	 * Run a SQL query directly against the database.
	 * 
	 * Be sure to close the ResultSet with
	 * 
	 * <pre>
	 * JdbcUtils.closeSilently(rs, true);
	 * </pre>
	 * 
	 * @param sql
	 *            the SQL statement
	 * @param args
	 *            optional object arguments for x=? tokens in query
	 * @return the result set
	 */
	public ResultSet executeQuery(String sql, Object... args) {
		try {
			if (args.length == 0) {
				return conn.createStatement().executeQuery(sql);
			} else {
				PreparedStatement stat = conn.prepareStatement(sql);
				int i = 1;
				for (Object arg : args) {
					stat.setObject(i++, arg);
				}
				return stat.executeQuery();
			}
		} catch (SQLException e) {
			throw new IciqlException(e);
		}
	}

	/**
	 * Run a SQL query directly against the database and map the results to the
	 * model class.
	 * 
	 * @param modelClass
	 *            the model class to bind the query ResultSet rows into.
	 * @param sql
	 *            the SQL statement
	 * @return the result set
	 */
	public <T> List<T> executeQuery(Class<? extends T> modelClass, String sql, Object... args) {
		ResultSet rs = null;
		try {
			if (args.length == 0) {
				rs = conn.createStatement().executeQuery(sql);
			} else {
				PreparedStatement stat = conn.prepareStatement(sql);
				int i = 1;
				for (Object arg : args) {
					stat.setObject(i++, arg);
				}
				rs = stat.executeQuery();
			}
			return buildObjects(modelClass, rs);
		} catch (SQLException e) {
			throw new IciqlException(e);
		} finally {
			JdbcUtils.closeSilently(rs, true);
		}
	}

	/**
	 * Run a SQL statement directly against the database.
	 * 
	 * @param sql
	 *            the SQL statement
	 * @return the update count
	 */
	public int executeUpdate(String sql) {
		Statement stat = null;
		try {
			stat = conn.createStatement();
			int updateCount = stat.executeUpdate(sql);
			return updateCount;
		} catch (SQLException e) {
			throw new IciqlException(e);
		} finally {
			JdbcUtils.closeSilently(stat);
		}
	}
}