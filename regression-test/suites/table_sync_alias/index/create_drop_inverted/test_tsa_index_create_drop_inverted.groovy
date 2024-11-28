// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
suite("test_tsa_index_create_drop_inverted") {
    def helper = new GroovyShell(new Binding(['suite': delegate]))
            .evaluate(new File("${context.config.suitePath}/../common", "helper.groovy"))

    def suffix = helper.randomSuffix()
    def tableName = "tbl_${suffix}"
    def aliasName = "alias_${suffix}"
    def test_num = 0
    def insert_num = 5

    def exist = { res -> Boolean
        return res.size() != 0
    }

    def has_count = { count ->
        return { res -> Boolean
            res.size() == count
        }
    }

    helper.set_alias(aliasName)
    sql "DROP TABLE IF EXISTS ${tableName}"
    sql """
        CREATE TABLE if NOT EXISTS ${tableName}
        (
            `test` INT,
            `id` INT,
            `value` String,
            `value1` String
        )
        ENGINE=OLAP
        UNIQUE KEY(`test`, `id`)
        DISTRIBUTED BY HASH(id) BUCKETS 1
        PROPERTIES (
            "replication_allocation" = "tag.location.default: 1",
            "binlog.enable" = "true"
        )
    """

    def values = [];
    for (int index = 0; index < insert_num; index++) {
        values.add("(${test_num}, ${index}, '${index}', '${index}')")
    }
    sql """
        INSERT INTO ${tableName} VALUES ${values.join(",")}
        """
    sql "sync"

    def show_indexes_result = sql "show indexes from ${tableName}"
    logger.info("show indexes: ${show_indexes_result}")

    helper.ccrJobCreate(tableName)
    assertTrue(helper.checkRestoreFinishTimesOf("${tableName}", 30))

    def first_job_progress = helper.get_job_progress(tableName)

    logger.info("=== Test 1: add inverted index ===")
    sql """
        CREATE INDEX idx_inverted ON ${tableName} (value) USING INVERTED
        """
    sql "sync"

    sql """ INSERT INTO ${tableName} VALUES (1, 1, "1", "1") """
    assertTrue(helper.checkSelectTimesOf(
        """ SELECT * FROM ${aliasName} """, insert_num + 1, 30))
    show_indexes_result = target_sql_return_maparray "show indexes from ${aliasName}"
    assertTrue(show_indexes_result.any {
        it['Key_name'] == 'idx_inverted' && it['Index_type'] == 'INVERTED' })

    assertTrue(helper.checkShowTimesOf("""
                                SHOW ALTER TABLE COLUMN
                                FROM ${context.dbName}
                                WHERE TableName = "${tableName}" AND State = "FINISHED"
                                """,
                                has_count(1), 30))

    show_indexes_result = sql "show indexes from ${tableName}"
    logger.info("show indexes: ${show_indexes_result}")

    sql """
        DROP INDEX idx_inverted ON ${tableName}
        """
    sql "sync"

    assertTrue(helper.checkShowTimesOf("""
                                SHOW ALTER TABLE COLUMN
                                FROM ${context.dbName}
                                WHERE TableName = "${tableName}" AND State = "FINISHED"
                                """,
                                has_count(2), 30))

    show_indexes_result = sql "show indexes from ${tableName}"
    logger.info("show indexes: ${show_indexes_result}")

    sql """ INSERT INTO ${tableName} VALUES (3, 3, "3", "3")"""

    assertTrue(helper.checkSelectTimesOf(
        """ SELECT * FROM ${aliasName} """, insert_num + 2, 30))
    show_indexes_result = target_sql_return_maparray "show indexes from ${aliasName}"
    assertTrue(show_indexes_result.isEmpty())
}