/*
 * Copyright 2013 NGDATA nv
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
package org.lilyproject.repository.impl.test;

import com.google.common.collect.Lists;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.junit.Test;
import org.lilyproject.repository.api.FieldType;
import org.lilyproject.repository.api.IdGenerator;
import org.lilyproject.repository.api.LTable;
import org.lilyproject.repository.api.QName;
import org.lilyproject.repository.api.RecordType;
import org.lilyproject.repository.api.Repository;
import org.lilyproject.repository.api.RepositoryException;
import org.lilyproject.repository.api.RepositoryManager;
import org.lilyproject.repository.api.RepositoryTable;
import org.lilyproject.repository.api.TableManager;
import org.lilyproject.repository.api.Scope;
import org.lilyproject.repository.api.TypeManager;
import org.lilyproject.repotestfw.RepositorySetup;
import org.lilyproject.tenant.model.api.TenantModel;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.lilyproject.tenant.model.api.Tenant.TenantLifecycleState;

/**
 * Tests related to tenants and tables.
 */
public class AbstractTenantTableTest {
    protected static final RepositorySetup repoSetup = new RepositorySetup();
    protected static RepositoryManager repositoryManager;

    /**
     * Trying to get a Repository for non-existing tenant should fail.
     */
    @Test(expected = RepositoryException.class)
    public void testTenantRegistrationIsRequired() throws Exception {
        repositoryManager.getRepository("funkyTenant");
    }

    /**
     * The public tenant should exist without need to create it.
     */
    @Test
    public void testPublicTenantExists() throws Exception {
        repositoryManager.getPublicRepository();
    }

    /**
     * Trying to get a non-existing table should fail.
     */
    @Test(expected = TableNotFoundException.class)
    public void testTableCreationIsRequired() throws Exception {
        Repository repository = repositoryManager.getPublicRepository();
        repository.getTable("aNonExistingTable");
    }

    /**
     * Tests that two tenants can have a table with the same name containing records with the
     * same id's, without conflict.
     */
    @Test
    public void testTwoTenantsSameTableSameId() throws Exception {
        TenantModel tenantModel = repoSetup.getTenantModel();
        tenantModel.create("company1");
        tenantModel.create("company2");
        assertTrue(tenantModel.waitUntilTenantInState("company1", TenantLifecycleState.ACTIVE, 60000L));
        assertTrue(tenantModel.waitUntilTenantInState("company2", TenantLifecycleState.ACTIVE, 60000L));

        TypeManager typeMgr = repositoryManager.getTypeManager();
        FieldType fieldType1 = typeMgr.createFieldType("STRING", new QName("test", "field1"), Scope.NON_VERSIONED);
        RecordType recordType1 = typeMgr.recordTypeBuilder()
                .name(new QName("test", "rt1"))
                .field(fieldType1.getId(), false)
                .create();

        List<String> tenants = Lists.newArrayList("company1", "company2", "public");

        for (String tenant : tenants) {
            Repository repo = repositoryManager.getRepository(tenant);
            repo.getTableManager().createTable("mytable");
            LTable table = repo.getTable("mytable");
            table.recordBuilder()
                    .id("id1")
                    .recordType(recordType1.getName())
                    .field(fieldType1.getName(), tenant + "-value1")
                    .create();
        }

        IdGenerator idGenerator = repositoryManager.getIdGenerator();

        for (String tenant : tenants) {
            Repository repo = repositoryManager.getRepository(tenant);
            LTable table = repo.getTable("mytable");
            assertEquals(tenant + "-value1", table.read(idGenerator.newRecordId("id1")).getField(fieldType1.getName()));
        }
    }

    @Test(expected = Exception.class)
    public void testRecordTableCannotBeDeleted() throws Exception {
        String tenantName = "testRecordTableCannotBeDeleted";
        TableManager tableManager = repositoryManager.getRepository(tenantName).getTableManager();
        tableManager.dropTable("record");
    }

    @Test
    public void testBasicTableManagement() throws Exception {
        String tenantName = "tablemgmttest";
        TenantModel tenantModel = repoSetup.getTenantModel();
        tenantModel.create(tenantName);
        assertTrue(tenantModel.waitUntilTenantInState(tenantName, TenantLifecycleState.ACTIVE, 60000L));

        Repository repository = repositoryManager.getRepository(tenantName);
        TableManager tableManager = repository.getTableManager();

        List<RepositoryTable> tables = tableManager.getTables();
        assertEquals(1, tables.size());
        assertEquals("record", tables.get(0).getName());

        tableManager.createTable("foo");

        assertEquals(2, tableManager.getTables().size());

        assertTrue(tableManager.tableExists("foo"));

        repository.getTable("foo");

        tableManager.dropTable("foo");

        assertEquals(1, tableManager.getTables().size());
    }
}
