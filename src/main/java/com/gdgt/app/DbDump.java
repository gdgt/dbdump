package com.gdgt.app;


import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

/**
 * DbDump
 */
public class DbDump {

  private static final String CMF_DB_PREFIX = "db.";
  private static final Logger LOG = LogManager.getLogger(DbDump.class.getName());
  private static String outputFile = "out.sql";

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
    } catch (IOException e) {
      LOG.fatal("Unable to open property file: " + args[0] + " exception: " + e);
      System.exit(1);
    }

    outputFile = props.getProperty("outputFile", "out.sql");
    LOG.info("outputFile=" + outputFile);
    try {
      File file = new File(outputFile);
      Files.deleteIfExists(file.toPath());
    } catch (IOException e) {
      LOG.fatal("Error occurred while saving output " + outputFile + " file.\n " + e.getMessage());
      System.exit(1);
    }

    // Begin work
    dumpDb(props);
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
   * Write data to file as soon as it becomes available
   * https://stackoverflow.com/a/2017922/528634
   */
  private static void writeToFile(String data) {
    try (FileWriter fw = new FileWriter(outputFile, true);
         BufferedWriter bw = new BufferedWriter(fw);
         PrintWriter out = new PrintWriter(bw)) {
      out.print(data);
    } catch (IOException e) {
      LOG.fatal(e.getStackTrace());
    }
  }

  /**
   * Dump the database table contents to an SQL statements
   */
  private static void dumpDb(Properties props) {
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
      LOG.fatal("Unsupported db.type");
      System.exit(1);
    }

    DatabaseMetaData dbMetaData = null;
    Connection dbConn = null;
    try {
      Class.forName(driverClassName);
      dbConn = DriverManager.getConnection(driverURL,
              props.getProperty(CMF_DB_PREFIX + "user"),
              props.getProperty(CMF_DB_PREFIX + "password"));
      dbMetaData = dbConn.getMetaData();
    } catch (Exception e) {
      LOG.fatal("Unable to connect to database: " + e);
    }

    Boolean toUpper = Boolean.parseBoolean(props.getProperty("tablesToUpper", "True"));
    String schema = props.getProperty("schema", "public");
    String catalog = props.getProperty("catalog", "");
    String[] tablesToSkip = {};
    if (props.containsKey("tablesToSkip")) {
      tablesToSkip = props.getProperty("tablesToSkip").trim().split(",");
    }

    for (Map.Entry<Object, Object> entry : props.entrySet()) {
      LOG.info(entry.getKey() + " = " + entry.getValue());
    }

    writeToFile("-- Generated: " +
            new SimpleDateFormat("dd/MMM/yyyy HH:mm:ss.SSS")
            .format(new java.util.Date())
    );

    try {
      try (ResultSet rs = dbMetaData.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
        if (!rs.next()) {
          LOG.error("Unable to find any tables matching: catalog=" + catalog +
                  " schema=" + schema + " tables=" + rs.getString("TABLE_NAME"));
          rs.close();
        } else {
          do {
            String tableName = toUpper ? rs.getString("TABLE_NAME").toUpperCase() :
                    rs.getString("TABLE_NAME");
            if (!Arrays.asList(tablesToSkip).contains(tableName))
              if ("TABLE".equalsIgnoreCase(rs.getString("TABLE_TYPE"))) {
                LOG.info("Dumping TABLE: " + tableName);
                dumpTable(dbConn, tableName);
              }
          } while (rs.next());
          rs.close();
        }
      }
      dbConn.close();
    } catch (SQLException e) {
      LOG.error(e.toString());
    }

    writeToFile("\n-- DONE: " +
            new SimpleDateFormat("dd/MMM/yyyy HH:mm:ss.SSS")
                    .format(new java.util.Date())
    );

    // System.out.println(data.toString());
    LOG.info("-- Dump file saved in: " + outputFile);
  }

  /**
   * Dump this particular table to string buffer
   */
  private static void dumpTable(Connection dbConn, String tableName) {
    try {
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
      writeToFile("\n\n-- Data for " + tableName + "\n");
      writeToFile("/*!40014 SET FOREIGN_KEY_CHECKS=0 */;\n");
      writeToFile("LOCK TABLES `" + tableName + "` WRITE;\n");
      writeToFile("/*!40000 ALTER TABLE `" + tableName + "` DISABLE KEYS */;\n");
      writeToFile("TRUNCATE TABLE `" + tableName + "`;\n");

      while (rs.next()) {
        writeToFile("INSERT INTO " + tableName + " (" + columnNames + ") VALUES (");

        // Mapping SQL and Java Types PG > MySQL
        // https://www.cs.mun.ca/java-api-1.5/guide/jdbc/getstart/mapping.html
        // https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/jdbc/TypeInfoCache.java#L67-L95
        for (int i = 0; i < columnCount; i++) {
          if (i > 0) {
            writeToFile(", ");
          }

          Object object = rs.getObject(i + 1);
          switch (columnTypes[i]) {
            case Types.INTEGER:
              if (object == null) {
                writeToFile("NULL");
              } else if (object instanceof Integer) {
                writeToFile(Integer.toString(rs.getInt(i + 1)));
              }
              break;
            case Types.BIGINT:
              if (object == null) {
                writeToFile("NULL");
              } else {
                writeToFile(Long.toString(rs.getLong(i + 1)));
              }
              break;
            case Types.BIT:
              if (object == null) {
                writeToFile("NULL");
              } else {
                writeToFile(Short.toString(((Boolean) rs.getObject(i + 1)) ? (short) 1 : (short) 0));
              }
              break;
            case Types.BOOLEAN:
              if (object instanceof Boolean) {
                writeToFile(Short.toString(((Boolean) rs.getObject(i + 1)) ? (short) 1 : (short) 0));
              }
              break;
            case Types.SMALLINT:
              if (object == null) {
                writeToFile("NULL");
              } else {
                writeToFile(Short.toString(rs.getShort(i + 1)));
              }
              break;
            case Types.BINARY:
              if (object == null) {
                writeToFile("NULL");
              } else {
                writeToFile("X'" + bytesToHex(rs.getBytes(i + 1)) + "'");
              }
              break;
            case Types.VARCHAR:
              if (object == null) {
                writeToFile("NULL");
              } else {
                String escaped = StringEscapeUtils.escapeJava(rs.getObject(i + 1).toString());
                writeToFile("\"" + escaped + "\"");
              }
              break;
            default:
              throw new UnsupportedOperationException("UnsupportedOperationException type: " +
                      metaData.getColumnName(i + 1) + ", " +
                      metaData.getColumnTypeName(i + 1) + ", " +
                      metaData.getColumnType(i + 1)
              );
          }
        }
        writeToFile(");\n");
      }
      writeToFile("/*!40000 ALTER TABLE `" + tableName + "` ENABLE KEYS */;\n");
      writeToFile("UNLOCK TABLES;\n");
      writeToFile("/*!40014 SET FOREIGN_KEY_CHECKS=1 */;\n");
      rs.close();
      stmt.close();
    } catch (SQLException e) {
      LOG.error("Failed to dump table " + tableName + " " + e.getMessage());
    }
  }
}
