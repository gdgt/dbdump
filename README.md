# Dump PostgresSQL to MySQL DDL/DML
	STOP Cluster
	STOP MGMT services
# dump embedded PostgresSQL and generate MySQL compliant DDL/DML
	$ java -cp .:DbDump-1.0-SNAPSHOT-jar-with-dependencies.jar:postgresql-9.4-1200-jdbc41.jar com.gdgt.app.DbDump ./db.properties

# Stop Cloudera Manager Agent on all nodes
	[all nodes] service cloudera-scm-agent stop

# Backup embedded PostgresSQL db.properties
	cp /etc/cloudera-scm-server/db.properties /etc/cloudera-scm-server/db.properties.embedded

# Make sure scm db doesn't exist in MySQL.
	mysql -u root -ppassword -e 'drop database scm;'

# Setting up the Cloudera Manager Server Database.
	sudo /usr/share/cmf/schema/scm_prepare_database.sh mysql -h $(hostname -f) -utemp -ppassword --scm-host $(hostname -f) scm scm scm
	Full instructions here: https://www.cloudera.com/documentation/enterprise/latest/topics/cm_ig_installing_configuring_dbs.html#cmig_topic_5_2

# Start Cloudera Manager server to populate the MySQL CM Schema, followed by Stop once the schema is populated.
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
