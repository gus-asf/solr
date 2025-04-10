/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.core.SolrCore;
import org.apache.solr.embedded.JettySolrRunner;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class MissingSegmentRecoveryTest extends SolrCloudTestCase {
  final String collection = getClass().getSimpleName();

  Replica leader;
  Replica replica;

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(2).addConfig("conf", configset("cloud-minimal")).configure();
    useFactory("solr.StandardDirectoryFactory");
  }

  @Before
  public void setup() throws SolrServerException, IOException {
    CollectionAdminRequest.createCollection(collection, "conf", 1, 2)
        .process(cluster.getSolrClient());
    waitForState(
        "Expected a collection with one shard and two replicas", collection, clusterShape(1, 2));

    List<SolrInputDocument> docs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", i);
      docs.add(doc);
    }

    cluster.getSolrClient().add(collection, docs);
    cluster.getSolrClient().commit(collection);

    DocCollection state = getCollectionState(collection);
    leader = state.getLeader("shard1");
    replica = getRandomReplica(state.getSlice("shard1"), (r) -> !leader.equals(r));
  }

  @After
  public void teardown() throws Exception {
    if (null == leader) {
      // test did not initialize, cleanup is No-Op;
      return;
    }
    System.clearProperty("CoreInitFailedAction");
    CollectionAdminRequest.deleteCollection(collection).process(cluster.getSolrClient());
  }

  @AfterClass
  public static void teardownCluster() throws Exception {
    resetFactory();
  }

  @Test
  // 12-Jun-2018 @BadApple(bugUrl="https://issues.apache.org/jira/browse/SOLR-12028")
  public void testLeaderRecovery() throws Exception {
    System.setProperty("CoreInitFailedAction", "fromleader");

    // Simulate failure by truncating the segment_* files
    for (Path segment : getSegmentFiles(replica)) {
      truncate(segment);
    }

    // Might not need a sledge-hammer to reload the core
    JettySolrRunner jetty = cluster.getReplicaJetty(replica);
    jetty.stop();
    jetty.start();

    waitForState(
        "Expected a collection with one shard and two replicas", collection, clusterShape(1, 2));

    QueryResponse resp = cluster.getSolrClient().query(collection, new SolrQuery("*:*"));
    assertEquals(10, resp.getResults().getNumFound());
  }

  private List<Path> getSegmentFiles(Replica replica) throws IOException {
    try (SolrCore core =
        cluster.getReplicaJetty(replica).getCoreContainer().getCore(replica.getCoreName())) {
      Path indexDir = Path.of(core.getDataDir(), "index");
      try (Stream<Path> files = Files.list(indexDir)) {
        return files
            .filter((file) -> file.getFileName().toString().startsWith("segments_"))
            .toList();
      }
    }
  }

  private void truncate(Path file) throws IOException {
    Files.write(file, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
  }
}
