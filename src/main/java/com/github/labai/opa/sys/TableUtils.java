package com.github.labai.opa.sys;

import com.github.labai.opa.Opa;
import com.github.labai.opa.Opa.DataType;
import com.github.labai.opa.Opa.IoDir;
import com.github.labai.opa.Opa.OpaField;
import com.github.labai.opa.Opa.OpaParam;
import com.github.labai.opa.Opa.OpaTable;
import com.github.labai.opa.Opa.OpaTransient;
import com.github.labai.opa.sys.Exceptions.OpaStructureException;
import com.progress.open4gl.InputResultSet;
import com.progress.open4gl.ResultSetHolder;
import com.progress.open4gl.Rowid;
import com.progress.open4gl.dynamicapi.MetaSchema;
import com.progress.open4gl.dynamicapi.ResultSet;
import com.progress.open4gl.dynamicapi.ResultSetMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * For internal usage only (is not part of api)
 *
 * @author Augustus
 */
public class TableUtils {
	final static Logger logger = LoggerFactory.getLogger(Opa.class);

	/**
	 *  a) if field in opp field list is provided, then fill it, otherwise (if null) - create ArrayList;
	 *  b) if provided list is not empty - throw exception (TODO: use flag APPEND)
	 *
	 */
	static void copyAllRecordSetsToBean (Map<Field, ResultSetHolder> rsmap, Object opp) throws OpaStructureException, SQLException {
		// fill TT
		for (Field field: rsmap.keySet()) {
			try {
				ResultSetHolder rsh = rsmap.get(field);
				OpaParam pp = field.getAnnotation(OpaParam.class);
				List list = (List)field.get(opp);
				if (list == null)
					list = new ArrayList();
				else if (list.isEmpty() == false) {
					IoDir io = field.getAnnotation(OpaParam.class).io();
					if (io == IoDir.INOUT)
						list.clear(); // for in-out - clear input data
					else if (io == IoDir.OUT)
						throw new OpaStructureException("List must be empty before call OpenEdge procedure (field=" + field.getName() + ")");
				}
				resultSetToList(pp.table(), (ResultSet) rsh.getResultSetValue(), list);

				field.set(opp, list);
			} catch (IllegalAccessException e) {
				throw new OpaStructureException("Error while assigning resultSet to opp", e);
			}
		}
	}

	private static class ColDef<T> {
		Field field;
		DataType declaredType;
		Class<?> type;
		Method setter;
		ColDef(Field field, Class<T> clazz, boolean ignoreSetters){
			this.field = field;
			this.type = field.getType();
			this.declaredType = declaredDataType(field);
			if (!ignoreSetters) {
				this.setter = getSetter(field, clazz);
				if (this.setter != null)
					this.setter.setAccessible(true); // if not set, then class expecting to be public also
			}
		}
		void setValue(Object bean, Object value) throws IllegalAccessException, InvocationTargetException {
			if (setter != null)
				setter.invoke(bean, value);
			else if (field != null)
				field.set(bean, value);
		};
		private static Method getSetter(Field field, Class<?> clazz) {
			try {
				String nm = field.getName();
				return clazz.getMethod("set" + nm.substring(0, 1).toUpperCase() + nm.substring(1), field.getType());
			} catch (SecurityException e) {
				return null; // will ignore if can't access
			} catch (NoSuchMethodException ignoreMe) {
				return null;
			}
		}
	}

	private static <T> void resultSetToList (Class<T> clazz, ResultSet resultSet, List<T> listToFill) throws SQLException, OpaStructureException {
		assert listToFill != null : "listToFill must be not null";

		boolean allowOmitOpaField = clazz.getAnnotation(OpaTable.class).allowOmitOpaField();
		boolean ignoreSetters = false; // leave for future implementations...

		// Java entity field name map (name -> javaField)
		Map<String, Field> entityFields = new LinkedHashMap<String, Field>();
		for (Field field : clazz.getDeclaredFields()) {
			field.setAccessible(true);
			String name = getOpaName(field, allowOmitOpaField);
			if (name == null)
				continue;
			entityFields.put(name, field);
		}

		// read OE columns -> field (read from metadata)
		Map<String, ColDef<T>> columnNames = new LinkedHashMap<String, ColDef<T>>();
		Set<Field> foundInOE = new HashSet<Field>();
		int count;
		count = resultSet.getMetaData().getColumnCount();
		for (int i = 1; i <= count; i++) {
			String colName = resultSet.getMetaData().getColumnName(i);
			Field f = entityFields.get(colName);
			if (f == null)
				logger.warn("Column '{}' from resultSet (OE temp-table) was not found in entity '{}'", colName, clazz.getName());
			else {
				foundInOE.add(f);
				columnNames.put(colName, new ColDef<T>(f, clazz, ignoreSetters));
			}
		}
		for (Field f : entityFields.values()) {
			if (! foundInOE.contains(f))
				logger.warn("Field '{}' of '{}' was not found in resultSet (OE temp-table)", f.getName(), clazz.getName());
		}

		// iterate throw rows and create bean
		String s;
		while (resultSet.next()) {
			T bean;
			try {
				Constructor<T> constructor = null;
				constructor = clazz.getDeclaredConstructor();
				constructor.setAccessible(true);
				bean = constructor.newInstance();
			} catch (NoSuchMethodException e) {
				throw new OpaStructureException("Error while creating object instance", e);
			} catch (InvocationTargetException e) {
				throw new OpaStructureException("Error while creating object instance", e);
			} catch (InstantiationException e) {
				throw new OpaStructureException("Error while creating object instance", e);
			} catch (IllegalAccessException e) {
				throw new OpaStructureException("Error while creating object instance", e);
			}

			for (String columnName : columnNames.keySet()) {
				ColDef colDef = columnNames.get(columnName);
				if (colDef == null) {
					resultSet.getObject(columnName);
					continue;
				}
				try {
					Class<?> type = colDef.type;
					if (type == long.class) {
						s = resultSet.getString(columnName);
						colDef.setValue(bean, s == null ? 0 : Long.parseLong(s)); // auto convert null to 0
					} else if (type.isAssignableFrom(Long.class)) {
						s = resultSet.getString(columnName);
						colDef.setValue(bean, s == null ? null : Long.parseLong(s));
						//colDef.setValue(bean, resultSet.getLong(columnName));
					} else if (type == int.class) {
						s = resultSet.getString(columnName);
						colDef.setValue(bean, s == null ? 0 : Integer.parseInt(s)); // auto convert null to 0
						//colDef.setValue(bean, resultSet.getInt(columnName));
					} else if (type.isAssignableFrom(Integer.class)) {
						s = resultSet.getString(columnName);
						colDef.setValue(bean, s == null ? null : Integer.parseInt(s));
					} else if (type == boolean.class) {
						s = resultSet.getString(columnName);
						colDef.setValue(bean, s == null ? false : Boolean.parseBoolean(s)); // auto convert null to false
					} else if (type.isAssignableFrom(Boolean.class)) {
						s = resultSet.getString(columnName);
						colDef.setValue(bean, s == null ? null : Boolean.parseBoolean(s));
					} else if (type.isAssignableFrom(BigDecimal.class)) {
						s = resultSet.getString(columnName);
						colDef.setValue(bean, s == null ? null : new BigDecimal(s));
					} else if (type.isAssignableFrom(String.class)) {
						switch(colDef.declaredType) {
							case CLOB:
								Clob clob = resultSet.getClob(columnName);
								colDef.setValue(bean, clob == null ? null : clob.getSubString(1, (int) clob.length())); // int - max 2GB
								break;
							default:
								colDef.setValue(bean, resultSet.getString(columnName));
						}
					} else if (type.isAssignableFrom(Date.class)) {
						switch(colDef.declaredType){
							case DATETIMETZ:
								GregorianCalendar cal = resultSet.getGregorianCalendar(columnName);
								colDef.setValue(bean, cal == null ? null : cal.getTime());
								break;
							case DATE:
								colDef.setValue(bean, toJavaDate(resultSet.getDate(columnName)));
								break;
							default:// DATETIME: // default - datetime
								cal = resultSet.getGregorianCalendar(columnName);
								colDef.setValue(bean, cal == null ? null : cal.getTime());
						}
					} else if (type.isEnum()) {
						String sval = resultSet.getString(columnName);
						if (sval == null || "".equals(sval))
							colDef.setValue(bean, null);
						else
							colDef.setValue(bean, Enum.valueOf((Class<Enum>) type, sval));
					//} else if (type.isAssignableFrom(byte.class) && type.isArray()) {
					} else if (type.getSimpleName().equals("byte[]")) {
						switch(colDef.declaredType) {
							case BLOB:
								Blob blob = resultSet.getBlob(columnName);
								if (blob != null) {
									byte[] data = blob.getBytes(1, (int)blob.length()); // int - max 2GB
									colDef.setValue(bean, data);
								} else {
									colDef.setValue(bean, null);
								}
								break;
							default: // RAW?
								throw new OpaStructureException("DataType.BLOB is required in OpaField annotation for 'byte[]' field '"+ colDef.field.getName() +"'");
						}
					} else { // clear
						//resultSet.getObject(columnName);
					}
				} catch (IllegalArgumentException e) {
					throw new OpaStructureException("Error while assigning value to field '"+ colDef.field.getName() +"'", e);
				} catch (IllegalAccessException e) {
					throw new OpaStructureException("Error while assigning value to field '"+ colDef.field.getName() +"'", e);
				} catch (InvocationTargetException e) {
					throw new OpaStructureException("Error while assigning value to field '"+ colDef.field.getName() +"'", e);
				}
			}
			listToFill.add(bean);
		}
		return;
	}

	private static List<Object> beanToList (Object bean) throws IllegalArgumentException, IllegalAccessException {
		List<Object> row = new ArrayList<Object>();
		Class<?> clazz = bean.getClass();
		boolean allowOmitOpaField = clazz.getAnnotation(OpaTable.class).allowOmitOpaField();

		for (Field field : clazz.getDeclaredFields()) {
			field.setAccessible(true);
			if (getOpaName(field, allowOmitOpaField) == null)
				continue;
			Class<?> type = field.getType();
			if (type.isAssignableFrom(Date.class)) {
				if (field.get(bean) == null) {
					row.add(null);
				} else {
					GregorianCalendar cal = new GregorianCalendar();
					cal.setTime((Date)field.get(bean));
					row.add(cal);
				}
			} else if (type.isEnum()) { // enum as char
				String sval = field.get(bean).toString();
				row.add(sval);
			} else {
				row.add(field.get(bean)); // other types - as is
			}
		}
		return row;
	}


	static java.sql.ResultSet listToResultSet (List<?> rowList, Class<?> clazz) {
		return (java.sql.ResultSet) new OpaInputResultSet(rowList, clazz);
	}


	static ResultSetMetaData extractMetaData (Class<?> clazz) throws OpaStructureException {
		boolean allowOmitOpaField = clazz.getAnnotation(OpaTable.class).allowOmitOpaField();

		// get count
		int iCount = 0;
		for (Field field : clazz.getDeclaredFields()) {
			field.setAccessible(true);
			if (getOpaName(field, allowOmitOpaField) == null)
				continue;
			iCount++;
		}
		// fill
		ResultSetMetaData metaData;
		metaData = new ResultSetMetaData(0, iCount);
		int iPos = 0;
		for (Field field : clazz.getDeclaredFields()) {
			field.setAccessible(true);
			String name = getOpaName(field, allowOmitOpaField);
			if (name == null)
				continue;
			iPos++;
			metaData.setFieldDesc(iPos, name, 0, ablType(field).progressId);
		}

		return metaData;
	}

	// must read all temp-tables param class
	//
	static MetaSchema extractMetaSchema(Class<?> paramsClass) throws OpaStructureException {
		MetaSchema metaschema = new MetaSchema();
		int iPos = 1;
		boolean found = false;
		for (Field field : paramsClass.getDeclaredFields()) {
			field.setAccessible(true);
			OpaParam pp = field.getAnnotation(OpaParam.class);
			if (pp == null) continue; // ignore free fields

			if (pp.table().equals(Void.class)) {
				// ok, not a table declaration
			} else if (pp.table().getAnnotation(OpaTable.class) == null) { // table must have OpaTable annotation
				throw new OpaStructureException("Entity table must have @OpaTable annotation (table=" + pp.table().getName() + ")");
			} else {
				//if (tp.equals("List")) ;
				metaschema.addResultSetSchema(extractMetaData(pp.table()), iPos, pp.io().progressId);
				found = true;
			}
			iPos++;
		}

		return found ? metaschema : null;
	}


	private static DataType declaredDataType (Field field) {
		OpaField opaField = field.getAnnotation(OpaField.class);
		if (opaField != null && opaField.dataType() != null)
			return opaField.dataType();
		return DataType.AUTO;
	}

	private static DataType ablType (Field field) throws OpaStructureException {
		Class<?> type = field.getType();
		if (type.isAssignableFrom(Long.class) || type == long.class) {
			DataType datatype = declaredDataType(field);
			if (datatype == DataType.RECID)
				return datatype;
			return DataType.INT64;
		} else if (type.isAssignableFrom(Integer.class) || type == int.class) {
			return DataType.INTEGER;
		} else if (type.isAssignableFrom(String.class)) {
			DataType datatype = declaredDataType(field);
			switch(datatype) {
				case CLOB: return DataType.CLOB;
				default: return DataType.CHARACTER;
			}
		} else if (type.isAssignableFrom(BigDecimal.class)) {
			return DataType.DECIMAL;
		} else if (type.isAssignableFrom(Date.class)) {
			DataType datatype = declaredDataType(field);
			switch(datatype){
				case DATETIMETZ: return DataType.DATETIMETZ;
				case DATETIME: return DataType.DATETIME;
				case DATE: return DataType.DATE;
				default: return DataType.DATETIME;
			}
		} else if (type.isAssignableFrom(Boolean.class) || type == boolean.class) {
			return DataType.LOGICAL;
		} else if (type.isAssignableFrom(Rowid.class)) {
			return DataType.ROWID;
		} else if (type.isEnum()) {
			return DataType.CHARACTER;

		} else if (type.getSimpleName().equals("byte[]")) {
			//} else if (type.isAssignableFrom(byte.class) && type.isArray()) {
			DataType datatype = declaredDataType(field);
			switch(datatype) {
				case BLOB:
					return DataType.BLOB;
				default: // RAW?
					throw new OpaStructureException("DataType.BLOB is required in OpaField annotation for 'byte[]' field '"+ field.getName() +"'");
			}
		} else { // ??? exception
			throw new OpaStructureException("Invalid field type (field=" + field.getName() +" type=" +field.getType().getSimpleName() + ")");
		}
	}

	// return null if it is not OpaField, and name of field, if it is OpaField
	private static String getOpaName (Field field, boolean allowOmitOpaField) {
		OpaField af = field.getAnnotation(OpaField.class);
		if (allowOmitOpaField) {
			if (field.getAnnotation(OpaTransient.class) != null)
				return null;
		} else {
			if (af == null)
				return null;
		}
		return af == null || "".equals(af.name()) ? field.getName() : af.name();
	}

	private static Date toJavaDate(java.sql.Date sqlDate) {
		return sqlDate == null ? null : new Date(sqlDate.getTime());
	}


	/* ***************************************************************\
	 * OpaInputResultSet
	\* ***************************************************************/
	static class OpaInputResultSet extends InputResultSet {

		private List<?> rowList;
		private Class<?> clazz;
		private int rowNum;
		private List<Object> currentRow = null;

		public OpaInputResultSet(List<?> list, Class<?> clazz) {
			this.clazz = clazz;
			if (list == null)
				this.rowList = new ArrayList<Object>();
			else
				this.rowList = new ArrayList<Object>(list);
			rowNum = -1;
		}

		@Override
		public ResultSetMetaData getMetaData() throws SQLException {
			try {
				return TableUtils.extractMetaData(clazz);
			} catch (OpaStructureException e) {
				throw new SQLException("Cannot extract metaData", e);
			}
		}

		@Override
		public Object getObject(int pos) throws SQLException {
			try {
				if (currentRow == null)
					currentRow = TableUtils.beanToList(rowList.get(rowNum));
				return currentRow.get(pos - 1);
			} catch (IllegalArgumentException e) {
				throw new SQLException(e);
			} catch (IllegalAccessException e) {
				throw new SQLException(e);
			}
		}

		@Override
		public boolean next() throws SQLException {
			++rowNum;
			currentRow = null;
			return rowNum < rowList.size();
		}

		@Override
		public boolean previous() throws SQLException {
			--rowNum;
			currentRow = null;
			return rowNum >= 0;
		}

		//
		// auto generated (for java 1.6+)
		//
		public RowId getRowId(int columnIndex) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public RowId getRowId(String columnLabel) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateRowId(int columnIndex, RowId x) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateRowId(String columnLabel, RowId x) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public int getHoldability() throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public boolean isClosed() throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateNString(int columnIndex, String nString) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateNString(String columnLabel, String nString) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public NClob getNClob(int columnIndex) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public NClob getNClob(String columnLabel) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public SQLXML getSQLXML(int columnIndex) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public SQLXML getSQLXML(String columnLabel) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public String getNString(int columnIndex) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public String getNString(String columnLabel) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public Reader getNCharacterStream(int columnIndex) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public Reader getNCharacterStream(String columnLabel) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateCharacterStream(String columnLabel, Reader reader)
				throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateClob(int columnIndex, Reader reader) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateClob(String columnLabel, Reader reader) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateNClob(int columnIndex, Reader reader) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public void updateNClob(String columnLabel, Reader reader) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public <T> T unwrap(Class<T> iface) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}

		public boolean isWrapperFor(Class<?> iface) throws SQLException {
			throw new RuntimeException("Unimplemented InputResultSet method");
		}
	}


}
