/*
 * Copyright 2017 Couchbase, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.couchbase.mock.clientv2;
import java.net.URI;
import java.util.ArrayList;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.CouchbaseCluster;
import com.couchbase.client.java.document.JsonDocument;
import com.couchbase.client.java.env.DefaultCouchbaseEnvironment;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import junit.framework.TestCase;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.couchbase.mock.BucketConfiguration;
import org.couchbase.mock.CouchbaseMock;
import org.couchbase.mock.JsonUtils;
import org.couchbase.mock.client.MockClient;
import org.apache.http.client.methods.HttpGet;
import org.couchbase.mock.httpio.HandlerUtil;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

public class ClientTest extends TestCase {
    protected final BucketConfiguration bucketConfiguration = new BucketConfiguration();
    protected MockClient mockClient;
    protected CouchbaseMock couchbaseMock;
    protected Cluster cluster;
    protected com.couchbase.client.java.Bucket bucket;
    protected int carrierPort;
    protected int httpPort;

    protected void getPortInfo(String bucket) throws Exception {
        httpPort = couchbaseMock.getHttpPort();
        URIBuilder builder = new URIBuilder();
        builder.setScheme("http").setHost("localhost").setPort(httpPort).setPath("mock/get_mcports")
                .setParameter("bucket", bucket);
        HttpGet request = new HttpGet(builder.build());
        HttpClient client = HttpClientBuilder.create().build();
        HttpResponse response = client.execute(request);
        int status = response.getStatusLine().getStatusCode();
        if (status < 200 || status > 300) {
            throw new ClientProtocolException("Unexpected response status: " + status);
        }
        String rawBody = EntityUtils.toString(response.getEntity());
        JsonObject respObject = JsonUtils.GSON.fromJson(rawBody, JsonObject.class);
        JsonArray portsArray = respObject.getAsJsonArray("payload");
        carrierPort = portsArray.get(0).getAsInt();
    }

    protected void createMock(@NotNull String name, @NotNull String password) throws Exception {
        bucketConfiguration.numNodes = 1;
        bucketConfiguration.numReplicas = 1;
        bucketConfiguration.numVBuckets = 1024;
        bucketConfiguration.name = name;
        bucketConfiguration.type = org.couchbase.mock.Bucket.BucketType.COUCHBASE;
        bucketConfiguration.password = password;
        ArrayList<BucketConfiguration> configList = new ArrayList<BucketConfiguration>();
        configList.add(bucketConfiguration);
        couchbaseMock = new CouchbaseMock(0, configList);
        couchbaseMock.start();
        couchbaseMock.waitForStartup();
    }

    protected void createClient() {
        cluster = CouchbaseCluster.create(DefaultCouchbaseEnvironment.builder()
                .bootstrapCarrierDirectPort(carrierPort)
                .bootstrapHttpDirectPort(httpPort)
                .build() ,"couchbase://127.0.0.1");
        bucket = cluster.openBucket("default");
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        createMock("default", "");
        getPortInfo("default");
        createClient();
    }

    @Override
    protected void tearDown() throws Exception {
        if (cluster != null) {
            cluster.disconnect();
        }
        if (couchbaseMock != null) {
            couchbaseMock.stop();
        }
        if (mockClient != null) {
            mockClient.shutdown();
        }
        super.tearDown();
    }

    @Test
    public void testSimple() {
        bucket.upsert(JsonDocument.create("foo"));
        bucket.get(JsonDocument.create("foo"));
    }
}