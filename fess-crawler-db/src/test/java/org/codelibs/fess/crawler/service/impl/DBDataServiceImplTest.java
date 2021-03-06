/*
 * Copyright 2012-2016 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.crawler.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.codelibs.fess.crawler.db.exentity.AccessResult;
import org.codelibs.fess.crawler.service.impl.DBDataServiceImpl;
import org.dbflute.utflute.lastadi.LastaDiTestCase;

/**
 * @author shinsuke
 * 
 */
public class DBDataServiceImplTest extends LastaDiTestCase {
    public DBDataServiceImpl dataService;

    @Override
    protected String prepareConfigFile() {
        return "app.xml";
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        dataService.deleteAll();
    }

    public void test_insert_deleteTx() {
        final org.codelibs.fess.crawler.db.exentity.AccessResult accessResult1 = new org.codelibs.fess.crawler.db.exentity.AccessResult();
        accessResult1.setContentLength(Long.valueOf(10));
        accessResult1.setCreateTime(System.currentTimeMillis());
        accessResult1.setExecutionTime(10);
        accessResult1.setHttpStatusCode(200);
        accessResult1.setLastModified(System.currentTimeMillis());
        accessResult1.setMethod("GET");
        accessResult1.setMimeType("text/plain");
        accessResult1.setParentUrl("http://www.parent.com/");
        accessResult1.setRuleId("htmlRule");
        accessResult1.setSessionId("id1");
        accessResult1.setStatus(200);
        accessResult1.setUrl("http://www.id1.com/");

        dataService.store(accessResult1);

        final AccessResult accessResult2 = dataService.getAccessResult("id1", "http://www.id1.com/");
        assertNotNull(accessResult2);

        accessResult2.setMimeType("text/html");
        dataService.update(accessResult2);

        final AccessResult accessResult3 = dataService.getAccessResult("id1", "http://www.id1.com/");
        assertNotNull(accessResult3);
        assertEquals("text/html", accessResult3.getMimeType());

        dataService.delete("id1");

        final AccessResult accessResult4 = dataService.getAccessResult("id1", "http://www.id1.com/");
        assertNull(accessResult4);
    }

    public void test_insert_delete_multiTx() {
        final org.codelibs.fess.crawler.db.exentity.AccessResult accessResult1 = new org.codelibs.fess.crawler.db.exentity.AccessResult();
        accessResult1.setContentLength(Long.valueOf(10));
        accessResult1.setCreateTime(System.currentTimeMillis());
        accessResult1.setExecutionTime(10);
        accessResult1.setHttpStatusCode(200);
        accessResult1.setLastModified(System.currentTimeMillis());
        accessResult1.setMethod("GET");
        accessResult1.setMimeType("text/plain");
        accessResult1.setParentUrl("http://www.parent.com/");
        accessResult1.setRuleId("htmlRule");
        accessResult1.setSessionId("id1");
        accessResult1.setStatus(200);
        accessResult1.setUrl("http://www.id1.com/");

        dataService.store(accessResult1);

        final org.codelibs.fess.crawler.db.exentity.AccessResult accessResult2 = new org.codelibs.fess.crawler.db.exentity.AccessResult();
        accessResult2.setContentLength(Long.valueOf(10));
        accessResult2.setCreateTime(System.currentTimeMillis());
        accessResult2.setExecutionTime(10);
        accessResult2.setHttpStatusCode(200);
        accessResult2.setLastModified(System.currentTimeMillis());
        accessResult2.setMethod("GET");
        accessResult2.setMimeType("text/plain");
        accessResult2.setParentUrl("http://www.parent.com/");
        accessResult2.setRuleId("htmlRule");
        accessResult2.setSessionId("id2");
        accessResult2.setStatus(200);
        accessResult2.setUrl("http://www.id2.com/");

        dataService.store(accessResult2);

        final AccessResult accessResult3 = dataService.getAccessResult("id1", "http://www.id1.com/");
        final AccessResult accessResult4 = dataService.getAccessResult("id2", "http://www.id2.com/");
        assertNotNull(accessResult3);
        assertNotNull(accessResult4);

        final List<AccessResult> accessResultList = new ArrayList<AccessResult>();
        accessResult3.setMimeType("text/html");
        accessResult4.setMimeType("text/html");
        accessResultList.add(accessResult3);
        accessResultList.add(accessResult4);
        dataService.update(accessResultList);

        final AccessResult accessResult5 = dataService.getAccessResult("id1", "http://www.id1.com/");
        final AccessResult accessResult6 = dataService.getAccessResult("id2", "http://www.id2.com/");
        assertNotNull(accessResult5);
        assertNotNull(accessResult6);
        assertEquals("text/html", accessResult5.getMimeType());
        assertEquals("text/html", accessResult6.getMimeType());

        dataService.delete("id1");

        assertNull(dataService.getAccessResult("id1", "http://www.id1.com/"));
        assertNotNull(dataService.getAccessResult("id2", "http://www.id2.com/"));

        dataService.store(accessResult1);
        assertNotNull(dataService.getAccessResult("id1", "http://www.id1.com/"));

        dataService.deleteAll();

        assertNull(dataService.getAccessResult("id1", "http://www.id1.com/"));
        assertNull(dataService.getAccessResult("id2", "http://www.id2.com/"));
    }

}
