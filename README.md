# Dump PostgresSQL to MySQL
	STOP Cluster
	STOP MGMT services

# Stop Cloudera Manager Agent on all nodes
	[all nodes] service cloudera-scm-agent stop

# Backup embedded PostgresSQL db.properties
	cp /etc/cloudera-scm-server/db.properties /etc/cloudera-scm-server/db.properties.embedded

# Dump embedded PostgresSQL and generate MySQL compliant INSERT statement
	service cloudera-scm-server stop
	$ java -cp .:DbDump-1.0-SNAPSHOT-jar-with-dependencies.jar:postgresql-9.4-1200-jdbc41.jar com.gdgt.app.DbDump ./db.properties

# Make sure SCM db doesn't exist in MySQL.
	mysql -u root -ppassword -e 'show databases;'
	Note: If the SCM database exists, you will need to drop the database [taking necessary backup]
	mysql -u root -ppassword -e 'drop database _the_name_of_the_scm_database_here;'

# Prepare SCM/Cloudera Manager Server Database.
	sudo /usr/share/cmf/schema/scm_prepare_database.sh mysql -h $(hostname -f) -utemp -ppassword --scm-host $(hostname -f) scm scm scm
	Full instructions here: https://www.cloudera.com/documentation/enterprise/latest/topics/cm_ig_installing_configuring_dbs.html#cmig_topic_5_2

# Start Cloudera Manager server to populate the MySQL SCM Schema, then Stop the Cloudera Manager Server once the schema is populated.
	service cloudera-scm-server restart
	while ! (exec 6<>/dev/tcp/$(hostname)/7180) 2> /dev/null ; do echo 'Waiting for Cloudera Manager to start accepting connections...'; sleep 10; done
	service cloudera-scm-server stop

# Import the dump using MySQL CLI and restart Cloudera Manager Server.
	mysql -u scm -pscm scm < out.sql
	_OR_ to monitor the progress of the import
	yum install -y pv
	pv out.sql | mysql -uscm -pscm scm
	service cloudera-scm-server restart

# Start Cloudera Manager Agent on all nodes
	[all nodes] service cloudera-scm-agent start

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
    toUpper=True
    schemaPattern=public
    # tablesToSkip for scm Databases, e.g. audits
    # tablesToSkip=audits
    # catalog=

# DB Mappings

|		|	PostgreSQL	|		|	MySQL	|		|
|-------|---------------|-------|-----------|-------|
|	1	|	BIGINT	|	64-bit integer	|	BIGINT	|	64-bit integer	|
|	2	|	BIT(n)	|	Fixed-length bit string	|	BIT(n)	|	Fixed-length bit string, 1 ⇐ n ⇐ 64	|
|	3	|	BOOLEAN, BOOL	|	True, false or NULL	|	BOOLEAN, BOOL	|	0 or 1 value; NULL is not allowed	|
|	4	|	BYTEA	|	Variable-length binary data, ⇐ 2G	|	MEDIUMBLOB	|	Binary large object, ⇐ 16M	|
|	5	|	INTEGER, INT	|	32-bit integer	|	INT, INTEGER	|	32-bit integer	|
|	6	|	SMALLINT	|	16-bit integer	|	SMALLINT	|	16-bit integer	|
|	7	|	TEXT	|	Variable-length character data, ⇐ 1G	|	LONGTEXT	|	Character large object, ⇐ 4G	|
|	8	|	VARCHAR(n)	|	Variable-length string, 1 ⇐ n ⇐ 1G	|	VARCHAR(n)	|	Variable-length string, 1 ⇐ n ⇐ 65535	|
