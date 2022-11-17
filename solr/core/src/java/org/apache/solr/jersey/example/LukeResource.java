package org.apache.solr.jersey.example;

import com.google.common.base.Charsets;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.Aliases;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.util.BytesOutputStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.response.QueryResponseWriter;
import org.apache.solr.response.QueryResponseWriterUtil;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.servlet.HttpSolrCall;
import org.apache.solr.servlet.ResponseUtils;
import org.apache.solr.servlet.ServletUtils;
import org.apache.solr.servlet.SolrRequestParsers;
import org.apache.solr.servlet.cache.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.solr.common.cloud.ZkStateReader.CORE_NAME_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.NODE_NAME_PROP;
import static org.apache.solr.servlet.HttpSolrCall.random;

@Path("/")
public class LukeResource {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Context
  HttpServletResponse response;
  @Context
  HttpServletRequest req;
  @Inject
  CoreContainer cc;

  public LukeResource() {
    log.info("Handling Luke Request");
  }

  @GET
  @Path("/{collection}/admin/luke")
  public Response get(@PathParam("collection") String collection, @QueryParam("wt") String writerType)
      throws Exception { // todo of course don't throw exception, fix later

    log.info("json response");
    String finalCollection = collection;
    collection = resolveCollectionListOrAlias(collection,cc).stream().findFirst()
        .orElseThrow(() -> new SolrException(SolrException.ErrorCode.UNKNOWN, finalCollection + " not found"));

    SolrCore core = getCoreByCollection(collection, false);

    //todo handle forwarding via jaxrs client?
    if (core == null) {
      throw new  SolrException(SolrException.ErrorCode.UNKNOWN, finalCollection + " not found");
    }

    String writerMediaType = getMtForWriter(writerType);
    SolrQueryRequest sReq = SolrRequestParsers.DEFAULT.parse(core, ServletUtils.getPathAfterContext(req), req);
    //todo magic value "/admin/luke" yuck
    SolrRequestHandler requestHandler = core.getRequestHandler( "/admin/luke");
    SolrQueryResponse rsp = new SolrQueryResponse();
    Response.ResponseBuilder jaxResp;
    try {
      requestHandler.handleRequest(sReq, rsp);
      QueryResponseWriter queryResponseWriter = getResponseWriter(sReq,core);
      String response = writeResponse(sReq,rsp, queryResponseWriter,Method.GET);
      jaxResp = Response.ok(response, writerMediaType);
    } catch (SolrException e) {
      log.error(e.getMessage(),e);
      jaxResp = Response.status(e.code()).entity(rsp.getResponse()).type(writerMediaType);
    } catch (Exception e) {
      //todo this probably is wrong, fix later
      log.error(e.getMessage(),e);
      jaxResp = Response.serverError().entity(e.getMessage()).type(MediaType.TEXT_PLAIN);
    }
    Iterator<Map.Entry<String, String>> headers = rsp.httpHeaders();
    while (headers.hasNext()) {
      Map.Entry<String, String> header = headers.next();
      jaxResp.header(header.getKey(), header.getValue());
    }
    return jaxResp.build();
  }

  private String getMtForWriter(String writerType) {
    //todo: actual logic
    return MediaType.APPLICATION_JSON;
  }



  /**
   * Resolves the parameter as a potential comma delimited list of collections, and resolves aliases
   * too. One level of aliases pointing to another alias is supported. De-duplicates and retains the
   * order.
   */

  //todo blantant cut and paste obviously bad
  protected List<String> resolveCollectionListOrAlias(String collectionStr, CoreContainer cores) {
    if (collectionStr == null || collectionStr.trim().isEmpty()) {
      return Collections.emptyList();
    }
    List<String> result = null;
    LinkedHashSet<String> uniqueList = null;
    Aliases aliases = cores.getAliases();
    List<String> inputCollections = StrUtils.splitSmart(collectionStr, ",", true);
    if (inputCollections.size() > 1) {
      uniqueList = new LinkedHashSet<>();
    }
    for (String inputCollection : inputCollections) {
      List<String> resolvedCollections = aliases.resolveAliases(inputCollection);
      if (uniqueList != null) {
        uniqueList.addAll(resolvedCollections);
      } else {
        result = resolvedCollections;
      }
    }
    if (uniqueList != null) {
      return new ArrayList<>(uniqueList);
    } else {
      return result;
    }
  }

  protected SolrCore getCoreByCollection(String collectionName, boolean isPreferLeader) {
    ZkStateReader zkStateReader = cc.getZkController().getZkStateReader();

    ClusterState clusterState = zkStateReader.getClusterState();
    DocCollection collection = clusterState.getCollectionOrNull(collectionName, true);
    if (collection == null) {
      return null;
    }

    Set<String> liveNodes = clusterState.getLiveNodes();

    if (isPreferLeader) {
      List<Replica> leaderReplicas =
          collection.getLeaderReplicas(cc.getZkController().getNodeName());
      SolrCore core = randomlyGetSolrCore(liveNodes, leaderReplicas);
      if (core != null) return core;
    }

    List<Replica> replicas = collection.getReplicas(cc.getZkController().getNodeName());
    return randomlyGetSolrCore(liveNodes, replicas);
  }

  private SolrCore randomlyGetSolrCore(Set<String> liveNodes, List<Replica> replicas) {
    if (replicas != null) {
      HttpSolrCall.RandomIterator<Replica> it = new HttpSolrCall.RandomIterator<>(random, replicas);
      while (it.hasNext()) {
        Replica replica = it.next();
        if (liveNodes.contains(replica.getNodeName())
            && replica.getState() == Replica.State.ACTIVE) {
          SolrCore core = checkProps(replica);
          if (core != null) return core;
        }
      }
    }
    return null;
  }

  private SolrCore checkProps(ZkNodeProps zkProps) {
    String corename;
    SolrCore core = null;
    if (cc.getZkController().getNodeName().equals(zkProps.getStr(NODE_NAME_PROP))) {
      corename = zkProps.getStr(CORE_NAME_PROP);
      core = cc.getCore(corename);
    }
    return core;
  }

  /**
   * Returns {@link QueryResponseWriter} to be used. When {@link CommonParams#WT} not specified in
   * the request or specified value doesn't have corresponding {@link QueryResponseWriter} then,
   * returns the default query response writer Note: This method must not return null
   */
  protected QueryResponseWriter getResponseWriter(SolrQueryRequest solrReq, SolrCore core) {
    String wt = solrReq.getParams().get(CommonParams.WT);
    if (core != null) {
      return core.getQueryResponseWriter(wt);
    } else {
      return SolrCore.DEFAULT_RESPONSE_WRITERS.getOrDefault(
          wt, SolrCore.DEFAULT_RESPONSE_WRITERS.get("standard"));
    }
  }

  protected String writeResponse( SolrQueryRequest solrReq,
      SolrQueryResponse solrRsp, QueryResponseWriter responseWriter, Method reqMethod)
      throws IOException {
    String result ="";
    try {
      Object invalidStates = solrReq.getContext().get(CloudSolrClient.STATE_VERSION);
      // This is the last item added to the response and the client would expect it that way.
      // If that assumption is changed , it would fail. This is done to avoid an O(n) scan on
      // the response for each request
      if (invalidStates != null) solrRsp.add(CloudSolrClient.STATE_VERSION, invalidStates);
      // Now write it out
      final String ct = responseWriter.getContentType(solrReq, solrRsp);
      // don't call setContentType on null
      if (null != ct) response.setContentType(ct);

      if (solrRsp.getException() != null) {
        NamedList<Object> info = new SimpleOrderedMap<>();
        int code = ResponseUtils.getErrorInfo(solrRsp.getException(), info, log);
        solrRsp.add("error", info);
        response.setStatus(code);
      }

      if (Method.HEAD != reqMethod) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); // todo maybe not great for
        QueryResponseWriterUtil.writeQueryResponse(out, responseWriter, solrReq, solrRsp, ct);
        result = out.toString(Charsets.UTF_8);
      }
      // else http HEAD request, nothing to write out, waited this long just to get ContentType
    } catch (EOFException e) {
      log.info("Unable to write response, client closed connection or we are shutting down", e);
    }
    return result;
  }

}
