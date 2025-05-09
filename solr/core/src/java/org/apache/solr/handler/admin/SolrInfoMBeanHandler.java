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
package org.apache.solr.handler.admin;

import java.io.StringReader;
import java.text.NumberFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.SolrInfoBean;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.JavaBinResponseWriter;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthorizationContext;

/** A request handler that provides info about all registered SolrInfoMBeans. */
public class SolrInfoMBeanHandler extends RequestHandlerBase {

  /**
   * Take an array of any type and generate a Set containing the toString. Set is guarantee to never
   * be null (but may be empty)
   */
  private Set<String> arrayToSet(Object[] arr) {
    HashSet<String> r = new HashSet<>();
    if (null == arr) return r;
    for (Object o : arr) {
      if (null != o) r.add(o.toString());
    }
    return r;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    NamedList<NamedList<NamedList<Object>>> cats = getMBeanInfo(req);
    if (req.getParams().getBool("diff", false)) {
      ContentStream body = null;
      try {
        body = req.getContentStreams().iterator().next();
      } catch (Exception ex) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "missing content-stream for diff");
      }
      final String content = StrUtils.stringFromReader(body.getReader());

      NamedList<NamedList<NamedList<Object>>> ref = fromXML(content);

      // Normalize the output
      SolrQueryResponse wrap = new SolrQueryResponse();
      wrap.add("solr-mbeans", cats);
      cats =
          (NamedList<NamedList<NamedList<Object>>>)
              JavaBinResponseWriter.getParsedResponse(req, wrap).get("solr-mbeans");

      // Get rid of irrelevant things
      normalize(ref);
      normalize(cats);

      // Only the changes
      boolean showAll = req.getParams().getBool("all", false);
      rsp.add("solr-mbeans", getDiff(ref, cats, showAll));
    } else {
      rsp.add("solr-mbeans", cats);
    }
    rsp.setHttpCaching(false); // never cache, no matter what init config looks like
  }

  @SuppressWarnings("unchecked")
  static NamedList<NamedList<NamedList<Object>>> fromXML(String content) {
    int idx = content.indexOf("<response>");
    if (idx < 0) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Body does not appear to be an XML response");
    }

    try {
      XMLResponseParser parser = new XMLResponseParser();
      return (NamedList<NamedList<NamedList<Object>>>)
          parser.processResponse(new StringReader(content)).get("solr-mbeans");
    } catch (Exception ex) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Unable to read original XML", ex);
    }
  }

  protected NamedList<NamedList<NamedList<Object>>> getMBeanInfo(SolrQueryRequest req) {

    NamedList<NamedList<NamedList<Object>>> cats = new NamedList<>();

    String[] requestedCats = req.getParams().getParams("cat");
    if (null == requestedCats || 0 == requestedCats.length) {
      for (SolrInfoBean.Category cat : SolrInfoBean.Category.values()) {
        cats.add(cat.name(), new SimpleOrderedMap<>());
      }
    } else {
      for (String catName : requestedCats) {
        cats.add(catName, new SimpleOrderedMap<>());
      }
    }

    Set<String> requestedKeys = arrayToSet(req.getParams().getParams("key"));

    Map<String, SolrInfoBean> reg = req.getCore().getInfoRegistry();
    for (Map.Entry<String, SolrInfoBean> entry : reg.entrySet()) {
      addMBean(req, cats, requestedKeys, entry.getKey(), entry.getValue());
    }

    for (SolrInfoBean infoMBean : req.getCoreContainer().getResourceLoader().getInfoMBeans()) {
      addMBean(req, cats, requestedKeys, infoMBean.getName(), infoMBean);
    }
    return cats;
  }

  private void addMBean(
      SolrQueryRequest req,
      NamedList<NamedList<NamedList<Object>>> cats,
      Set<String> requestedKeys,
      String key,
      SolrInfoBean m) {
    if (!(requestedKeys.isEmpty() || requestedKeys.contains(key))) return;
    NamedList<NamedList<Object>> catInfo = cats.get(m.getCategory().name());
    if (null == catInfo) return;
    NamedList<Object> mBeanInfo = new SimpleOrderedMap<>();
    mBeanInfo.add("class", m.getName());
    mBeanInfo.add("description", m.getDescription());

    if (req.getParams().getFieldBool(key, "stats", false) && m.getSolrMetricsContext() != null)
      mBeanInfo.add("stats", m.getSolrMetricsContext().getMetricsSnapshot());

    catInfo.add(key, mBeanInfo);
  }

  protected NamedList<NamedList<NamedList<Object>>> getDiff(
      NamedList<NamedList<NamedList<Object>>> ref,
      NamedList<NamedList<NamedList<Object>>> now,
      boolean includeAll) {

    NamedList<NamedList<NamedList<Object>>> changed = new NamedList<>();

    // Cycle through each category
    for (int i = 0; i < ref.size(); i++) {
      String category = ref.getName(i);
      NamedList<NamedList<Object>> ref_cat = ref.get(category);
      NamedList<NamedList<Object>> now_cat = now.get(category);
      if (now_cat != null) {
        String ref_txt = ref_cat + "";
        String now_txt = now_cat + "";
        if (!ref_txt.equals(now_txt)) {
          // Something in the category changed
          // Now iterate the real beans

          NamedList<NamedList<Object>> cat = new SimpleOrderedMap<>();
          for (int j = 0; j < ref_cat.size(); j++) {
            String name = ref_cat.getName(j);
            NamedList<Object> ref_bean = ref_cat.get(name);
            NamedList<Object> now_bean = now_cat.get(name);

            ref_txt = ref_bean + "";
            now_txt = now_bean + "";
            if (!ref_txt.equals(now_txt)) {
              //              System.out.println( "----" );
              //              System.out.println( category +" : " + name );
              //              System.out.println( "REF: " + ref_txt );
              //              System.out.println( "NOW: " + now_txt );

              // Calculate the differences
              NamedList<Object> diff = diffNamedList(ref_bean, now_bean);
              diff.add("_changed_", true); // flag the changed thing
              cat.add(name, diff);
            } else if (includeAll) {
              cat.add(name, ref_bean);
            }
          }
          if (cat.size() > 0) {
            changed.add(category, cat);
          }
        } else if (includeAll) {
          changed.add(category, ref_cat);
        }
      }
    }
    return changed;
  }

  public NamedList<Object> diffNamedList(NamedList<?> ref, NamedList<?> now) {
    NamedList<Object> out = new SimpleOrderedMap<>();
    for (int i = 0; i < ref.size(); i++) {
      String name = ref.getName(i);
      Object r = ref.getVal(i);
      Object n = now.get(name);
      if (n == null) {
        if (r != null) {
          out.add("REMOVE " + name, r);
          now.remove(name);
        }
      } else if (r != null) {
        out.add(name, diffObject(r, n));
        now.remove(name);
      }
    }

    for (int i = 0; i < now.size(); i++) {
      String name = now.getName(i);
      Object v = now.getVal(i);
      if (v != null) {
        out.add("ADD " + name, v);
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  public Object diffObject(Object ref, Object now) {
    if (now instanceof Map) {
      now = new NamedList<>((Map<String, ?>) now);
    }
    if (ref instanceof NamedList) {
      return diffNamedList((NamedList<?>) ref, (NamedList<?>) now);
    }
    if (ref.equals(now)) {
      return ref;
    }
    StringBuilder str = new StringBuilder();
    str.append("Was: ").append(ref).append(", Now: ").append(now);

    if (ref instanceof Number) {
      NumberFormat nf = NumberFormat.getIntegerInstance(Locale.ROOT);
      if ((ref instanceof Double) || (ref instanceof Float)) {
        nf = NumberFormat.getInstance(Locale.ROOT);
      }
      double dref = ((Number) ref).doubleValue();
      double dnow = ((Number) now).doubleValue();
      double diff = Double.NaN;
      if (Double.isNaN(dref)) {
        diff = dnow;
      } else if (Double.isNaN(dnow)) {
        diff = dref;
      } else {
        diff = dnow - dref;
      }
      str.append(", Delta: ").append(nf.format(diff));
    }
    return str.toString();
  }

  /** The 'avgRequestsPerSecond' field will make everything look like it changed */
  public void normalize(NamedList<?> input) {
    input.remove("avgRequestsPerSecond");
    for (int i = 0; i < input.size(); i++) {
      Object v = input.getVal(i);
      if (v instanceof NamedList) {
        // edit in place so we don't need to return it
        normalize((NamedList<?>) v);
      }
    }
  }

  @Override
  public String getDescription() {
    return "Get Info (and statistics) for registered SolrInfoMBeans";
  }

  @Override
  public Category getCategory() {
    return Category.ADMIN;
  }

  @Override
  public Name getPermissionName(AuthorizationContext request) {
    return Name.METRICS_READ_PERM;
  }
}
