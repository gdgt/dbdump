package com.gdgt.app;


import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import java.io.*;
import java.nio.file.Files;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Properties;

/**
 * DbDump
 */
public class DbDump {
  private static final String CMF_DB_PREFIX = "db.";

  private static final Logger LOG = LogManager.getLogger(DbDump.class.getName());

  /**
   * Main method takes arguments for connection to JDBC etc.
   */
  public static void main(String[] args) {

    if (args.length != 1) {
      LOG.error("usage: DbDump <property file>");
    }
    // Right so there's one argument, we assume it's a property file
    // so lets open it
    Properties props = new Properties();
    try {
      props.load(new FileInputStream(args[0]));
      saveToFile(props, dumpDb(props));
    } catch (IOException e) {
      LOG.error("Unable to open property file: " + args[0] + " exception: " + e);
    }
  }

  /**
   * convert a byte array to a hex string in Java
   * https://stackoverflow.com/a/9855338/528634
   */
  private final static char[] hexArray = "0123456789ABCDEF".toCharArray();

  public static String bytesToHex(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for (int j = 0; j < bytes.length; j++) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 2] = hexArray[v >>> 4];
      hexChars[j * 2 + 1] = hexArray[v & 0x0F];
    }
    return new String(hexChars);
  }

  /**
   * Dump the whole results to an output file
   */
  private static void saveToFile(Properties props, StringBuffer data) {
    File file;

    try {
      file = new File(props.getProperty("outputFile"));
      Files.deleteIfExists(file.toPath());

      BufferedWriter bwr = new BufferedWriter(new FileWriter(file));
      data.append("\n-- DONE: ").append(new SimpleDateFormat("dd/MMM/yyyy HH:mm:ss.SSS")
              .format(new java.util.Date()));
      bwr.write(data.toString());
      bwr.flush();
      // System.out.println(data.toString());
      LOG.info("-- Dump file saved in: " + file.toPath());

      System.exit(0);
    } catch (IOException e) {
      LOG.error("Error occurred while saving to file.\n " + e.getMessage());
    }
  }

  /**
   * Dump the whole database to an SQL string
   */
  private static StringBuffer dumpDb(Properties props) {
    String dbHostPort = props.getProperty(CMF_DB_PREFIX + "host");
    String dbName = props.getProperty(CMF_DB_PREFIX + "name");
    String dbType = props.getProperty(CMF_DB_PREFIX + "type");

    String driverClassName = "";
    String driverURL = "";

    if (dbType.toLowerCase().equals("postgresql")) {
      driverURL = String.format("jdbc:postgresql://%s/%s", dbHostPort, dbName);
      driverClassName = "org.postgresql.Driver";
    } else {
      // NOT SUPPORTED
      LOG.error("Unsupported db.type");
      System.exit(1);
    }

    DatabaseMetaData dbMetaData;
    Connection dbConn;
    try {
      Class.forName(driverClassName);
      dbConn = DriverManager.getConnection(driverURL,
              props.getProperty(CMF_DB_PREFIX + "user"),
              props.getProperty(CMF_DB_PREFIX + "password"));
      dbMetaData = dbConn.getMetaData();
    } catch (Exception e) {
      LOG.error("Unable to connect to database: " + e);
      return null;
    }

    try {
      StringBuffer result = new StringBuffer();
      Boolean toUpper = props.getProperty("toUpper").isEmpty();
      String schema = props.getProperty("schemaPattern");

      String catalog = null;
      if (props.containsKey("catalog")) {
         catalog = props.getProperty("catalog");
      }
      String[] tablesToSkip = {};
      if (props.containsKey("tablesToSkip")) {
        tablesToSkip = props.getProperty("tablesToSkip").trim().split(",");
      }
      result.append("-- Generated: ").append(
              new SimpleDateFormat("dd/MMM/yyyy HH:mm:ss.SSS").format(new java.util.Date()));

      try (
              ResultSet rs = dbMetaData.getTables(catalog, schema, "%",
                      new String[]{"TABLE"})
      ) {
        if (!rs.next()) {
          LOG.error("Unable to find any tables matching: catalog=" + catalog +
                  " schema=" + schema + " tables=" + rs.getString("TABLE_NAME"));
          rs.close();
        } else {
          do {
            String tableName = rs.getString("TABLE_NAME");
            if (!Arrays.asList(tablesToSkip).contains(tableName))
              if ("TABLE".equalsIgnoreCase(rs.getString("TABLE_TYPE"))) {
                LOG.info("Dumping TABLE: " + tableName);
                dumpTable(dbConn, result, toUpper ? tableName : tableName.toUpperCase());
              }
          } while (rs.next());
          rs.close();
        }
      }
      dbConn.close();
      return result;
    } catch (SQLException e) {
      LOG.error(e.toString());
    }
    return null;
  }

  /**
   * Dump this particular table to string buffer
   */
  private static void dumpTable(Connection dbConn, StringBuffer result, String tableName) {
    try {
      // First we output the create table stuff
      PreparedStatement stmt = dbConn.prepareStatement("SELECT * FROM " + tableName);
      ResultSet rs = stmt.executeQuery();
      ResultSetMetaData metaData = rs.getMetaData();
      int columnCount = metaData.getColumnCount();

      int numColumns = metaData.getColumnCount();
      int[] columnTypes = new int[numColumns];
      String columnNames = "";
      for (int i = 0; i < numColumns; i++) {
        columnTypes[i] = metaData.getColumnType(i + 1);
        if (i != 0) {
          columnNames += ", ";
        }
        columnNames += metaData.getColumnName(i + 1);
      }

      // Now we can output the actual data
      result.append("\n\n-- Data for ").append(tableName).append("\n");
      result.append("/*!40014 SET FOREIGN_KEY_CHECKS=0 */;\n");
      result.append("LOCK TABLES `").append(tableName).append("` WRITE;\n");
      result.append("/*!40000 ALTER TABLE `").append(tableName).append("` DISABLE KEYS */;\n");
      result.append("TRUNCATE TABLE `").append(tableName).append("`;\n");

      while (rs.next()) {
        result.append("INSERT INTO ").append(tableName).append(" (").append(columnNames)
                .append(") VALUES (");

        // Mapping SQL and Java Types PG > MySQL
        // https://www.cs.mun.ca/java-api-1.5/guide/jdbc/getstart/mapping.html
        // https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/jdbc/TypeInfoCache.java#L67-L95
        for (int i = 0; i < columnCount; i++) {
          if (i > 0) {
            result.append(", ");
          }

          Object object = rs.getObject(i + 1);
          switch (columnTypes[i]) {
            case Types.INTEGER:
              if (object == null) {
                result.append("NULL");
              } else if (object instanceof Integer) {
                result.append(rs.getInt(i + 1));
              }
              break;
            case Types.BIGINT:
              if (object == null) {
                result.append("NULL");
              } else {
                result.append(rs.getLong(i + 1));
              }
              break;
            case Types.BIT:
              if (object == null) {
                result.append("NULL");
              } else {
                result.append(((Boolean) rs.getObject(i + 1)) ? (short) 1 : (short) 0);
              }
              break;
            case Types.BOOLEAN:
              if (object instanceof Boolean) {
                result.append(((Boolean) rs.getObject(i + 1)) ? (short) 1 : (short) 0);
              }
              break;
            case Types.SMALLINT:
              if (object == null) {
                result.append("NULL");
              } else {
                result.append(rs.getShort(i + 1));
              }
              break;
            case Types.BINARY:
              if (object == null) {
                result.append("NULL");
              } else {
                result.append("X'").append(bytesToHex(rs.getBytes(i + 1))).append("'");
              }
              break;
            case Types.VARCHAR:
              if (object == null) {
                result.append("NULL");
              } else {
                String escaped = StringEscapeUtils.escapeJava(rs.getObject(i + 1).toString());
                result.append("\"").append(escaped).append("\"");
              }
              break;
            default:
              throw new UnsupportedOperationException("UnsupportedOperationException type: " +
                      metaData.getColumnName(i+1) + ", " +
                      metaData.getColumnTypeName(i+1) + ", " +
                      metaData.getColumnType(i+1)
              );
          }
        }
        result.append(");\n");
      }

      result.append("/*!40000 ALTER TABLE `").append(tableName).append("` ENABLE KEYS */;\n");
      result.append("UNLOCK TABLES;\n");
      result.append("/*!40014 SET FOREIGN_KEY_CHECKS=1 */;\n");
      rs.close();
      stmt.close();
    } catch (SQLException e) {
      LOG.error("Unable to dump table " + tableName + " because: " + e);
    }
  }
}
