# High-level steps to migrate Cloudera Manager Server data from embedded PostgreSQL database to external MySQL database server.
1. Download the "DbDump-1.0-SNAPSHOT-jar-with-dependencies.jar" from [0]
2. Stop all the Cluster Services/Roles
3. Stop the Cloudera Manager Server and on all nodes stop Cloudera Manager Agent
4. Export the Cloudera Manager SCM database data from the embedded PostgresSQL database
5. Backup /etc/cloudera-scm-server/db.properties
6. Update /etc/cloudera-scm-server/db.properties file to use the external database
7. Start Cloudera Manager server to populate the MySQL SCM Schema, then Stop the Cloudera Manager Server once the schema is populated
8. Dump embedded PostgresSQL and generate MySQL compliant INSERT statement
9. Import the dump using MySQL CLI and restart Cloudera Manager Server
10. Log into Cloudera Manager and start the Cloudera Management Services and all the Cluster Services/Roles
    10.1 Start the Cloudera Manager Server and on all nodes stop Cloudera Manager Agent
    10.2 Start all the Cluster Services/Roles

`[0] https://github.com/gdgt/dbdump/releases/download/1.0-SNAPSHOT/DbDump-1.0-SNAPSHOT-jar-with-dependencies.jar`

# Detailed steps for migrating Cloudera Manager embedded PostgresSQL database data to External MySQL database
## 1. Download the "DbDump-1.0-SNAPSHOT-jar-with-dependencies.jar" from [0]
    [cm-host]# wget https://github.com/gdgt/dbdump/releases/download/1.0-SNAPSHOT/DbDump-1.0-SNAPSHOT-jar-with-dependencies.jar

## 2. Stop all the Cluster Services/Roles
    STOP Cluster
    STOP MGMT services

## 3. Stop the Cloudera Manager Server and on all nodes stop Cloudera Manager Agent
    [cm-host]# service cloudera-scm-server stop
    [all nodes]# service cloudera-scm-agent stop

## 4. Export the Cloudera Manager SCM database data from the embedded PostgresSQL database
    # retrieve the generated PostgresSQL DBA user 'cloudera-scm' password
    [cm-host]# head -1 /var/lib/cloudera-scm-server-db/data/generated_password.txt
    # dump the data using the pg_dump tool
    [cm-host]# sudo -u cloudera-scm pg_dump -F c -h localhost -p 7432 -Ucloudera-scm scm > /tmp/cm_server.$(date +"+%Y%m%d_%H%M%S").pg_dump

## 5. Backup /etc/cloudera-scm-server/db.properties
    # cp /etc/cloudera-scm-server/db.properties /etc/cloudera-scm-server/db.properties.embedded

## 6. Update /etc/cloudera-scm-server/db.properties file to use the external database
    # Prepare SCM/Cloudera Manager Server Database.
    Full instructions here:
    https://www.cloudera.com/documentation/enterprise/latest/topics/cm_ig_installing_configuring_dbs.html#cmig_topic_5_2
    [cm-host]# sudo /usr/share/cmf/schema/scm_prepare_database.sh mysql -h {MySQL database host} -utemp -ppassword --scm-host {SCM server's hostname} database username [password]

## 7. Start Cloudera Manager server to populate the MySQL SCM Schema, then Stop the Cloudera Manager Server once the schema is populated.
    [cm-host]# service cloudera-scm-server restart
    [cm-host]# while ! (exec 6<>/dev/tcp/$(hostname)/7180) 2> /dev/null ; do echo 'Waiting for Cloudera Manager to start accepting connections...'; sleep 10; done
    [cm-host]# service cloudera-scm-server stop

## 8. Dump embedded PostgresSQL and generate MySQL compliant INSERT statement
    [cm-host]# service cloudera-scm-server stop
    # Generate db.properties configuration file, which will be used for DbDump-1.0-SNAPSHOT-jar-with-dependencies.jar
    [cm-host]# grep 'name\|type\|host\|user\|password' /etc/cloudera-scm-server/db.properties.embedded | sed "s/com.cloudera.cmf.//g" > /tmp/db.properties
    [cm-host]# java -cp .:DbDump-1.0-SNAPSHOT-jar-with-dependencies.jar:postgresql-9.4-1200-jdbc41.jar com.gdgt.app.DbDump /tmp/db.properties
    ...
    # Once the dump complete the file will be saved in out.sql which can be used to restore it in mysql
    ...
    INFO  | 2018-04-19 00:00:00 | [main] app.DbDump (DbDump.java:164) - -- Dump file saved in: out.sql

## 9. Import the dump using MySQL CLI and restart Cloudera Manager Server.
    [mysql-host]# mysql -u scm -pscm scm < out.sql
    _OR_ to monitor the progress of the import
    [mysql-host]# yum install -y pv
    [mysql-host]# pv out.sql | mysql -uscm -pscm scm
    [cm-host]# service cloudera-scm-server restart

## 10. Log into Cloudera Manager and start the Cloudera Management Services and all the Cluster Services/Roles
### 10.1 Start the Cloudera Manager Server and on all nodes start Cloudera Manager Agent
    [cm-host]# service cloudera-scm-server start
    [all nodes]# service cloudera-scm-agent start
### 10.2 Start all the Cluster Services/Roles
    START MGMT services
    START Cluster

*Optional: if you have Kerberos enabled Cluster. Confirm that principals are generated. If there are no principals, simply import kerberos account manager credentials again and regenerate credentials*
`Cloudera Manager UI> Administration> Security> Kerberos Credentials> Import Kerberos Account Manager Credentials
`

# db.properties example contents
    # Properties file for controlling com.gdgt.app.DbDump
    # Driver information as appear in /etc/cloudera-scm-server/db.properties
    # ==================
    # These are mandatory, you must provide appropriate values
    db.type=postgresql
    db.host=embedded.postgresql.host:7432
    db.name=scm
    db.user=scm
    db.password=Rv3qXmCB8G
    outputFile=out.sql
    tablesToUpper=True
    schemaPattern=public
    # tablesToSkip for scm Databases, e.g. audits
    # tablesToSkip=audits
    # catalog=

# DB Mappings

|		|	PostgreSQL	|		|	MySQL	|		|
|:-------:|---------------:|:-------|-----------:|:-------|
|	1	|	BIGINT	|	64-bit integer	|	BIGINT	|	64-bit integer	|
|	2	|	BIT(n)	|	Fixed-length bit string	|	BIT(n)	|	Fixed-length bit string, 1 ⇐ n ⇐ 64	|
|	3	|	BOOLEAN, BOOL	|	True, false or NULL	|	TINYINT	|	8-bit integer	|
|	4	|	BYTEA	|	Variable-length binary data, ⇐ 2G	|	MEDIUMBLOB	|	Binary large object, ⇐ 16M	|
|	5	|	INTEGER, INT	|	32-bit integer	|	INT, INTEGER	|	32-bit integer	|
|	6	|	SMALLINT	|	16-bit integer	|	SMALLINT	|	16-bit integer	|
|	7	|	TEXT	|	Variable-length character data, ⇐ 1G	|	LONGTEXT 	|	Character large object, ⇐ 4G	|
|       |           |                                           |   MEDIUMTEXT	|   Character large object, ⇐ 16M
|	8	|	VARCHAR(n)	|	Variable-length string, 1 ⇐ n ⇐ 1G	|	VARCHAR(n)	|	Variable-length string, 1 ⇐ n ⇐ 65535	|
