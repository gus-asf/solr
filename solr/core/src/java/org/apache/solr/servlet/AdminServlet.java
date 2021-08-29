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
package org.apache.solr.servlet;

import io.opentracing.Span;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.logging.MDCLoggingContext;
import org.apache.solr.servlet.CoreService.ServiceHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static org.apache.solr.servlet.ServletUtils.closeShield;
import static org.apache.solr.servlet.ServletUtils.configExcludes;
import static org.apache.solr.servlet.ServletUtils.excludedPath;
import static org.apache.solr.servlet.ServletUtils.sendError;
import static org.apache.solr.servlet.ServletUtils.ATTR_TRACING_SPAN;

@WebServlet
public class AdminServlet extends HttpServlet implements PathExcluder {
  
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  
  private ArrayList<Pattern> excludes;
  private ServiceHolder coreServiceHolder;
  protected String abortErrorMessage = null;

  @Override
  public void init() throws ServletException {
    configExcludes(this, getInitParameter("excludePatterns"));
    try {
      coreServiceHolder = CoreService.serviceForContext(getServletContext());
    } catch (InterruptedException e) {
      throw new ServletException(e);
    }
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    request = closeShield(request, false);
    response = closeShield(response, false);
    if (excludedPath(excludes, request, response, null)) {
      return;
    }
    final HttpServletResponse resp = response;
    final HttpServletRequest req = request;
    ServletUtils.rateLimitRequest(request, resp, () -> {
      Span span = (Span) req.getAttribute(ATTR_TRACING_SPAN);
      try {
        CoreContainer cores = coreServiceHolder.getService().getCoreContainer();
        MDCLoggingContext.reset();
        MDCLoggingContext.setTracerId(span.context().toTraceId()); // handles empty string
        MDCLoggingContext.setNode(cores);

        if (cores == null) {
          String message = "Server is shutting down or failed to initialize";
          sendError(503, message, resp);
          throw new SolrException(ErrorCode.SERVICE_UNAVAILABLE,message);
        }

        // continue with relevant stuff from init().
        String path = ServletUtils.getPathAfterContext(req);

      } catch (UnavailableException e) {
        String msg = "Core Container Unavailable";
        try {
          sendError(500, msg, resp);
        } catch (IOException ex) {
          ex.addSuppressed(e);
          String extendedMsg = msg + " and an error occurred when sending the error response to the client!";
          log.error(extendedMsg,ex);
        }
        log.error(msg,e);
      } catch (IOException e) {
        throw new SolrException(ErrorCode.SERVER_ERROR, e);
      }
    });
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
    // until I find a reason in the code to treat posts differently...
    doGet(req, resp);
  }

  @Override
  public void setExcludePatterns(ArrayList<Pattern> excludePatterns) {
    this.excludes = excludePatterns;
  }
}
