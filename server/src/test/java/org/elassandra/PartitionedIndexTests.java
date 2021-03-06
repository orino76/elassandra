/*
 * Copyright (c) 2017 Strapdata (http://www.strapdata.com)
 * Contains some code from Elasticsearch (http://www.elastic.co)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.elassandra;

import static org.hamcrest.Matchers.equalTo;

import java.util.Locale;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ConsistencyLevel;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.junit.Test;

/**
 * Elassandra partitioned index tests.
 * @author vroyer
 *
 */
//mvn test -Pdev -pl om.strapdata.elasticsearch:elasticsearch -Dtests.seed=622A2B0618CE4676 -Dtests.class=org.elassandra.PartitionedIndexTests -Des.logger.level=ERROR -Dtests.assertion.disabled=false -Dtests.security.manager=false -Dtests.heap.size=1024m -Dtests.locale=ro-RO -Dtests.timezone=America/Toronto
public class PartitionedIndexTests extends ESSingleNodeTestCase {
    
    @Test
    public void basicPartitionFunctionTest() throws Exception {
        process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "CREATE KEYSPACE ks WITH replication = {'class': 'NetworkTopologyStrategy', '%s': '1'}",DatabaseDescriptor.getLocalDataCenter()));
        process(ConsistencyLevel.ONE,"CREATE TABLE ks.t1 ( name text, age int, primary key (name))");
        
        for(long i=20; i < 30; i++) {
            createIndex("ks_"+i, Settings.builder().put("index.keyspace","ks")
                    .put("index.partition_function", "byage ks_{0,number,##} age")
                    .build(),"t1", discoverMapping("t1"));
            ensureGreen("ks_"+i);
        }
        for(long i=20; i < 30; i++) {
            for(int j=0; j < i; j++)
                process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "INSERT INTO ks.t1 (name, age) VALUES ('name%d-%d', %d)",i,j,i));
        }
        
        for(long i=20; i < 30; i++)
            assertThat(client().prepareSearch().setIndices("ks_"+i).setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(i));
    }
    
    @Test
    public void basicStringPartitionFunctionTest() throws Exception {
        process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "CREATE KEYSPACE ks WITH replication = {'class': 'NetworkTopologyStrategy', '%s': '1'}",DatabaseDescriptor.getLocalDataCenter()));
        process(ConsistencyLevel.ONE,"CREATE TABLE ks.t1 ( name text, age int, primary key (name))");
        
        for(long i=20; i < 30; i++) {
            createIndex("ks_"+i, Settings.builder().put("index.keyspace","ks")
                    .put("index.partition_function", "byage ks_%d age")
                    .put("index.partition_function_class", "org.elassandra.index.StringPartitionFunction")
                    .build(),"t1", discoverMapping("t1"));
            ensureGreen("ks_"+i);
        }
        for(long i=20; i < 30; i++) {
            for(int j=0; j < i; j++)
                process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "INSERT INTO ks.t1 (name, age) VALUES ('name%d-%d', %d)",i,j,i));
        }
        
        for(long i=20; i < 30; i++)
            assertThat(client().prepareSearch().setIndices("ks_"+i).setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(i));
    }
    
    @Test
    public void multipleMappingTest() throws Exception {
        process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "CREATE KEYSPACE IF NOT EXISTS fb WITH replication = {'class': 'NetworkTopologyStrategy', '%s': '1'}",DatabaseDescriptor.getLocalDataCenter()));
        process(ConsistencyLevel.ONE,"CREATE TABLE fb.messages ( conversation text, num int, author text, content text, date timestamp, recipients list<text>, PRIMARY KEY (conversation, num))");
        
        createIndex("fb", Settings.builder().put("index.keyspace","fb").put("index.table","messages").build(),"messages", discoverMapping("messages"));
        ensureGreen("fb");
        
        XContentBuilder mapping2 = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("properties")
                        .startObject("content").field("type", "text").field("cql_collection","singleton").endObject()
                        .startObject("date").field("type", "date").field("cql_collection","singleton").endObject()
                        .startObject("recipients").field("type", "keyword").field("cql_collection","list").endObject()
                        .startObject("conversation").field("type", "text").field("cql_collection","singleton").endObject()
                        .startObject("author").field("type", "text").field("cql_collection","singleton").endObject()
                     .endObject()
                .endObject();
        createIndex("fb2", Settings.builder().put("index.keyspace","fb").put("index.table","messages").build(),"messages", mapping2);
        ensureGreen("fb2");
        
        client().prepareIndex("fb", "messages","\"Lisa%20Revol\",201]")
        .setSource("{\"content\": \"ouais\", \"num\": 201, \"conversation\": \"Lisa\", \"author\": \"Barth\", \"date\": 1469968740000, \"recipients\": [\"Lisa\"]}", XContentType.JSON)
        .get();

        assertThat(client().prepareSearch().setIndices("fb").setTypes("messages").get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("fb2").setTypes("messages").get().getHits().getTotalHits(), equalTo(1L));
    }
}
