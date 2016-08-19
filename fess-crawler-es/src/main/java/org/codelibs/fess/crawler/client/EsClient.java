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
package org.codelibs.fess.crawler.client;

import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.crawler.exception.CrawlerSystemException;
import org.codelibs.fess.crawler.exception.EsAccessException;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.*;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.count.CountRequest;
import org.elasticsearch.action.count.CountRequestBuilder;
import org.elasticsearch.action.count.CountResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.exists.ExistsRequest;
import org.elasticsearch.action.exists.ExistsRequestBuilder;
import org.elasticsearch.action.exists.ExistsResponse;
import org.elasticsearch.action.explain.ExplainRequest;
import org.elasticsearch.action.explain.ExplainRequestBuilder;
import org.elasticsearch.action.explain.ExplainResponse;
import org.elasticsearch.action.fieldstats.FieldStatsRequest;
import org.elasticsearch.action.fieldstats.FieldStatsRequestBuilder;
import org.elasticsearch.action.fieldstats.FieldStatsResponse;
import org.elasticsearch.action.get.*;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.indexedscripts.delete.DeleteIndexedScriptRequest;
import org.elasticsearch.action.indexedscripts.delete.DeleteIndexedScriptRequestBuilder;
import org.elasticsearch.action.indexedscripts.delete.DeleteIndexedScriptResponse;
import org.elasticsearch.action.indexedscripts.get.GetIndexedScriptRequest;
import org.elasticsearch.action.indexedscripts.get.GetIndexedScriptRequestBuilder;
import org.elasticsearch.action.indexedscripts.get.GetIndexedScriptResponse;
import org.elasticsearch.action.indexedscripts.put.PutIndexedScriptRequest;
import org.elasticsearch.action.indexedscripts.put.PutIndexedScriptRequestBuilder;
import org.elasticsearch.action.indexedscripts.put.PutIndexedScriptResponse;
import org.elasticsearch.action.percolate.*;
import org.elasticsearch.action.search.*;
import org.elasticsearch.action.suggest.SuggestRequest;
import org.elasticsearch.action.suggest.SuggestRequestBuilder;
import org.elasticsearch.action.suggest.SuggestResponse;
import org.elasticsearch.action.termvectors.*;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateRequestBuilder;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.AdminClient;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.support.Headers;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.threadpool.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class EsClient implements Client {
    public static final String TRANSPORT_ADDRESSES = "crawler.es.transport_addresses";

    public static final String CLUSTER_NAME = "crawler.es.cluster_name";

    public static final String TARGET_INDICES = "crawler.es.target_indices";

    private static final Logger logger = LoggerFactory.getLogger(EsClient.class);

    protected TransportClient client;

    protected String clusterName;

    protected String[] addresses;

    protected List<OnConnectListener> onConnectListenerList = new ArrayList<>();

    private volatile boolean connected;

    protected Scroll scrollForDelete = new Scroll(TimeValue.timeValueMinutes(1));

    protected int sizeForDelete = 10;

    protected long retryInterval = 60 * 1000;

    protected int maxRetryCount = 10;

    protected long connTimeout = 180 * 1000;

    protected String searchPreference;

    protected String[] targetIndices;

    public EsClient() {
        clusterName = System.getProperty(CLUSTER_NAME, "liteshell-data");
        addresses = Arrays.stream(System.getProperty(TRANSPORT_ADDRESSES, "do.litehsell.io:9300").split(",")).map(v -> v.trim())
                .toArray(n -> new String[n]);
        final String targets = System.getProperty(TARGET_INDICES);
        if (StringUtil.isNotBlank(targets)) {
            targetIndices = Arrays.stream(targets.split(",")).map(v -> v.trim()).toArray(n -> new String[n]);
        }

        logger.info("XXXXXX=>"+System.getProperty(CLUSTER_NAME));
        logger.info("XXXXXX=>"+System.getProperty(TRANSPORT_ADDRESSES));
    }

    public void setClusterName(final String clusterName) {
        this.clusterName = clusterName;
    }

    public void setAddresses(final String[] addresses) {
        this.addresses = addresses;
    }

    public void addOnConnectListener(final OnConnectListener listener) {
        onConnectListenerList.add(listener);
    }

    public boolean connected() {
        return connected;
    }

    public void connect() {
        destroy();
        final Settings.Builder settingsBuilder =
                Settings.settingsBuilder()
                        .put("cluster.name", StringUtil.isBlank(clusterName) ? "liteshell-data" : clusterName)
                        .put("transport.sniff", false)
                        .put("action.bulk.compress", false).put("shield.transport.ssl", false)
                        .put("request.headers.X-Found-Cluster", "liteshell-data").put("shield.user", "vlad:Lapt3s1mls");
        final Settings settings = settingsBuilder.build();
        client =TransportClient.builder().addPlugin(ShieldPlugin.class).settings(settings).build();
        // TODO adding shield
        Arrays.stream(addresses).forEach(address -> {
            final String[] values = address.split(":");
            String hostname;
            int port = 9300;
            if (values.length == 1) {
                hostname = values[0];
            } else if (values.length == 2) {
                hostname = values[0];
                port = Integer.parseInt(values[1]);
            } else {
                throw new CrawlerSystemException("Invalid address: " + address);
            }
            try {
                client.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(hostname), port));
            } catch (final Exception e) {
                throw new CrawlerSystemException("Unknown host: " + address);
            }
            logger.info("Connected to " + hostname + ":" + port);
        });

        final ClusterHealthResponse healthResponse = get(c -> c.admin().cluster().prepareHealth(targetIndices).setWaitForYellowStatus().execute());
        if (!healthResponse.isTimedOut()) {
            onConnectListenerList.forEach(l -> {
                try {
                    l.onConnect();
                } catch (final Exception e) {
                    logger.warn("Failed to invoke " + l, e);
                }
            });

            connected = true;
        } else {
            logger.warn("Could not connect to " + clusterName + ":" + String.join(",", addresses));
        }
    }

    public <T> T get(Function<EsClient, ListenableActionFuture<T>> func) {
        int retryCount = 0;
        while (true) {
            try {
                return func.apply(this).actionGet(connTimeout, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                if (e instanceof IndexNotFoundException) {
                    logger.debug("IndexNotFoundException.");
                    throw e;
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("Failed to actionGet. count:" + retryCount, e);
                }
                if (retryCount > maxRetryCount) {
                    logger.info("Failed to actionGet. All retry failure.", e);
                    throw e;
                }

                retryCount++;
                try {
                    Thread.sleep(retryInterval);
                } catch (InterruptedException ie) {
                    throw e;
                }
            }
        }
    }

    @PreDestroy
    public void destroy() {
        if (client != null) {
            try {
                client.close();
            } catch (final ElasticsearchException e) {
                logger.warn("Failed to close client.", e);
            }
            logger.info("Disconnected to " + clusterName + ":" + String.join(",", addresses));
        }
        connected = false;
    }

    @Override
    public ThreadPool threadPool() {
        return client.threadPool();
    }

    @Override
    public AdminClient admin() {
        return client.admin();
    }

    @Override
    public ActionFuture<IndexResponse> index(final IndexRequest request) {
        return client.index(request);
    }

    @Override
    public void index(final IndexRequest request, final ActionListener<IndexResponse> listener) {
        client.index(request, listener);
    }

    @Override
    public IndexRequestBuilder prepareIndex() {
        return client.prepareIndex();
    }

    @Override
    public ActionFuture<UpdateResponse> update(final UpdateRequest request) {
        return client.update(request);
    }

    @Override
    public void update(final UpdateRequest request, final ActionListener<UpdateResponse> listener) {
        client.update(request, listener);
    }

    @Override
    public UpdateRequestBuilder prepareUpdate() {
        return client.prepareUpdate();
    }

    @Override
    public UpdateRequestBuilder prepareUpdate(final String index, final String type, final String id) {
        return client.prepareUpdate(index, type, id);
    }

    @Override
    public IndexRequestBuilder prepareIndex(final String index, final String type) {
        return client.prepareIndex(index, type);
    }

    @Override
    public IndexRequestBuilder prepareIndex(final String index, final String type, final String id) {
        return client.prepareIndex(index, type, id);
    }

    @Override
    public ActionFuture<DeleteResponse> delete(final DeleteRequest request) {
        return client.delete(request);
    }

    @Override
    public void delete(final DeleteRequest request, final ActionListener<DeleteResponse> listener) {
        client.delete(request, listener);
    }

    @Override
    public DeleteRequestBuilder prepareDelete() {
        return client.prepareDelete();
    }

    @Override
    public DeleteRequestBuilder prepareDelete(final String index, final String type, final String id) {
        return client.prepareDelete(index, type, id);
    }

    @Override
    public ActionFuture<BulkResponse> bulk(final BulkRequest request) {
        return client.bulk(request);
    }

    @Override
    public void bulk(final BulkRequest request, final ActionListener<BulkResponse> listener) {
        client.bulk(request, listener);
    }

    @Override
    public BulkRequestBuilder prepareBulk() {
        return client.prepareBulk();
    }

    @Override
    public ActionFuture<GetResponse> get(final GetRequest request) {
        return client.get(request);
    }

    @Override
    public void get(final GetRequest request, final ActionListener<GetResponse> listener) {
        client.get(request, listener);
    }

    @Override
    public GetRequestBuilder prepareGet() {
        return client.prepareGet();
    }

    @Override
    public GetRequestBuilder prepareGet(final String index, final String type, final String id) {
        return client.prepareGet(index, type, id);
    }

    @Override
    public PutIndexedScriptRequestBuilder preparePutIndexedScript() {
        return client.preparePutIndexedScript();
    }

    @Override
    public PutIndexedScriptRequestBuilder preparePutIndexedScript(final String scriptLang, final String id, final String source) {
        return client.preparePutIndexedScript(scriptLang, id, source);
    }

    @Override
    public void deleteIndexedScript(final DeleteIndexedScriptRequest request, final ActionListener<DeleteIndexedScriptResponse> listener) {
        client.deleteIndexedScript(request, listener);
    }

    @Override
    public ActionFuture<DeleteIndexedScriptResponse> deleteIndexedScript(final DeleteIndexedScriptRequest request) {
        return client.deleteIndexedScript(request);
    }

    @Override
    public DeleteIndexedScriptRequestBuilder prepareDeleteIndexedScript() {
        return client.prepareDeleteIndexedScript();
    }

    @Override
    public DeleteIndexedScriptRequestBuilder prepareDeleteIndexedScript(final String scriptLang, final String id) {
        return client.prepareDeleteIndexedScript(scriptLang, id);
    }

    @Override
    public void putIndexedScript(final PutIndexedScriptRequest request, final ActionListener<PutIndexedScriptResponse> listener) {
        client.putIndexedScript(request, listener);
    }

    @Override
    public ActionFuture<PutIndexedScriptResponse> putIndexedScript(final PutIndexedScriptRequest request) {
        return client.putIndexedScript(request);
    }

    @Override
    public GetIndexedScriptRequestBuilder prepareGetIndexedScript() {
        return client.prepareGetIndexedScript();
    }

    @Override
    public GetIndexedScriptRequestBuilder prepareGetIndexedScript(final String scriptLang, final String id) {
        return client.prepareGetIndexedScript(scriptLang, id);
    }

    @Override
    public void getIndexedScript(final GetIndexedScriptRequest request, final ActionListener<GetIndexedScriptResponse> listener) {
        client.getIndexedScript(request, listener);
    }

    @Override
    public ActionFuture<GetIndexedScriptResponse> getIndexedScript(final GetIndexedScriptRequest request) {
        return client.getIndexedScript(request);
    }

    @Override
    public ActionFuture<MultiGetResponse> multiGet(final MultiGetRequest request) {
        return client.multiGet(request);
    }

    @Override
    public void multiGet(final MultiGetRequest request, final ActionListener<MultiGetResponse> listener) {
        client.multiGet(request, listener);
    }

    @Override
    public MultiGetRequestBuilder prepareMultiGet() {
        return client.prepareMultiGet();
    }

    @SuppressWarnings("deprecation")
    @Override
    public ActionFuture<CountResponse> count(final CountRequest request) {
        return client.count(request);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void count(final CountRequest request, final ActionListener<CountResponse> listener) {
        client.count(request, listener);
    }

    @SuppressWarnings("deprecation")
    @Override
    public CountRequestBuilder prepareCount(final String... indices) {
        return client.prepareCount(indices);
    }

    @SuppressWarnings("deprecation")
    @Override
    public ActionFuture<ExistsResponse> exists(final ExistsRequest request) {
        return client.exists(request);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void exists(final ExistsRequest request, final ActionListener<ExistsResponse> listener) {
        client.exists(request, listener);
    }

    @SuppressWarnings("deprecation")
    @Override
    public ExistsRequestBuilder prepareExists(final String... indices) {
        return client.prepareExists(indices);
    }

    @Override
    public ActionFuture<SuggestResponse> suggest(final SuggestRequest request) {
        return client.suggest(request);
    }

    @Override
    public void suggest(final SuggestRequest request, final ActionListener<SuggestResponse> listener) {
        client.suggest(request, listener);
    }

    @Override
    public SuggestRequestBuilder prepareSuggest(final String... indices) {
        return client.prepareSuggest(indices);
    }

    @Override
    public ActionFuture<SearchResponse> search(final SearchRequest request) {
        return client.search(request);
    }

    @Override
    public void search(final SearchRequest request, final ActionListener<SearchResponse> listener) {
        client.search(request, listener);
    }

    @Override
    public SearchRequestBuilder prepareSearch(final String... indices) {
        SearchRequestBuilder builder = client.prepareSearch(indices);
        if (searchPreference != null) {
            builder.setPreference(searchPreference);
        }
        return builder;
    }

    @Override
    public ActionFuture<SearchResponse> searchScroll(final SearchScrollRequest request) {
        return client.searchScroll(request);
    }

    @Override
    public void searchScroll(final SearchScrollRequest request, final ActionListener<SearchResponse> listener) {
        client.searchScroll(request, listener);
    }

    @Override
    public SearchScrollRequestBuilder prepareSearchScroll(final String scrollId) {
        return client.prepareSearchScroll(scrollId);
    }

    @Override
    public ActionFuture<MultiSearchResponse> multiSearch(final MultiSearchRequest request) {
        return client.multiSearch(request);
    }

    @Override
    public void multiSearch(final MultiSearchRequest request, final ActionListener<MultiSearchResponse> listener) {
        client.multiSearch(request, listener);
    }

    @Override
    public MultiSearchRequestBuilder prepareMultiSearch() {
        return client.prepareMultiSearch();
    }

    @Override
    public ActionFuture<PercolateResponse> percolate(final PercolateRequest request) {
        return client.percolate(request);
    }

    @Override
    public void percolate(final PercolateRequest request, final ActionListener<PercolateResponse> listener) {
        client.percolate(request, listener);
    }

    @Override
    public PercolateRequestBuilder preparePercolate() {
        return client.preparePercolate();
    }

    @Override
    public ActionFuture<MultiPercolateResponse> multiPercolate(final MultiPercolateRequest request) {
        return client.multiPercolate(request);
    }

    @Override
    public void multiPercolate(final MultiPercolateRequest request, final ActionListener<MultiPercolateResponse> listener) {
        client.multiPercolate(request, listener);
    }

    @Override
    public MultiPercolateRequestBuilder prepareMultiPercolate() {
        return client.prepareMultiPercolate();
    }

    @Override
    public ExplainRequestBuilder prepareExplain(final String index, final String type, final String id) {
        return client.prepareExplain(index, type, id);
    }

    @Override
    public ActionFuture<ExplainResponse> explain(final ExplainRequest request) {
        return client.explain(request);
    }

    @Override
    public void explain(final ExplainRequest request, final ActionListener<ExplainResponse> listener) {
        client.explain(request, listener);
    }

    @Override
    public ClearScrollRequestBuilder prepareClearScroll() {
        return client.prepareClearScroll();
    }

    @Override
    public ActionFuture<ClearScrollResponse> clearScroll(final ClearScrollRequest request) {
        return client.clearScroll(request);
    }

    @Override
    public void clearScroll(final ClearScrollRequest request, final ActionListener<ClearScrollResponse> listener) {
        client.clearScroll(request, listener);
    }

    @Override
    public Settings settings() {
        return client.settings();
    }

    @Override
    public FieldStatsRequestBuilder prepareFieldStats() {
        return client.prepareFieldStats();
    }

    @Override
    public ActionFuture<FieldStatsResponse> fieldStats(final FieldStatsRequest request) {
        return client.fieldStats(request);
    }

    @Override
    public void fieldStats(final FieldStatsRequest request, final ActionListener<FieldStatsResponse> listener) {
        client.fieldStats(request, listener);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>> ActionFuture<Response> execute(
            Action<Request, Response, RequestBuilder> action, Request request) {
        return client.execute(action, request);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>> void execute(
            Action<Request, Response, RequestBuilder> action, Request request, ActionListener<Response> listener) {
        client.execute(action, request, listener);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>> RequestBuilder prepareExecute(
            Action<Request, Response, RequestBuilder> action) {
        return client.prepareExecute(action);
    }

    @Override
    public void close() {
        client.close();
    }

    @Override
    public ActionFuture<TermVectorsResponse> termVectors(TermVectorsRequest request) {
        return client.termVectors(request);
    }

    @Override
    public void termVectors(TermVectorsRequest request, ActionListener<TermVectorsResponse> listener) {
        client.termVectors(request, listener);
    }

    @Override
    public TermVectorsRequestBuilder prepareTermVectors() {
        return client.prepareTermVectors();
    }

    @Override
    public TermVectorsRequestBuilder prepareTermVectors(String index, String type, String id) {
        return client.prepareTermVectors(index, type, id);
    }

    @Override
    @Deprecated
    public ActionFuture<TermVectorsResponse> termVector(TermVectorsRequest request) {
        return client.termVector(request);
    }

    @Override
    @Deprecated
    public void termVector(TermVectorsRequest request, ActionListener<TermVectorsResponse> listener) {
        client.termVector(request, listener);
    }

    @Override
    @Deprecated
    public TermVectorsRequestBuilder prepareTermVector() {
        return client.prepareTermVector();
    }

    @Override
    public TermVectorsRequestBuilder prepareTermVector(String index, String type, String id) {
        return client.prepareTermVectors(index, type, id);
    }

    @Override
    public ActionFuture<MultiTermVectorsResponse> multiTermVectors(MultiTermVectorsRequest request) {
        return client.multiTermVectors(request);
    }

    @Override
    public void multiTermVectors(MultiTermVectorsRequest request, ActionListener<MultiTermVectorsResponse> listener) {
        client.multiTermVectors(request, listener);
    }

    @Override
    public MultiTermVectorsRequestBuilder prepareMultiTermVectors() {
        return client.prepareMultiTermVectors();
    }

    @Override
    public Headers headers() {
        return client.headers();
    }

    public int deleteByQuery(final String index, final String type, final QueryBuilder queryBuilder) {
        boolean scrolling = true;
        int count = 0;
        String scrollId = null;
        while (scrolling) {
            final SearchResponse scrollResponse;
            if (scrollId == null) {
                scrollResponse = get(c -> c.prepareSearch(index).setTypes(type).setScroll(scrollForDelete).setSize(sizeForDelete)
                        .setQuery(queryBuilder).execute());
            } else {
                final String sid = scrollId;
                scrollResponse = get(c -> c.prepareSearchScroll(sid).setScroll(scrollForDelete).execute());
            }
            final SearchHit[] hits = scrollResponse.getHits().getHits();
            if (hits.length == 0) {
                scrolling = false;
                break;
            }

            scrollId = scrollResponse.getScrollId();

            count += hits.length;
            final BulkResponse bulkResponse = get(c -> {
                final BulkRequestBuilder bulkRequest = client.prepareBulk();
                for (final SearchHit hit : hits) {
                    bulkRequest.add(client.prepareDelete(hit.getIndex(), hit.getType(), hit.getId()));
                }
                return bulkRequest.execute();
            });
            if (bulkResponse.hasFailures()) {
                throw new EsAccessException(bulkResponse.buildFailureMessage());
            }
        }
        return count;
    }

    public interface OnConnectListener {
        void onConnect();
    }

    public void setScrollForDelete(Scroll scrollForDelete) {
        this.scrollForDelete = scrollForDelete;
    }

    public void setSizeForDelete(int sizeForDelete) {
        this.sizeForDelete = sizeForDelete;
    }

    public void setRetryInterval(long retryInterval) {
        this.retryInterval = retryInterval;
    }

    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    public void setConnTimeout(long connTimeout) {
        this.connTimeout = connTimeout;
    }

    public void setSearchPreference(String searchPreference) {
        this.searchPreference = searchPreference;
    }
}
