/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authz;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.search.SearchContextMissingException;
import org.elasticsearch.test.SecurityIntegTestCase;
import org.elasticsearch.xpack.security.SecurityLifecycleService;
import org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken;
import org.junit.After;

import java.util.Collections;

import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class SecurityScrollTests extends SecurityIntegTestCase {

    public void testScrollIsPerUser() throws Exception {
        assertSecurityIndexWriteable();
        securityClient().preparePutRole("scrollable")
                .addIndices(new String[] { randomAlphaOfLengthBetween(4, 12) }, new String[] { "read" }, null, null, null)
                .get();
        securityClient().preparePutUser("other", "changeme".toCharArray(), "scrollable").get();

        final int numDocs = randomIntBetween(4, 16);
        IndexRequestBuilder[] docs = new IndexRequestBuilder[numDocs];
        for (int i = 0; i < docs.length; i++) {
            docs[i] = client().prepareIndex("foo", "bar").setSource("doc", i);
        }
        indexRandom(true, docs);

        SearchResponse response = client().prepareSearch("foo")
                .setScroll(TimeValue.timeValueSeconds(5L))
                .setQuery(matchAllQuery())
                .setSize(1)
                .get();
        assertEquals(numDocs, response.getHits().getTotalHits());
        assertEquals(1, response.getHits().getHits().length);

        if (randomBoolean()) {
            response = client().prepareSearchScroll(response.getScrollId()).setScroll(TimeValue.timeValueSeconds(5L)).get();
            assertEquals(numDocs, response.getHits().getTotalHits());
            assertEquals(1, response.getHits().getHits().length);
        }

        final String scrollId = response.getScrollId();
        SearchPhaseExecutionException e = expectThrows(SearchPhaseExecutionException.class, () ->
                client()
                    .filterWithHeader(Collections.singletonMap("Authorization",
                            UsernamePasswordToken.basicAuthHeaderValue("other", new SecureString("changeme".toCharArray()))))
                    .prepareSearchScroll(scrollId)
                    .get());
        for (ShardSearchFailure failure : e.shardFailures()) {
            assertThat(ExceptionsHelper.unwrapCause(failure.getCause()), instanceOf(SearchContextMissingException.class));
        }
    }

    public void testSearchAndClearScroll() throws Exception {
        IndexRequestBuilder[] docs = new IndexRequestBuilder[randomIntBetween(20, 100)];
        for (int i = 0; i < docs.length; i++) {
            docs[i] = client().prepareIndex("idx", "type").setSource("field", "value");
        }
        indexRandom(true, docs);
        SearchResponse response = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setScroll(TimeValue.timeValueSeconds(5L))
                .setSize(randomIntBetween(1, 10)).get();

        int hits = 0;
        try {
            do {
                assertHitCount(response, docs.length);
                hits += response.getHits().getHits().length;
                response = client().prepareSearchScroll(response.getScrollId())
                        .setScroll(TimeValue.timeValueSeconds(5L)).get();
            } while (response.getHits().getHits().length != 0);

            assertThat(hits, equalTo(docs.length));
        } finally {
            clearScroll(response.getScrollId());
        }
    }

    @After
    public void wipeSecurityIndex() {
        try {
            // this is a hack to clean up the .security index since only superusers can delete it and the default test user is not a
            // superuser since the role used there is a file based role since we cannot guarantee the superuser role is always available
            internalClient().admin().indices().prepareDelete(SecurityLifecycleService.SECURITY_INDEX_NAME).get();
        } catch (IndexNotFoundException e) {
            logger.warn("security index does not exist", e);
        }
    }

    @Override
    public String transportClientUsername() {
        return this.nodeClientUsername();
    }

    @Override
    public SecureString transportClientPassword() {
        return this.nodeClientPassword();
    }
}
